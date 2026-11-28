package com.buildcloud.material.service.requisition.mq;

import cn.hutool.core.util.StrUtil;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.enums.requisition.RequisitionApprovalStatusEnum;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import com.buildcloud.material.service.stock.StockRollbackService;
import com.buildcloud.material.service.stock.redis.RequisitionRollbackResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 申领单**超时关单的事务承载类**（spec §5.2 状态机 + §6.1 步骤⑥）
 *
 * <pre>
 *   延迟消息到期 → 核对单据状态与超时时间
 *     status = 已提交(1) 且 timeout_time &lt;= now()  → 关闭：已提交(1)→已关闭(8) + 回滚（统一组件）
 *     status = 已扣减(2)/已出库(3)/已完成(4)/部分回退(5) → **已处理过**，幂等忽略
 *     status = 已取消(9)/已关闭(8)                       → **已终态**，幂等忽略
 *     status = 草稿(0)                                  → 从未提交，幂等忽略
 *     已提交(1) 但 timeout_time &gt; now()                 → 消息早到，**不关单**（等待再次投递/兜底扫描）
 * </pre>
 *
 * ========== 为什么"只关审批中的单"要写成状态白名单 ==========
 *
 * 任务书要求「只关审批中的单，不能误关已通过的单」。这里的实现是**白名单**而不是黑名单：
 * 只有 `status == 已提交(1)` 才可能被关闭。为什么这一点必须强调：
 * - 已扣减(2)：审批已通过、库存已固化。若被关单，`status=8` 会让这张料"既扣了库存、单又作废"，
 *   而且它之后再也走不到出库（状态机 8 是终态）——是比"账不平"更严重的业务事故；
 * - 已出库(3)/已完成(4)/部分回退(5)：料已出门，关单毫无意义；
 * - 草稿(0)：从没提交过，没有预扣也没有扣减，关它只是凭空造状态。
 *
 * 另外还有第二道闸：**超时时间核对**。延迟消息不保证准点（Broker 秒级扫描 + 堆积会晚到，
 * 手工补发会早到）。若只按"收到消息"关单，就会出现「消息早到、审批晚 1 秒通过」
 * 的误关——`timeout_time <= now()` 是唯一可靠的判据（这也是提交时写 timeout_time 的原因）。
 *
 * ========== 三步的顺序：先 CAS 拿"处理权"，再回补，再（同一事务内）落终态 ==========
 *
 * <pre>
 *   ① CAS: status 1 → 8（WHERE status = 1 AND approval_status = 1）
 *        影响 0 行 → 别的路径已处理（并发关单 / 审批刚好通过 / 人工取消）→ 幂等返回
 *   ② 回滚（统一组件）：回补 DB + 写 biz_type=4 冲正流水 + 还原 Redis 预扣
 *   ③ 回滚抛错 → 整个事务回滚，CAS 一起撤销（单据回到已提交(1)）→ 抛异常让 MQ 重投
 * </pre>
 *
 * 为什么 CAS 必须在回补之前（与驳回路径同一条理由，W4-P5 已经踩过）：
 * 若先回补再 CAS，两个并发触发（比如消息重投 + 人工关单）都会各回补一次，
 * 而失败的 CAS 通常被当成"幂等返回"而非抛错，事务照常提交 → 库存翻倍。
 *
 * ========== 为什么不在这里通知 bpm 终止流程实例 ==========
 *
 * 关单后 bpm 里那个流程实例还活着，审批人仍能在待办里点"通过"。
 * 本项目**不做自动终止**：跨服务终止流程需要 bpm 侧新增接口/权限（改动基座，R-2），
 * 而业务侧已经用两道闸挡住了后果——审批回调进来时状态已是已关闭(8)，
 * `finishApprovalCas` 的 `WHERE approval_status = 1` 影响 0 行 → 幂等返回，**不会二次扣减也不会二次回补**。
 * 代价只是"bpm 里多了一条已过期的待办"，由 P4 的对账 Job / 运维清理，这条取舍写在这里避免下一个人重复研究。
 *
 * ========== 事务内不产生「外部副作用」（W5-P3 复核时补的约束） ==========
 *
 * {@link #closeIfTimedOut} 是 `@Transactional` 方法：它里面的**每一个**外部动作都必须能被事务回滚
 * 或者本身幂等，否则"DB 回滚了、外部已经动了"会留下无法自愈的中断态。本方法里：
 * <ul>
 *   <li>关单 CAS、冲正流水 → 本地事务，可回滚；</li>
 *   <li>Redis 预扣还原 → **不可回滚**，但 P1 的 Lua 是「明细存在才还原」的幂等脚本，
 *       重投时明细已空 → NOT_FOUND，无副作用（见 {@code StockRollbackServiceImpl} 的第 0 遍注释）；</li>
 *   <li>行锁回补 → 本地事务，可回滚。</li>
 * </ul>
 * 因此重投是安全的。**不要**在这里补"发通知 / 调 bpm 终止流程 / 发新消息"这类动作——
 * 它们不能随事务回滚，会制造出"消息发出去了但单据没关"的假状态。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class RequisitionTimeoutCloseService {

    /**
     * 回滚原因（写进冲正流水备注，与驳回路径的「审批驳回」可区分）
     */
    static final String ROLLBACK_REASON = "超时关单";

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private StockRollbackService stockRollbackService;

    /**
     * 处理一条超时关单消息（**一个本地事务**：关单 CAS + 回补 + 冲正流水 同成同败）
     *
     * @param message 超时关单消息
     * @return 处理结果（供消费者写日志/单测断言）
     */
    @Transactional(rollbackFor = Exception.class)
    public TimeoutCloseOutcome closeIfTimedOut(RequisitionTimeoutMessage message) {
        String reqNo = message.getReqNo();
        RequisitionDO requisition = resolveRequisition(message);
        if (requisition == null) {
            // 单据被物理删除 / 租户上下文不对：重投一万次也不会变好 → 不抛异常（ACK）并告警
            log.error("[closeIfTimedOut][申领单不存在，丢弃消息 reqNo={} requisitionId={}]",
                    reqNo, message.getRequisitionId());
            return TimeoutCloseOutcome.REQUISITION_NOT_FOUND;
        }

        Integer status = requisition.getStatus();
        // ===== 白名单：只有"已提交(1)"才可能被关 =====
        if (!RequisitionStatusEnum.SUBMITTED.getStatus().equals(status)) {
            if (isAlreadyProcessed(status)) {
                log.info("[closeIfTimedOut][幂等：单据已处理过（status={} {}），忽略超时消息 reqNo={}]",
                        status, statusName(status), reqNo);
                return TimeoutCloseOutcome.IGNORED_ALREADY_PROCESSED;
            }
            log.info("[closeIfTimedOut][幂等：单据已处于终态/不可关（status={} {}），忽略超时消息 reqNo={}]",
                    status, statusName(status), reqNo);
            return TimeoutCloseOutcome.IGNORED_END_STATUS;
        }

        // ===== 第二道闸：到点才关（延迟消息可能早到，见类注释） =====
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime timeoutTime = requisition.getTimeoutTime();
        if (timeoutTime == null) {
            // 提交时必然写 timeout_time（startApprovalCas 的 SQL 里是 NOT NULL 参数）。
            // 为 null 说明数据被外部改过：按"不能证明到期"处理，**不关单**，等兜底扫描/人工。
            log.error("[closeIfTimedOut][超时时间为空，不关单（需人工核对）reqNo={} requisitionId={}]",
                    reqNo, requisition.getId());
            return TimeoutCloseOutcome.IGNORED_NOT_TIMEOUT_YET;
        }
        if (timeoutTime.isAfter(now)) {
            log.info("[closeIfTimedOut][消息早到：timeout_time={} 晚于当前 {}，不关单 reqNo={}]",
                    timeoutTime, now, reqNo);
            return TimeoutCloseOutcome.IGNORED_NOT_TIMEOUT_YET;
        }

        // ===== ① CAS 拿"处理权"：已提交(1) + 审批中(1) → 已关闭(8) + 审批状态置"已取消(4)" =====
        // approval_status 落 4（已取消）而不是保留 1：单据已关闭，审批流不再有业务意义；
        // 同时它让**迟到的审批回调**在 `finishApprovalCas (WHERE approval_status = 1)` 上影响 0 行，
        // 从而幂等返回，不会二次扣减/二次回补（见类注释末节）。
        int casRows = requisitionMapper.closeTimeoutCas(requisition.getId(),
                RequisitionApprovalStatusEnum.CANCEL.getStatus());
        if (casRows != 1) {
            // 影响 0 行 = 单据在"读到 1"与"CAS"之间被改了（审批刚好通过 / 人工取消 / 另一条消息抢先）
            log.info("[closeIfTimedOut][关单 CAS 未命中（已被并发处理），幂等返回 reqNo={} 读到的状态={}]",
                    reqNo, status);
            return TimeoutCloseOutcome.IGNORED_CAS_MISSED;
        }

        // ===== ② 回滚（唯一实现：与审批驳回共用同一个组件与同一个方法） =====
        RequisitionRollbackResult rollbackResult =
                stockRollbackService.rollbackForRequisition(requisition, null, ROLLBACK_REASON);
        if (!rollbackResult.isAcquired()) {
            // 回补不彻底（仍有物料被本单占用）：不能当作成功。抛错整体回滚（含上面的 CAS），
            // 让 MQ 重投；重投仍失败进死信，由对账 Job（P4）兜底。**绝不静默 ACK**。
            throw new IllegalStateException(String.format(
                    "超时关单回滚不彻底：reqNo=%s requisitionId=%s result=%s", reqNo, requisition.getId(),
                    rollbackResult));
        }

        log.info("[closeIfTimedOut][超时关单完成 reqNo={} requisitionId={} 已提交(1)→已关闭(8) "
                        + "timeoutTime={} now={} 回滚结果={}]",
                reqNo, requisition.getId(), timeoutTime, now, rollbackResult);
        return rollbackResult.getRestoredItemCount() > 0
                ? TimeoutCloseOutcome.CLOSED_WITH_ROLLBACK
                : TimeoutCloseOutcome.CLOSED_WITHOUT_DB_ROLLBACK;
    }

    // ========== 内部方法 ==========

    /**
     * 定位单据：优先用 id（索引最快，且不会撞同号的脏数据），没带 id 时用单号兜底
     *
     * 为什么要兜底：手工补发/脚本重放时可能只写了 reqNo（人比机器更可能漏字段），
     * 一条能定位的单据不该被丢成"畸形消息"。
     *
     * @param message 消息
     * @return 单据；不存在返回 null
     */
    private RequisitionDO resolveRequisition(RequisitionTimeoutMessage message) {
        if (message.getRequisitionId() != null) {
            RequisitionDO byId = requisitionMapper.selectById(message.getRequisitionId());
            if (byId != null) {
                return byId;
            }
        }
        if (StrUtil.isNotBlank(message.getReqNo())) {
            return requisitionMapper.selectByReqNo(message.getReqNo());
        }
        return null;
    }

    /**
     * 是否"已处理过"（已扣减及之后）
     *
     * 与 {@code RequisitionDeductTransactionService#isAlreadyDeducted} 同一口径（跨类同一判据）：
     * 这几个状态都意味着**库存扣减已经发生（或已经出门）**，超时消息对它们没有任何要做的事。
     *
     * @param status 状态
     * @return 是否已处理
     */
    private boolean isAlreadyProcessed(Integer status) {
        if (status == null) {
            return false;
        }
        return RequisitionStatusEnum.DEDUCTED.getStatus().equals(status)
                || RequisitionStatusEnum.OUTBOUND.getStatus().equals(status)
                || RequisitionStatusEnum.COMPLETED.getStatus().equals(status)
                || RequisitionStatusEnum.PARTIAL_RETURNED.getStatus().equals(status);
    }

    /**
     * 状态值 → 可读名（日志用）
     *
     * @param status 状态值
     * @return 状态名
     */
    private String statusName(Integer status) {
        RequisitionStatusEnum statusEnum = RequisitionStatusEnum.valueOf(status);
        return statusEnum != null ? statusEnum.getName() : String.valueOf(status);
    }

    /**
     * 关单处理结果（消费者的日志与单测断言都基于它，因此用枚举而不是布尔）
     */
    public enum TimeoutCloseOutcome {

        /**
         * 已关闭，并且确实回补了 DB（V1 链路 / V2 消费者已扣后的单据）
         */
        CLOSED_WITH_ROLLBACK,

        /**
         * 已关闭，但 DB 本就没有扣减（V2 在途预扣单据）：只还原了 Redis 预扣
         */
        CLOSED_WITHOUT_DB_ROLLBACK,

        /**
         * 忽略：已扣减/已出库/已完成/部分回退（审批已通过或已处理完）
         */
        IGNORED_ALREADY_PROCESSED,

        /**
         * 忽略：已取消/已关闭/草稿（终态或从未提交）
         */
        IGNORED_END_STATUS,

        /**
         * 忽略：消息早到，`timeout_time` 还没到
         */
        IGNORED_NOT_TIMEOUT_YET,

        /**
         * 忽略：关单 CAS 未命中（并发已被别的路径处理）
         */
        IGNORED_CAS_MISSED,

        /**
         * 忽略：单据不存在（物理删除 / 租户上下文不符）
         */
        REQUISITION_NOT_FOUND

    }

}
