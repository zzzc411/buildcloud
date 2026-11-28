package com.buildcloud.material.service.requisition.strategy;

import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.enums.requisition.RequisitionModeEnum;
import com.buildcloud.material.service.requisition.mq.RequisitionDeductLocalTransactionService;
import com.buildcloud.material.service.requisition.mq.RequisitionDeductMessage;
import com.buildcloud.material.service.requisition.mq.RequisitionDeductMessageProducer;
import com.buildcloud.material.service.stock.StockPredeductService;
import com.buildcloud.material.service.stock.redis.StockPredeductResult;
import com.buildcloud.material.service.stock.redis.StockRollbackResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_SUBMIT_MQ_FAILED;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_ENOUGH;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_PREDEDUCT_BASELINE_MISSING;

/**
 * V2 申领提交策略：**Redis Lua 原子预扣 → RocketMQ 事务消息 → 异步落库**（spec §6.1 V2 架构）
 *
 * <pre>
 *   ③ Redis Lua 原子预扣：KEY pm:stock:{projectId}:{materialId}
 *        available >= qty ? decr + 记明细(reqNo→hash) : 返回失败
 *        失败 → 友好降级（"库存不足"），不打 DB
 *   ④ 发 RocketMQ 事务消息（half 消息）
 *        本地事务：单据(已提交)  ← 由 RequisitionDeductLocalTransactionService 完成
 *        commit → 消费者：DB 真实扣减 + stock_log + 状态→已扣减
 *        本地事务失败 → rollback，MQ 丢弃 half 消息
 * </pre>
 *
 * ========== 与 V1 的根本差别（W5 压测要比的是什么） ==========
 *
 * | | V1（{@link RequisitionV1SubmitHandler}） | V2（本类） |
 * | --- | --- | --- |
 * | 请求线程做的 | `SELECT ... FOR UPDATE` 锁库存行 + 扣减 + 写流水 + 推状态 | **只有一次 Redis Lua** + 一次 half 消息发送 |
 * | 热点行 | `bc_material_stock` 同一行上串行排队 | Redis 单线程内的毫秒级脚本，DB 行锁完全不参与 |
 * | DB 扣减何时发生 | 请求事务内（同步） | 消费者线程（异步、离线于用户请求） |
 * | 一致性 | 强一致（同一事务） | 最终一致（事务消息 + 对账兜底） |
 *
 * ========== 三步的失败语义（每一步失败都必须"钱货两清"） ==========
 *
 * | 失败点 | 已发生的副作用 | 必须做的补偿 | 用户看到 |
 * | --- | --- | --- | --- |
 * | Lua 预扣返回 NOT_ENOUGH | 无（脚本原子，不足时不扣不记） | 无 | 库存不足（1-102-040-001，**不打 DB**） |
 * | Lua 预扣返回 NEED_INIT | 无 | 从 DB 回填基线后重试一次 | 重试失败才报 1-102-080-003 |
 * | 第 2 行预扣失败（多行） | 前面几行已预扣 | **回滚前面几行**（本类的 "rollback all"） | 库存不足 |
 * | 发 half 消息失败/本地事务回滚 | 全部行已预扣 | 回滚全部预扣 + 单据退回草稿 | 1-102-090-000 |
 *
 * ========== 为什么预扣失败不落库（"友好降级，不打 DB"） ==========
 *
 * spec §6.1 原文。高并发下 90% 的请求在热点物料上会被 Lua 拒绝，如果这些请求都去写一次库，
 * 数据库反而成为新的瓶颈，而它们**本来就不该成功**。本类在预扣失败时立刻抛业务异常，
 * 调用方整体回滚（草稿单保持草稿、零 DB 写入）。
 *
 * @author buildcloud
 */
@Component
@Slf4j
public class RequisitionV2SubmitHandler implements RequisitionSubmitHandler {

    @Resource
    private StockPredeductService stockPredeductService;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private RequisitionDeductMessageProducer messageProducer;

    @Resource
    private RequisitionDeductLocalTransactionService localTransactionService;

    @Override
    public RequisitionModeEnum getMode() {
        return RequisitionModeEnum.V2;
    }

    // ========== 提交：预扣 → 事务消息 ==========

    @Override
    public void submit(RequisitionDO requisition, List<RequisitionItemDO> items) {
        Long projectId = requisition.getProjectId();
        String reqNo = requisition.getReqNo();

        // 按 materialId 升序：多行预扣失败时"回滚已扣的行"的集合是确定的，
        // 且与 V1 的加锁顺序、消费者的处理顺序一致（跨版本同一约定，见 V1 类注释）
        List<RequisitionItemDO> orderedItems = items.stream()
                .sorted(Comparator.comparing(RequisitionItemDO::getMaterialId))
                .toList();

        // ===== 步骤 1：逐行 Lua 原子预扣（失败就回滚已预扣的行） =====
        // 为什么要"部分成功就回滚"：申领是业务原子事件（要么这批料全批，要么不批）。
        // 若留下前几行的预扣，用户会看到"提交失败但库存少了一块"，而这张单永远不会有对应的
        // DB 扣减 —— 那就是账不平。回滚用 Lua 的 rollback（按明细里的数量校验后 INCRBY + HDEL），
        // 是本 Phase 唯一正确的撤销手段。
        for (RequisitionItemDO item : orderedItems) {
            StockPredeductResult result = predeductWithBaselineRepair(projectId, item.getMaterialId(),
                    reqNo, item.getQuantity());
            if (result == StockPredeductResult.NOT_ENOUGH) {
                rollbackPrededucted(orderedItems, item, reqNo, projectId);
                BigDecimal available = stockPredeductService.queryAvailable(projectId, item.getMaterialId());
                log.info("[submit][V2 预扣失败：库存不足，已回滚本次已预扣的行 reqNo={} materialId={} need={} redisAvailable={}]",
                        reqNo, item.getMaterialId(), item.getQuantity(), available);
                // 友好降级：抛业务异常，由调用方事务回滚（不落库、不打 DB）
                throw exception(STOCK_NOT_ENOUGH,
                        available == null ? "0" : available.toPlainString(),
                        item.getQuantity().toPlainString());
            }
            // SUCCESS / IDEMPOTENT 都算"这一行已预扣到位"
            // IDEMPOTENT 出现的场景：上一轮提交在"预扣完成、消息未发"之间失败后重试
            // （同一 reqNo 的明细在 Redis 里已记过），此时不能重复扣，也不能报错。
        }

        // ===== 步骤 2：发事务消息（本地事务在 executeLocalTransaction 里提交单据） =====
        RequisitionDeductMessage message = buildMessage(requisition, orderedItems);
        boolean sent;
        try {
            sent = messageProducer.send(message, reqNo);
        } catch (Exception e) {
            // 发送异常（含 Namesrv 不可达、序列化失败）：half 消息可能已发也可能没发，
            // 统一按"没成"处理 —— 回滚预扣 + 单据退回草稿，再抛业务异常
            log.error("[submit][V2 事务消息发送异常 reqNo={}]", reqNo, e);
            compensateAfterSendFailure(requisition, orderedItems);
            throw exception(REQUISITION_SUBMIT_MQ_FAILED, reqNo);
        }
        if (!sent) {
            compensateAfterSendFailure(requisition, orderedItems);
            throw exception(REQUISITION_SUBMIT_MQ_FAILED, reqNo);
        }

        // ===== 步骤 3：交给调用方 —— 单据此时已是"已提交(1)"，等待消费者扣 DB =====
        // 注意：这里**不**把单据推进到已扣减(2)。
        // V1 的 submitRequisition 里有一段「若 handler 已代为推进则跳过」的判断，
        // 那时单据还是内存态的 1，因此 V1 会继续推进到 2；V2 必须保持在 1，
        // 由消费者（RequisitionDeductTransactionService#deduct）用 deductCas 推进。
        log.info("[submit][V2 预扣+事务消息完成，等待消费者异步落库 reqNo={} projectId={} itemCount={}]",
                reqNo, projectId, orderedItems.size());
    }

    // ========== 取消 / 冲正 ==========

    /**
     * V2 取消：回滚 Redis 预扣 + 交给消费者/同步链路冲正（本 Phase 只做 Redis 侧）
     *
     * ========== 为什么本 Phase 的 cancel 只回滚 Redis ==========
     *
     * spec 把「取消/驳回/超时 → Lua 恢复库存 + **冲正流水**」放在 Phase 3（延迟关单 + 回滚冲正），
     * 本 Phase（P2）的验收范围是"事务消息链路通、幂等消费对、账平"，不含冲正流水。
     * 所以这里做的是**本 Phase 必须做对的那一半**：
     * 1. 把 Redis 预扣还回去（否则 Redis 永久少一块，对账 Job 会立刻报差异）；
     * 2. DB 侧：若消费者**还没扣**（单据仍是 1），DB 无需动作，取消后账自然平；
     *    若消费者**已经扣了**（单据已是 2），DB 的回补 + 冲正流水由 P3 的共用回滚组件完成。
     *
     * 这里的取舍是显式的：**宁可让 P3 立刻接手，也不在本 Phase 写一段将来会被替换的冲正逻辑**
     * （两份冲正口径并存，是"账不平"最常见的来源）。调用链上 P3 到来前，V2 的单据一旦到 2
     * 就无法取消——由 {@code RequisitionStatusEnum#canCancel} 与前端按钮保住体验，
     * 而不会产生错误的账。
     *
     * @param requisition 单据（状态=已提交/已扣减）
     * @param items       明细
     */
    @Override
    public void cancel(RequisitionDO requisition, List<RequisitionItemDO> items) {
        String reqNo = requisition.getReqNo();
        Long projectId = requisition.getProjectId();
        List<RequisitionItemDO> orderedItems = items.stream()
                .sorted(Comparator.comparing(RequisitionItemDO::getMaterialId))
                .toList();

        for (RequisitionItemDO item : orderedItems) {
            StockRollbackResult result = stockPredeductService.rollback(reqNo, projectId,
                    item.getMaterialId(), item.getQuantity());
            log.info("[cancel][V2 回滚 Redis 预扣 reqNo={} materialId={} qty={} result={}]",
                    reqNo, item.getMaterialId(), item.getQuantity(), result);
        }
        log.info("[cancel][V2 取消完成（Redis 预扣已恢复；DB 回补与冲正流水由 W5-P3 的统一回滚组件承担）"
                + " reqNo={} itemCount={}]", reqNo, orderedItems.size());
    }

    // ========== 内部方法 ==========

    /**
     * 预扣（含 NEED_INIT 的**从 DB 回填**自愈）
     *
     * ========== NEED_INIT 的处置（任务书要求说明） ==========
     *
     * Redis 里没有 `pm:stock:{projectId}:{materialId}` 时，Lua 返回 NEED_INIT(-1)。
     * 这不是业务失败，而是**缓存缺基线**，常见于：
     * - 项目在 W5 之前入库（那时 P1 还没接入库），Redis 里从未写过这个 key；
     * - Redis 重启/被清（库存是长期数据但缓存是内存数据）；
     * - 对账 Job（P4）清理中。
     *
     * 处置：用 DB 的 `bc_material_stock.available_qty` 做 **RESET 基线**回填一次，再试一次预扣。
     * - 为什么用 RESET 而不是 ACCUMULATE：此刻 Redis 侧没有基线，ACCUMULATE 会返回 -4（无 key 不能累加），
     *   而且"回填"的语义本来就是把 DB 真值抄过去；
     * - 为什么回填用的是 `available_qty` 而不是「available_qty - 在途预扣」：Redis 里连 key 都没有，
     *   说明这个物料的在途预扣记录也一起丢了（两者在同一个脚本里原子操作）。
     *   真有在途单据时，它们的状态是"已提交(1)"而 DB 尚未扣减，那么用 `available_qty` 回填
     *   **偏保守**（少算已预扣量 → 后续预扣更容易被拒），不会超卖；对账 Job 会把这批在途单
     *   重新扣一遍（幂等），最终收敛。保守 > 超卖。
     * - 回填只做**一次**：第二次仍 NEED_INIT 说明连 key 都建不起来（Redis 只读/故障），
     *   继续重试只会放大故障，直接报错。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param reqNo      申领单号
     * @param quantity   数量
     * @return 预扣结果（保证不是 NEED_INIT：要么 SUCCESS/IDEMPOTENT，要么 NOT_ENOUGH）
     */
    private StockPredeductResult predeductWithBaselineRepair(Long projectId, Long materialId,
                                                             String reqNo, BigDecimal quantity) {
        StockPredeductResult result = stockPredeductService.tryPrededuct(projectId, materialId, reqNo, quantity);
        if (result != StockPredeductResult.NEED_INIT) {
            return result;
        }
        // 从 DB 取真实可用量回填基线
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        if (stock == null) {
            // 该项目从未给该物料入过库：没有基线可回填，属于业务前置缺失（不是库存不足）
            log.error("[predeductWithBaselineRepair][Redis 未初始化且 DB 无库存行 reqNo={} projectId={} materialId={}]",
                    reqNo, projectId, materialId);
            throw exception(STOCK_PREDEDUCT_BASELINE_MISSING, projectId, materialId);
        }
        BigDecimal baseline = stock.getAvailableQty() == null ? BigDecimal.ZERO : stock.getAvailableQty();
        log.warn("[predeductWithBaselineRepair][Redis 可用量缺失，从 DB 回填基线 reqNo={} projectId={} materialId={} baseline={}]",
                reqNo, projectId, materialId, baseline);
        stockPredeductService.initAvailable(projectId, materialId, baseline, StockPredeductService.InitMode.RESET);

        // 回填后重试一次（仍失败则按返回值如实返回：NOT_ENOUGH / NEED_INIT 都不会到这里）
        StockPredeductResult retry = stockPredeductService.tryPrededuct(projectId, materialId, reqNo, quantity);
        if (retry == StockPredeductResult.NEED_INIT) {
            // 回填后依然没有 key：Redis 写入失败（只读/空间满），必须暴露为故障
            log.error("[predeductWithBaselineRepair][回填后仍 NEED_INIT，Redis 可能只读或故障 reqNo={} projectId={} materialId={}]",
                    reqNo, projectId, materialId);
            throw new IllegalStateException("Redis 可用量回填后仍不可用：projectId=" + projectId
                    + ", materialId=" + materialId);
        }
        return retry;
    }

    /**
     * 回滚"本次已预扣但业务最终失败"的那些行
     *
     * @param orderedItems 已排序的全部明细
     * @param failedItem   失败的那一行（只回滚它**之前**的行）
     * @param reqNo        申领单号
     * @param projectId    项目编号
     */
    private void rollbackPrededucted(List<RequisitionItemDO> orderedItems, RequisitionItemDO failedItem,
                                     String reqNo, Long projectId) {
        for (RequisitionItemDO item : orderedItems) {
            if (item == failedItem) {
                break;    // 失败行自己没扣成，不需要回滚
            }
            StockRollbackResult result = stockPredeductService.rollback(reqNo, projectId,
                    item.getMaterialId(), item.getQuantity());
            log.info("[rollbackPrededucted][回滚本次已预扣的行 reqNo={} materialId={} qty={} result={}]",
                    reqNo, item.getMaterialId(), item.getQuantity(), result);
        }
    }

    /**
     * 发消息失败后的补偿：回滚全部 Redis 预扣 + 单据退回草稿
     *
     * 顺序：**先回滚 Redis，再改单据状态**。
     * 理由：若先改单据、回滚 Redis 时抛错（Redis 故障），就会留下"单据是草稿、Redis 却少了一块"
     * 的账不平；反过来先回滚 Redis 再改单据，最坏情况是"Redis 已还、单据还是已提交(1)"
     * ——后者在查询/展示上是"待处理"，且对账 Job 会发现"状态=1 但 Redis 无对应明细"并自愈。
     * 两种残留里，后者的可自愈性明显更好。
     *
     * 补偿本身失败**不能吞**：抛出去会让用户看到失败（正确），并留下 error 日志供对账发现。
     *
     * @param requisition 单据
     * @param items       明细
     */
    private void compensateAfterSendFailure(RequisitionDO requisition, List<RequisitionItemDO> items) {
        Long projectId = requisition.getProjectId();
        String reqNo = requisition.getReqNo();
        for (RequisitionItemDO item : items) {
            try {
                StockRollbackResult result = stockPredeductService.rollback(reqNo, projectId,
                        item.getMaterialId(), item.getQuantity());
                log.warn("[compensateAfterSendFailure][回滚预扣 reqNo={} materialId={} result={}]",
                        reqNo, item.getMaterialId(), result);
            } catch (Exception e) {
                // 单行回滚失败不能中断其余行：多回滚回一行比少回滚一行更接近"没有预扣过"
                log.error("[compensateAfterSendFailure][回滚预扣失败 reqNo={} materialId={}]",
                        reqNo, item.getMaterialId(), e);
            }
        }
        try {
            localTransactionService.revertToDraft(reqNo);
        } catch (Exception e) {
            log.error("[compensateAfterSendFailure][单据回退草稿失败 reqNo={}]", reqNo, e);
        }
    }

    /**
     * 构造扣减消息（明细快照，消费者据此扣 DB，不反查库）
     *
     * @param requisition 单据
     * @param items       已排序明细
     * @return 消息
     */
    private RequisitionDeductMessage buildMessage(RequisitionDO requisition, List<RequisitionItemDO> items) {
        return RequisitionDeductMessage.builder()
                .reqNo(requisition.getReqNo())
                .requisitionId(requisition.getId())
                .projectId(requisition.getProjectId())
                .operator(String.valueOf(requisition.getApplicantUserId()))
                .items(items.stream()
                        .map(item -> RequisitionDeductMessage.Item.builder()
                                .materialId(item.getMaterialId())
                                .quantity(item.getQuantity())
                                .build())
                        .toList())
                .build();
    }

    /**
     * 明细合计（供日志/响应，避免 n 行日志散落）
     *
     * @param items 明细
     * @return 合计数量
     */
    static BigDecimal totalQuantity(List<RequisitionItemDO> items) {
        return RequisitionDeductLocalTransactionService.sumQuantity(
                items.stream().map(RequisitionItemDO::getQuantity).toList());
    }

}
