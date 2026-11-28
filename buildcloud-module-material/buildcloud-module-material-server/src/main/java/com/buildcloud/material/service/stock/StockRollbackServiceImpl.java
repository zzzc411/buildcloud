package com.buildcloud.material.service.stock;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionItemMapper;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import com.buildcloud.material.service.stock.redis.RequisitionRollbackResult;
import com.buildcloud.material.service.stock.redis.StockRollbackResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_ITEM_EMPTY;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_EXISTS;

/**
 * {@link StockRollbackService} 的实现（驳回 / 取消 / 超时关单**共用的唯一回补实现**）
 *
 * ========== 三道幂等（从粗到细，缺一不可） ==========
 *
 * | 层 | 判据 | 挡的是哪种重复 |
 * | --- | --- | --- |
 * | ① 单据状态 CAS | 调用方在进本方法**之前**已用 CAS 拿到"这次归我处理" | 两个并发回调/人工+超时同时触发 |
 * | ② 行级冲正流水存在性（本类） | 本单本物料已有 `biz_type=4` → 跳过该行 | **单据状态被人为改回已提交/已扣减后重新触发**（数据修复、回滚重放） |
 * | ③ Redis 明细存在性（P1 的 Lua） | 明细不存在 → `NOT_FOUND`，不 INCRBY | 超时关单与驳回并发时把 Redis 还两次 |
 *
 * 为什么第 ② 层必须存在（单测 `testRollback_*` 钉住）：
 * 超时关单是**系统驱动**的，MQ 至少一次语义下同一条消息可能被重复投递；
 * 若期间单据被人工改回「已提交(1)」（数据修复很常见），只靠第 ① 层就会**第二次回补**——
 * 库存凭空多出来，是最难查的一类账务错误。`biz_type=4` 流水是这次回补的**持久凭证**，
 * 用"有没有这条凭证"判重，与 V2 消费者的"本行是否已有 biz_type=2 流水"完全同构。
 *
 * ========== 为什么按「本行是否扣过 DB」决定回补，而不是无条件回补 ==========
 *
 * 同一张单据可能处于三种库存事实之一：
 * <pre>
 *   V1 链路（提交即扣 DB）        : 有 biz_type=2 流水 → 必须回补 DB + 写 biz_type=4
 *   V2 链路（Redis 预扣在途）     : 没有 biz_type=2 流水 → DB **没有**扣过，回补 DB 就是凭空加库存
 *   V2 链路（消费者已扣 DB）      : 有 biz_type=2 流水 → 同 V1，回补 DB
 * </pre>
 * 所以判据只能是"流水"，不能是"单据状态"或"内存里以为的那样"。
 * 对 V2 在途单据，本组件只做 ④（还 Redis 预扣），DB 一行不动——这正是"少卖方向可接受"的那一侧。
 *
 * ========== balance_after ==========
 *
 * 与 V1 回补同口径：`锁内读到的 available_qty + 本次回补量`。行已被本事务 `FOR UPDATE` 排他锁住，
 * 期间没有别的写入，所以算出来的就是提交后的真实余额（不需要再回查，回查还会多一次往返）。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class StockRollbackServiceImpl implements StockRollbackService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * 冲正流水备注的前缀（与 V1 的「取消冲正：」区分；最终文案 = 前缀 + 原因 + 单号）
     *
     * 为什么流水备注要带「原因」：三种触发者（驳回/取消/超时）在流水表上 biz_no 相同、
     * biz_type 相同，只靠备注能一眼看出是谁触发的。对账发现差异时这条信息决定找谁核对。
     */
    static final String REVERSAL_REMARK_PREFIX = "申领回滚冲正：";

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private RequisitionItemMapper requisitionItemMapper;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    /**
     * Redis 预扣组件（P1）：回滚预扣。V1 链路上本单从未预扣过 → 每次返回 NOT_FOUND，无副作用。
     */
    @Resource
    private StockPredeductService stockPredeductService;

    // ========== 对外入口 ==========

    @Override
    public RequisitionRollbackResult rollbackForRequisition(Long requisitionId, String reqNo, String reason) {
        RequisitionDO requisition = requisitionId == null ? null : requisitionMapper.selectById(requisitionId);
        if (requisition == null) {
            // 单据不存在（被物理删除 / 租户上下文不对）：调用方无法推进终态，这里必须抛错让它整体回滚。
            // 静默返回"已回滚"会让调用方把状态改成已关闭——一张不存在的单据上"成功关单"，纯属假象。
            log.error("[rollbackForRequisition][申领单不存在，无法回滚 requisitionId={} reqNo={}]",
                    requisitionId, reqNo);
            throw new IllegalStateException("申领单不存在，无法回滚：requisitionId=" + requisitionId
                    + ", reqNo=" + reqNo);
        }
        return rollbackForRequisition(requisition, null, reason);
    }

    @Override
    public RequisitionRollbackResult rollbackForRequisition(RequisitionDO requisition,
                                                            List<RequisitionItemDO> items, String reason) {
        if (requisition == null || requisition.getId() == null) {
            throw new IllegalArgumentException("回滚申领单时 requisition 与其 id 不能为空");
        }
        Long requisitionId = requisition.getId();
        Long projectId = requisition.getProjectId();
        String reqNo = requisition.getReqNo();
        if (projectId == null || StrUtil.isBlank(reqNo)) {
            // projectId / reqNo 是流水与 Redis 明细的键，缺一个就会写到错的地方（或 NPE 在半路）
            throw new IllegalArgumentException("回滚申领单时 projectId / reqNo 不能为空：requisitionId="
                    + requisitionId + ", projectId=" + projectId + ", reqNo=" + reqNo);
        }

        // 明细**以库为准**：调用方传了就用它做行数校验，没传就重查。
        // 为什么不完全信任调用方快照：超时关单的调用方是在 CAS 之前读的明细，
        // 而 CAS 期间单据可能被改成"已扣减"（审批刚好通过），此时明细一般没变，
        // 但"改单"是真会发生的（管理端改明细后重新提交审批）；以库为准才能保证与扣减量一致。
        List<RequisitionItemDO> dbItems = requisitionItemMapper.selectListByRequisitionId(requisitionId);
        requireRequisitionItem(dbItems, requisitionId, reqNo);
        if (items != null && items.size() != dbItems.size()) {
            // 只做提示，不据此计算：说明调用方拿的是旧快照，以库为准更安全
            log.warn("[rollbackForRequisition][调用方明细快照({}) 与库({}) 行数不一致，以库为准 requisitionId={} reqNo={}]",
                    items.size(), dbItems.size(), requisitionId, reqNo);
        }

        // 按 materialId 升序：与 V1 扣减/回补、V2 消费者同一加锁顺序（跨版本同一约定，防 AB-BA 死锁）
        List<RequisitionItemDO> orderedItems = dbItems.stream()
                .sorted(Comparator.comparing(RequisitionItemDO::getMaterialId))
                .toList();

        String normalizedReason = StrUtil.blankToDefault(reason, "申领回滚");

        // ===================== 第 0 遍：Redis 预检（fail fast，**必须在任何 DB 写入之前**） =====================
        // 为什么不能用「每行 DB 回补 → 该行 Redis 还原」的顺序（W5-P3 集成测试实测踩到）：
        // `StockPredeductService#rollback` 对 NEED_INIT / QTY_MISMATCH 是**抛业务异常且拒绝改动**，
        // 而 DB 回补已经提交在同一个本地事务里——事务回滚后 DB 是回去了，
        // 但因为 Redis 与 DB 不在同一个事务里（非 XA），一旦 Redis 抛错的时机在 DB 写入之后，
        // 就会出现"DB 已补、Redis 没还"的中途状态，且日志里只看到一次异常，很难判断到底补没补。
        // 集成测试 `testRollback_realRedis_qtyMismatch_throwsAndKeepsBalance` 就是在断言这条边界。
        //
        // 预检的做法：逐行调一次真正的 rollback（它就是"还 Redis"这个动作本身，不是试探），
        // 把 Redis 还原**提前**完成；随后第 1 遍只做 DB 回补，不再碰 Redis。
        // 好处是失败点全部集中在最前面：Redis 对不上 → 直接在零 DB 写入的状态下抛错。
        // 代价是"Redis 已还原、DB 回补失败"的窗口——该窗口由对账 Job（P4）兜底，
        // 且方向是"DB 少补"（少卖），比"DB 多补"（超卖方向）安全。
        int redisRolledBack = 0;
        int redisNotFound = 0;
        for (RequisitionItemDO item : orderedItems) {
            requirePositiveQuantity(item.getQuantity(), requisitionId, item.getMaterialId());
            StockRollbackResult redisResult = stockPredeductService.rollback(reqNo, projectId,
                    item.getMaterialId(), item.getQuantity());
            if (redisResult == StockRollbackResult.ROLLED_BACK) {
                redisRolledBack++;
            } else {
                redisNotFound++;
            }
        }

        int restoredItemCount = 0;
        int acquiredItemCount = 0;
        BigDecimal restoredQuantity = ZERO;

        for (RequisitionItemDO item : orderedItems) {
            BigDecimal quantity = item.getQuantity();

            // ===== ① 行级幂等：已有冲正流水 → 本行已经补过 =====
            Long reversalCount = stockLogMapper.selectCountByBizNoAndType(projectId, item.getMaterialId(), reqNo,
                    StockBizTypeEnum.REVERSAL.getBizType());
            boolean hasReversalLog = reversalCount != null && reversalCount > 0;
            // 本行是否真的扣过 DB（biz_type=2 流水是唯一可靠判据，见类注释）
            Long deductCount = stockLogMapper.selectCountByBizNoAndType(projectId, item.getMaterialId(), reqNo,
                    StockBizTypeEnum.REQUISITION_OUT.getBizType());
            boolean hasDeductLog = deductCount != null && deductCount > 0;

            if (hasReversalLog) {
                // 冲正流水已存在说明本行确实占过库存（否则不会有这条流水）→ 计入"已归还"
                // 注意：这种情况下该行的 Redis 预扣已在上一轮被 HDEL 过，本轮的 rollback 只会返回
                // NOT_FOUND（无副作用），因此不需要额外补偿逻辑。
                acquiredItemCount++;
                restoredQuantity = restoredQuantity.add(quantity);
                log.info("[rollbackForRequisition][幂等：本行已有 {} 条冲正流水，跳过回补 reqNo={} materialId={}]",
                        reversalCount, reqNo, item.getMaterialId());
            } else if (hasDeductLog) {
                // **只有这一行真的动过 DB**，才需要行锁回补 + 写冲正流水
                restoreOneItem(requisition, item, quantity, normalizedReason);
                restoredItemCount++;
                acquiredItemCount++;
                restoredQuantity = restoredQuantity.add(quantity);
            } else {
                // V2 在途（Redis 预扣、DB 未扣）或该行从未扣减：**不回补 DB**（否则凭空加库存）。
                // 它的 Redis 预扣已在第 0 遍还原过（或本来就没有），所以本行也已结算清楚。
                //
                // 能走到这个分支还说明一件事：Redis 与 DB **已经不漂移了**（两边都没有这笔）。
                // 如果出现"Redis 有预扣、DB 没扣、又拿不到冲正流水"的组合，只可能是第 0 遍的
                // rollback 返回了 QTY_MISMATCH/NEED_INIT——那会抛错，压根不会继续往下走。
                acquiredItemCount++;
                log.info("[rollbackForRequisition][本行无申领扣减流水（DB 未扣过），跳过 DB 回补 reqNo={} materialId={}]",
                        reqNo, item.getMaterialId());
            }
        }

        // ===== 结论 =====
        boolean fullySettled = acquiredItemCount == orderedItems.size();
        RequisitionRollbackResult.Type type;
        if (restoredItemCount > 0) {
            // 有行真的动了 DB → 无论剩余行是什么情况，只要都结算清楚就是 RESTORED
            type = fullySettled
                    ? RequisitionRollbackResult.Type.RESTORED
                    : RequisitionRollbackResult.Type.PARTIAL_RESTORED;
        } else if (fullySettled) {
            // 一行 DB 都没动：V2 在途/已结算过的单据。是否"有东西被还回去"不影响结论
            type = redisRolledBack > 0
                    ? RequisitionRollbackResult.Type.SETTLED_AT_REDIS_ONLY
                    : RequisitionRollbackResult.Type.SKIPPED_NO_STOCK_IMPACT;
        } else {
            // 保留这条防御分支：acquiredItemCount 的分母是明细行数，而 ① ② ③ 三个分支每个都
            // 恰好 +1，所以目前走不到这里。一旦将来有人给某个分支加了 continue（或漏了计数），
            // 这里会把"仍有 X 行没结算清楚"喊出来，而不是默默当作成功。
            type = RequisitionRollbackResult.Type.PARTIAL_RESTORED;
        }
        if (type == RequisitionRollbackResult.Type.PARTIAL_RESTORED) {
            // 没还干净的残留必须显眼：调用方据此决定是否让单据进入终态
            log.error("[rollbackForRequisition][部分回补：仍有 {} 行库存被本单占用，需人工核对 requisitionId={} reqNo={}]",
                    orderedItems.size() - acquiredItemCount, requisitionId, reqNo);
        }
        RequisitionRollbackResult result = new RequisitionRollbackResult(type, restoredItemCount,
                restoredQuantity, redisRolledBack, redisNotFound);
        log.info("[rollbackForRequisition][回滚完成 requisitionId={} reqNo={} reason={} result={}]",
                requisitionId, reqNo, normalizedReason, result);
        return result;
    }

    // ========== 内部方法 ==========


    /**
     * 明细非空校验（两种回滚路径共用，避免两处文案/错误码分叉）
     */
    private void requireRequisitionItem(List<RequisitionItemDO> items, Long requisitionId, String reqNo) {
        if (CollUtil.isEmpty(items)) {
            // 明细被删空的单据：没有"该退多少"的依据。抛错整体回滚（调用方的 CAS 一起撤销），
            // 交由人工核对——静默跳过 = 「单据已关闭但库存永久少一块」且无人知晓。
            log.error("[rollbackForRequisition][申领单明细为空，无法确定回补量 requisitionId={} reqNo={}]",
                    requisitionId, reqNo);
            throw exception(REQUISITION_ITEM_EMPTY);
        }
    }

    /**
     * 明细数量校验（> 0）
     */
    private void requirePositiveQuantity(BigDecimal quantity, Long requisitionId, Long materialId) {
        if (quantity == null || quantity.signum() <= 0) {
            // 数量非正的明细没有业务含义（0 回补会写一条 quantity=0 的流水，负回补会反向扣库存）
            log.error("[rollbackForRequisition][明细数量非法，整单回滚 requisitionId={} materialId={} qty={}]",
                    requisitionId, materialId, quantity);
            throw new IllegalArgumentException("申领明细数量必须大于 0：requisitionId=" + requisitionId
                    + ", materialId=" + materialId + ", qty=" + quantity);
        }
    }


    /**
     * 单行回补：行锁 → `available_qty += qty` → 写 `biz_type=4` 冲正流水
     *
     * 与 {@code RequisitionV1SubmitHandler#revertOneItem} 的 SQL 完全相同（同一把锁、同一套 Mapper 方法），
     * 差别只在：本方法先做了"本行是否扣过/补过"的判据（幂等），并把原因写进备注。
     *
     * @param requisition 申领单（提供 projectId / reqNo）
     * @param item        明细行
     * @param quantity    本次回补量（> 0）
     * @param reason      回滚原因（写进流水备注）
     */
    private void restoreOneItem(RequisitionDO requisition, RequisitionItemDO item,
                                BigDecimal quantity, String reason) {
        Long projectId = requisition.getProjectId();
        Long materialId = item.getMaterialId();
        String reqNo = requisition.getReqNo();

        // ① SELECT ... FOR UPDATE 锁库存行（与扣减同一把锁：回补与新申领在热点行上自然串行）
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialIdForUpdate(projectId, materialId);
        if (stock == null) {
            // 库存行不见了（管理动作删掉）：回补无处可补。抛错让调用方整体回滚，
            // 静默跳过会造成「料已退回但库存没加」且无人知晓。
            log.error("[restoreOneItem][库存行不存在，无法回补 reqNo={} projectId={} materialId={}]",
                    reqNo, projectId, materialId);
            throw exception(STOCK_NOT_EXISTS);
        }

        // ② available_qty += qty（行锁下直接累加）
        int updatedRows = materialStockMapper.increaseAvailableQtyUnderLock(stock.getId(), quantity);
        if (updatedRows != 1) {
            log.error("[restoreOneItem][回补影响 {} 行（期望 1）reqNo={} stockId={} qty={}]",
                    updatedRows, reqNo, stock.getId(), quantity);
            throw exception(STOCK_NOT_EXISTS);
        }
        BigDecimal available = stock.getAvailableQty() == null ? ZERO : stock.getAvailableQty();
        BigDecimal balanceAfter = available.add(quantity);

        // ③ 写冲正流水：biz_type=4、quantity 记**正数**（库存净增）、biz_no=reqNo
        StockLogDO stockLog = StockLogDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .bizType(StockBizTypeEnum.REVERSAL.getBizType())
                .bizNo(reqNo)
                .quantity(quantity)
                .balanceAfter(balanceAfter)
                .remark(REVERSAL_REMARK_PREFIX + reason + "：" + reqNo)
                .build();
        stockLogMapper.insert(stockLog);

        log.debug("[restoreOneItem][回补成功 reqNo={} materialId={} qty={} balanceAfter={} reason={}]",
                reqNo, materialId, quantity, balanceAfter, reason);
    }

}
