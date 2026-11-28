-- ============================================================================
-- stock-prededuct.lua —— 材料申领 V2 的「Redis Lua 原子预扣」（spec v1.2 §6.1 步骤③）
-- ============================================================================
--
-- 定位：W5 V2 链路的第一道防线（spec §6.1「防超卖三道防线」：Lua 原子预扣 → DB 乐观锁
--      条件更新 → 对账 Job）。V1 的 `SELECT ... FOR UPDATE` 行锁不在此脚本职责内，
--      它是 W4 留下的对照组，本脚本只服务于 V2。
--
-- ---------------------------------------------------------------------------
-- 为什么必须用 Lua：原子性来自 Redis 的「脚本整体单线程执行」
-- ---------------------------------------------------------------------------
-- 「取可用量 → 判断够不够 → 扣减 → 记预扣明细」是四步复合操作。若拆成四条客户端命令
-- （GET / 判断 / DECRBY / HSET），两条命令之间必然夹带其它客户端的命令——100 并发下
-- 两个请求都读到 100、都认为够、都扣，结果就是个负数库存（超卖）。Redis 执行 EVAL 时
-- 整体占住命令队列，**脚本内不存在交错**，所以这四步天然等价于一次操作。
-- 这一点不需要"小心"也不用"加锁"：Redis 单线程 + 脚本不 yield = 天然互斥。
--
-- 第二条理由：脚本里**没有** DB 操作，也不应有。
--   V2 的数据库扣减由 RocketMQ 消费者异步完成（spec §6.1 步骤④：消费者按 version 乐观锁
--   落库 + 写 stock_log + 状态→已扣减）。Redis 预扣只负责「在内存里先把票占住」，
--   把 DB 挡住（失败请求不打 DB），因此脚本必须是纯内存操作：一旦脚本里连 DB，
--   单线程的 Redis 就会被慢 SQL 拖死，整个缓存层陪葬。
--
-- ---------------------------------------------------------------------------
-- KEYS / ARGV 约定
-- ---------------------------------------------------------------------------
--   KEYS[1] = 可用库存计数 key：pm:stock:{projectId}:{materialId}   （string，可用量）
--   KEYS[2] = 预扣明细 hash  key：pm:prededuct:{projectId}:{materialId}（field=reqNo, value=qty）
--   ARGV[1] = reqNo（申领单号，预扣明细的 field，也是幂等键）
--   ARGV[2] = qty  （本次预扣数量，正整数；调用方负责保证 >0）
--
--   两个 key 都必须落在同一个 hash slot（同一 {projectId}:{materialId} 后缀即天然满足）。
--   当前是单实例部署，cluster 下也无需 hash tag 额外处理——不要擅自给 key 加 {} 包裹，
--   那会改变 key 命名口径（spec §6.2 明文规定 `pm:stock:{projectId}:{materialId}`）。
--
-- ---------------------------------------------------------------------------
-- 返回值（数字码，调用方按码分派；禁止用布尔，Lua 的 false/nil 与 Redis 的 null 易混）
-- ---------------------------------------------------------------------------
--    1  SUCCESS      预扣成功（available 已 DECRBY、明细已 HSET）
--    0  NOT_ENOUGH   库存不足（available 不变、明细不写）
--   -1  NEED_INIT    可用库存 key 不存在，需先从 DB 加载初始化（调用方走 initAvailable）
--    2  IDEMPOTENT   同一 reqNo 已预扣过（幂等成功：重复提交不重复扣）
--
-- 关于 NEED_INIT 用 -1 而不是 0：把「没初始化」与「库存不足」混成一个码，会让
-- "库存为 0"与"缓存丢了"在日志与告警里不可区分——前者是正常的业务结果，
-- 后者是需要立刻修复的缓存事故，运维口径完全不同。
--
-- ---------------------------------------------------------------------------
-- 幂等设计：明细 hash 就是幂等记录本
-- ---------------------------------------------------------------------------
-- 每次成功预扣都在 KEYS[2] 里写 field=reqNo。重复提交（前端双击 / 网关重试 / MQ 重投）
-- 命中已有 field 时直接返回 2，**不动 available**。判重与扣减仍在同一个脚本里，
-- 所以"判断是否重复"与"扣减"之间也不存在竞态。
-- 明细的清理：正常路径由回滚脚本（stock-prededuct-rollback.lua）HDEL；
-- 遗留明细由对账 Job 按 TTL 清理（spec §6.1 步骤⑦）。TTL 交给 initAvailable 统一设置，
-- 不给明细单独续期——单条明细续期会让孤儿记录永久留存。
--
-- ---------------------------------------------------------------------------
-- 性能/成本说明（面试口径）
-- ---------------------------------------------------------------------------
-- 脚本只做 O(1) 操作：EXISTS / HGET / DECRBY / HSET，无 KEYS 扫描、无 HGETALL。
-- 只读一次 available，不额外读明细全表。

if redis.replicate_commands then
    -- Redis 3.2+ 默认可复制的脚本；显式调用只为兼容老版本客户端的语义，
    -- 在 7.x 下是空操作。
    redis.replicate_commands()
end

local stockKey     = KEYS[1]
local predeductKey = KEYS[2]
local reqNo        = ARGV[1]
local qty          = tonumber(ARGV[2])

-- 参数兜底：qty 非法时直接按"库存不足"外的第三种情况处理——这里选择返回 -1（需初始化）
-- 不安全，会误导调用方去初始化。改为抛错：Redis 会把错误原样返回给客户端，
-- 调用方（StockPredeductService）捕获后按参数错误处理并告警，绝不能让非法数量静默通过。
if reqNo == nil or reqNo == '' or qty == nil or qty <= 0 then
    return redis.error_reply('stock-prededuct: invalid arguments reqNo=' ..
            tostring(reqNo) .. ' qty=' .. tostring(ARGV[2]))
end

-- ① 幂等：明细里已有该 reqNo → 重复提交，直接返回幂等成功，不重复扣
if redis.call('HEXISTS', predeductKey, reqNo) == 1 then
    return 2
end

-- ② 取可用库存：key 不存在说明还没初始化（入库时没同步 / 缓存被清）
local current = redis.call('GET', stockKey)
if current == false then
    return -1
end

local available = tonumber(current)
-- 脏数据兜底：key 存在但不是数字（被其它写入方写坏）→ 按"需初始化"上报，
-- 让调用方走 DB 重载修复，而不是在脚本里抛错把整条链路打挂。
if available == nil then
    return -1
end

-- ③ 校验 + 扣减 + 记明细（三步在同一脚本内完成，不存在交错）
if available < qty then
    return 0
end
redis.call('DECRBY', stockKey, qty)
redis.call('HSET', predeductKey, reqNo, qty)
return 1
