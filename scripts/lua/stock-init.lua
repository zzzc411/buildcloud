-- ============================================================================
-- stock-init.lua —— 初始化 / 重置可用库存计数（入库时同步刷新缓存）
-- ============================================================================
--
-- 为什么初始化也要单独一个脚本：
--   「SET 可用量」与「清掉旧预扣明细」必须一起做。只 SET 不清明细的话，
--   重新入库后旧 reqNo 的明细还在，那些单据再提交一次会被判成 IDEMPOTENT（返回 2）
--   而**实际没有扣减**——等待消费者的将是一笔"预扣不存在"的扣减，账立刻不平。
--   反之只清明细不 SET 也不行：可用量会停留在旧值。
--
-- 注意：reset（清明细）只应在"整笔重置/重建缓存"时用；**日常入库是累加**，
--   走 ARGV[3]=accumulate=1 的分支，绝不触碰明细。
--
-- ---------------------------------------------------------------------------
-- KEYS / ARGV
-- ---------------------------------------------------------------------------
--   KEYS[1] = pm:stock:{projectId}:{materialId}
--   KEYS[2] = pm:prededuct:{projectId}:{materialId}
--   ARGV[1] = qty        初始可用量（>= 0）
--   ARGV[2] = ttlSeconds 可选，可用量 key 的 TTL（<=0 或空表示不过期）
--   ARGV[3] = mode       可选：'reset'（默认，SET 并清明细）| 'accumulate'（INCRBY，保留明细）
--
-- ---------------------------------------------------------------------------
-- 返回值
-- ---------------------------------------------------------------------------
--    1  OK         初始化完成
--   -3  INVALID    参数非法（qty 为负 / mode 不认识）
--   -4  MISSING_STOCK_KEY  accumulate 模式下 key 不存在（应先走 reset 建 key）

local stockKey     = KEYS[1]
local predeductKey = KEYS[2]
local qty          = tonumber(ARGV[1])
local ttl          = tonumber(ARGV[2])
local mode         = ARGV[3]
if mode == nil or mode == '' then
    mode = 'reset'
end

if qty == nil or qty < 0 then
    return redis.error_reply('stock-init: invalid qty=' .. tostring(ARGV[1]))
end

if mode == 'accumulate' then
    -- 日常入库：只累加，不动明细（明细属于在途单据，不能被入库影响）
    if redis.call('EXISTS', stockKey) == 0 then
        return -4
    end
    redis.call('INCRBYFLOAT', stockKey, qty)
elseif mode == 'reset' then
    -- 初始化 / 重建：先清明细再写基线，顺序无所谓（脚本内原子），但明写在前面更符合阅读习惯
    redis.call('DEL', predeductKey)
    redis.call('SET', stockKey, tostring(qty))
    if ttl ~= nil and ttl > 0 then
        redis.call('EXPIRE', stockKey, ttl)
        redis.call('EXPIRE', predeductKey, ttl)
    end
else
    return -3
end

return 1
