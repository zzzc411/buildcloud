-- ============================================================================
-- stock-prededuct-rollback.lua —— 预扣回滚（INCRBY 还原可用量 + HDEL 明细）
-- ============================================================================
--
-- 使用场景（spec §6.1 步骤⑤⑥）：
--   1. 审批**驳回** → 恢复库存 + 冲正流水；
--   2. 超时**关单**（延迟消息 30 分钟到期未审完）→ 恢复库存 + 冲正流水；
--   3. 事务消息的本地事务失败 → rollback half 消息后，把预扣还回去。
-- 三条路径共用本脚本，保证"回滚"只有一种实现（plan-w5 Phase 3「驳回与超时共用回滚组件」）。
--
-- ---------------------------------------------------------------------------
-- 为什么回滚也要用 Lua（不能 DECRBY 与 HDEL 分两次发）
-- ---------------------------------------------------------------------------
-- 回滚的原子要求是「**只有明细存在时才还原**」：
--   - 明细存在 → 说明这笔预扣确实发生过 → INCRBY 还原 + HDEL 明细（一个事务性动作）；
--   - 明细不存在 → 说明这笔根本没预扣过（或已被回滚/被对账清理过）→ **什么都不做**，
--     返回「无需回滚」。
-- 若拆成客户端两步（先 HDEL 再 INCRBY，或先 GET 再 INCRBY），超时关单与人工驳回并发时
-- 会**双重还原**：两个调用方先后认为"自己拿到了明细"，各 INCRBY 一次，库存凭空多出一份
-- （与超卖相反、但同样是账不平）。脚本内完成则第二个调用方必然看到明细已空而空转。
--
-- ---------------------------------------------------------------------------
-- KEYS / ARGV
-- ---------------------------------------------------------------------------
--   KEYS[1] = 可用库存计数 key：pm:stock:{projectId}:{materialId}
--   KEYS[2] = 预扣明细 hash  key：pm:prededuct:{projectId}:{materialId}
--   ARGV[1] = reqNo（要找的预扣记录）
--   ARGV[2] = qty  （**预期数量**：只有在明细记录的数量与之一致时才按传入值还原，
--                    防止调用方传错数量导致「还多/还少」）
--
-- ---------------------------------------------------------------------------
-- 返回值
-- ---------------------------------------------------------------------------
--    1  ROLLED_BACK     已还原（INCRBY + HDEL 完成）
--    0  NOT_FOUND       无该 reqNo 的预扣明细（未预扣/已回滚），无任何副作用
--   -1  NEED_INIT       明细存在但可用库存 key 丢了（极端：只删了 pm:stock:*），
--                       本次不还原（无法判断基线），由对账 Job 重建后人工/任务补还原
--   -2  QTY_MISMATCH    明细里的数量与 ARGV[2] 不一致（调用方数量传错），
--                       **不还原**并原样回报，避免"回滚一个错误的量"污染账目；
--                       由调用方按明细真实数量重试（queryPrededuct 可查真值）
--
-- 注意：本脚本**不做 DB 操作**，与预扣脚本同口径——Redis 只负责内存口径的还原，
-- DB 侧的冲正流水由业务侧（审批驳回/关单）在自己的事务里写（biz_type=4）。

local stockKey     = KEYS[1]
local predeductKey = KEYS[2]
local reqNo        = ARGV[1]
local qty          = tonumber(ARGV[2])

if reqNo == nil or reqNo == '' or qty == nil or qty <= 0 then
    return redis.error_reply('stock-prededuct-rollback: invalid arguments reqNo=' ..
            tostring(reqNo) .. ' qty=' .. tostring(ARGV[2]))
end

-- ① 查明细：没有就是"无需回滚"，绝不能凭空 INCRBY
local recorded = redis.call('HGET', predeductKey, reqNo)
if recorded == false then
    return 0
end

-- ② 数量一致性校验：不一致说明调用方与本脚本的账对不上，宁可不改也不改错
if tonumber(recorded) ~= qty then
    return -2
end

-- ③ 可用量 key 必须存在才能还原（否则还原出来的基线是错的）
if redis.call('EXISTS', stockKey) == 0 then
    return -1
end

-- ④ 还原 + 清明细：两条命令在同一个脚本内，不存在"还原了但明细没删"的中间态
redis.call('INCRBY', stockKey, qty)
redis.call('HDEL', predeductKey, reqNo)
return 1
