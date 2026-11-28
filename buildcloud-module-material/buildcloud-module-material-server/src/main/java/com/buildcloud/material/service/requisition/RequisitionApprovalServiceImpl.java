package com.buildcloud.material.service.requisition;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionApprovalStatusRespVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionApprovalSubmitReqVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionReturnReqVO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionItemMapper;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.requisition.RequisitionApprovalStatusEnum;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import com.buildcloud.material.service.requisition.mq.RequisitionTimeoutMessageProducer;
import com.buildcloud.material.service.requisition.strategy.RequisitionV1SubmitHandler;
import com.buildcloud.material.service.stock.StockRollbackService;
import com.buildcloud.material.service.stock.redis.RequisitionRollbackResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVERS_EXCEED;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVAL_ALREADY_RUNNING;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVAL_START_FAILED;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVAL_STATUS_NOT_ALLOWED;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVERS_EMPTY;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_CANNOT_OUTBOUND;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_CANNOT_RETURN;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_ITEM_EMPTY;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_ITEM_DUPLICATE;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_ITEM_EMPTY;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_ITEM_NOT_IN_REQUISITION;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_QUANTITY_EXCEEDED;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_QUANTITY_INVALID;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_STATUS_INVALID;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_EXISTS;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.PROCESS_KEY_REQUISITION_APPROVE;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.VARIABLE_APPROVERS;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.VARIABLE_INITIATOR;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.VARIABLE_ITEMS;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.VARIABLE_REQ_NO;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.VARIABLE_REQUISITION_ID;

/**
 * 材料申领审批 / 出库 / 退料 Service 实现类（W4-P5）
 *
 * 接口注释里的「审批与 V1 扣减的衔接」表是本文档的总纲，这里只补实现层面的取舍。
 *
 * ========== 一、四类写动作的加锁顺序（防死锁的唯一约定，与 P4 完全一致） ==========
 *
 * 所有路径统一为：**先锁单据行（CAS UPDATE）→ 再按 materialId 升序锁库存行**。
 * <pre>
 * submitApproval : startApprovalCas（锁单据 0→1） → 升序锁库存扣减 → finish 回写实例号
 * 驳回/取消      : finishApprovalCas（锁单据 approval=1→终态）→ 升序锁库存回补
 * 出库           : outboundCas（单语句，无库存动作）
 * 退料           : 逐行原子累加 returned_qty → 升序（先排序再循环）锁库存回补 → returnCas
 * </pre>
 * 只要所有事务都以同一个顺序申请「单据行 → 库存行」，就不会出现 A 持单据等库存、B 持库存等单据。
 *
 * ========== 二、审批回调幂等的实现（**主闸门是数据库 CAS，不是内存判断**） ==========
 *
 * 回调可能是重复的（bpm 重投 / 人工补投 / 单体部署时进程内事件与 HTTP 回调同时命中）。
 * 三道闸：
 * 1. 串号：{@code processInstanceId} 与库里的不一致 → 旧流程的迟到通知，丢弃；
 * 2. 终态短路：已是终态且结果相同 → 直接返回（省一次无谓的写）；
 * 3. **CAS**：{@code finishApprovalCas} 的 `WHERE approval_status = 1` 影响行数 0 → 幂等返回。
 * 第 3 道才是真正可靠的：前两道都是「先读后判」，两个并发回调会同时读到「还在审批中」；
 * 只有把条件写进 UPDATE 的 WHERE，才能保证「回补库存 + 写冲正流水」**只发生一次**。
 * 因为库存回补是**不可察觉的账务错误**（料凭空多出来），单测专门有两条用例钉住它：
 * {@code testHandleApprovalResult_reject_twice_noDoubleRestore} 与
 * {@code testHandleApprovalResult_approve_after_reject_noDeduct}。
 *
 * ========== 三、审批通过为什么不碰库存（本类最贵的一行注释） ==========
 *
 * V1 的口径是「提交即扣减」，{@link #submitApproval} 已经通过
 * {@link RequisitionV1SubmitHandler#submit} 扣过库存并写过 biz_type=2 流水。
 * 审批通过只是「给这张已扣减的单据放行」，把状态从已提交(1) 推到已扣减(2) 即可。
 * 若在这里再扣一次，就是**同一张单扣了两遍**，且驳回时只补一遍——账永远对不平。
 * {@code testHandleApprovalResult_approve_stockUnchanged} 断言「库存与流水条数完全不变」。
 *
 * ========== 四、退料的数学（为什么用「原子条件更新 returned_qty」而不是「查流水聚合」） ==========
 *
 * 可退量 = {@code quantity - returned_qty}（明细行上的本地事实），而不是
 * `SUM(bc_stock_log WHERE biz_type=3 AND biz_no=req_no)`——
 * bc_stock_log 是 W6 的分库分表主表（按 project_id 哈希），跨片聚合会变成跨片查询；
 * 而明细行的 returned_qty 还能承一条 `WHERE returned_qty + ? <= quantity` 的原子条件更新，
 * 让「并发重复退料」的第二个请求影响行数 0（而不是把库存退两次）。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class RequisitionApprovalServiceImpl implements RequisitionApprovalService {

    /**
     * 审批提交时的超时关闭时间偏移：30 分钟
     *
     * 与 P4 {@code RequisitionServiceImpl#TIMEOUT_MINUTES} 同口径（spec §6.1 第 ⑥ 步「延迟消息 30 分钟」）。
     * W5 的超时关单扫描到这个字段：`status=1 && timeout_time <= now()` → 关闭(8) + 回补库存 + 冲正流水。
     */
    static final int TIMEOUT_MINUTES = 30;

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /**
     * 逗号分隔符（approvers 列的存储格式，见 sql/buildcloud/08-requisition-approval.sql 文件头）
     */
    private static final String APPROVERS_SEPARATOR = ",";

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private RequisitionItemMapper requisitionItemMapper;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    /**
     * V1 行锁扣减实现（**复用而非重写**：审批提交与 V1 的 submit 必须是同一段扣减代码）
     *
     * 为什么不注入策略工厂 {@code RequisitionSubmitHandlerFactory}：
     * 审批链路固定用 V1 语义（提交即扣减）。若走工厂，W5 把配置切成 V2 时，
     * 审批链路的扣减会突然变成「Redis 预扣 + 异步落库」，而审批回调却仍按「已扣减」推进状态——
     * 两条链路的时序假设被悄悄打破。V2 的审批衔接由 W5 一并设计（届时引入新的 handler 或参数化），
     * 当前显式绑定 V1 实现，让「谁的语义被用在这里」在代码上一目了然。
     */
    @Resource
    private RequisitionV1SubmitHandler v1SubmitHandler;

    /**
     * **统一回滚组件**（W5-P3）：驳回 / 超时关单共用的回补实现
     *
     * 本类的驳回分支只调用它，不再自己拼「读明细 → 锁行 → 回补 → 写冲正流水」。
     * 组件内部复用 V1 的行锁回补（V1 代码零改动），所以"回补 + 冲正"从此只有组件这一个入口，
     * 驳回与超时两条路径的等价性由 `RequisitionApprovalServiceImplTest` 与
     * `RequisitionTimeoutCloseServiceTest` 各自 `verify` 到**同一个 bean 的同一个方法**钉住。
     */
    @Resource
    private StockRollbackService stockRollbackService;

    /**
     * 超时关单延迟消息发送器（W5-P3）
     *
     * 注入接口而非 {@code RocketMQTemplate}：单测要把"发成功/发失败"变成可控输入，
     * 而 {@code RocketMQTemplate} 需要完整的 Broker 上下文。
     */
    @Resource
    private RequisitionTimeoutMessageProducer timeoutMessageProducer;

    /**
     * 基座 BPM 流程实例 RPC（bpm-server，@FeignClient(name="bpm-server")）
     */
    @Resource
    private BpmProcessInstanceApi processInstanceApi;

    // ========== 提交审批 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submitApproval(Long requisitionId, List<Long> approverUserIds) {
        // 1.1 校验单据存在
        RequisitionDO requisition = validateRequisitionExists(requisitionId);
        // 1.2 校验没有审批中的流程（先给最具体的错误码：ALREADY_RUNNING 带出在途实例号，排障更快）
        if (ObjectUtil.equal(requisition.getApprovalStatus(), RequisitionApprovalStatusEnum.RUNNING.getStatus())) {
            throw exception(REQUISITION_APPROVAL_ALREADY_RUNNING,
                    StrUtil.blankToDefault(requisition.getProcessInstanceId(), "-"));
        }
        // 1.3 只有草稿可提交审批
        //     已扣减(2)/已出库(3) 再提交审批会造成「同一张单第二次扣库存」，是账务事故级错误；
        //     已取消(9)/已完成(4) 是终态，要重办就重建单（见 isEndStatus 的语义）。
        if (!ObjectUtil.equal(requisition.getStatus(), RequisitionStatusEnum.DRAFT.getStatus())) {
            throw exception(REQUISITION_APPROVAL_STATUS_NOT_ALLOWED, statusName(requisition.getStatus()));
        }
        // 1.4 校验会签审批人名单
        List<Long> approvers = validateApprovers(approverUserIds);
        // 1.5 明细必须非空（建单已校验，但以库为准：明细被删空的单据扣不了任何东西）
        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(requisitionId);
        if (CollUtil.isEmpty(items)) {
            throw exception(REQUISITION_ITEM_EMPTY);
        }

        // 2. CAS 推进 草稿(0) → 已提交(1)：同时落「审批中」+ 会签人 + 超时时间
        //    timeoutTime 只算**一次**并复用给内存态与 SQL（L3-10：两处各调 now() 会让响应体与库值不一致）
        LocalDateTime timeoutTime = LocalDateTime.now().plusMinutes(TIMEOUT_MINUTES);
        String approversText = CollUtil.join(approvers, APPROVERS_SEPARATOR);
        int casRows = requisitionMapper.startApprovalCas(requisitionId, approversText, timeoutTime);
        if (casRows != 1) {
            // 走到这里说明并发：另一个请求刚把这张单从草稿推走（或单据被删）。
            // 抛错整体回滚——**绝不允许**两个请求都往下走去扣库存。
            log.warn("[submitApproval][单据状态已被并发修改 id={} 期望 status=草稿]", requisitionId);
            throw exception(REQUISITION_APPROVAL_STATUS_NOT_ALLOWED, statusName(RequisitionStatusEnum.DRAFT.getStatus()));
        }
        // 内存态同步回填（返回值与库值一致，L3-10）
        requisition.setStatus(RequisitionStatusEnum.SUBMITTED.getStatus());
        requisition.setApprovalStatus(RequisitionApprovalStatusEnum.RUNNING.getStatus());
        requisition.setApprovers(approversText);
        requisition.setTimeoutTime(timeoutTime);

        // 3. **扣减库存**：复用 V1 的行锁逻辑（SELECT ... FOR UPDATE → 校验 → 扣减 → biz_type=2 流水）
        //    注意这里是「已提交(1) 态下扣减」，与 V1 submit 的「事务内瞬时 1 态」是同一个 handler、
        //    同一段代码——两处各写一份扣减逻辑必然分叉（一处忘了写流水、一处忘了校验），那是账不平的根源。
        //    库存不足时 handler 抛 STOCK_NOT_ENOUGH，本事务整体回滚：单据回到草稿、实例号不写。
        v1SubmitHandler.submit(requisition, items);

        // 4. 发起会签流程实例（读码笔记 §2.1：四个入参 = key + variables + businessKey + 自选审批人）
        //    审批人走**流程变量 approvers**（BPMN 里 candidateStrategy=60 表达式取人），
        //    因此第四个参数 startUserSelectAssignees 传 null：它对应的是「发起人自选」的
        //    `<taskKey>_assignees` 机制，两者不要混用（混用会让实例数被覆盖成自选名单，见 BPMN 文件头）。
        Long userId = resolveInitiatorUserId(requisition);
        String processInstanceId = processInstanceApi.createProcessInstance(userId,
                        new BpmProcessInstanceCreateReqDTO()
                                .setProcessDefinitionKey(PROCESS_KEY_REQUISITION_APPROVE)
                                .setBusinessKey(String.valueOf(requisitionId))
                                .setVariables(buildProcessVariables(requisition, items, approvers, userId)))
                .getCheckedData();
        if (StrUtil.isBlank(processInstanceId)) {
            // bpm 侧没真正建实例 → 必须抛错回滚。否则会留下「审批中(1) 但库存已扣、且没有流程」的死状态：
            // 永远等不到回调，人工也撤销不了（撤销要求 status=1 的 CAS，能做但没人知道要做）。
            throw exception(REQUISITION_APPROVAL_START_FAILED);
        }

        // 5. 回写流程实例编号（approval_status / approvers 已在第 2 步写入）
        requisitionMapper.updateProcessInstanceIdIfAbsent(requisitionId, processInstanceId);
        requisition.setProcessInstanceId(processInstanceId);

        // 6. **发超时关单延迟消息**（W5-P3，spec §6.1 步骤⑥：30 分钟后到期，未审完则关闭 + 回滚）
        //    为什么放在"实例号已回写"之后：消息是"提醒未来去关单"，只有在单据真的处于
        //    「已提交(1) + 审批中(1) + 有实例号」的状态下它才有意义（消费者还会再核对一次状态与超时时间）。
        //    为什么失败**不抛**：提交本身已经成功（预扣 + 状态 + 流程实例都落了），
        //    因一条提醒失败而回滚整笔业务是本末倒置；漏发的后果是"这张单不会自动关"（保守方向，
        //    单据停在待审批，由人处理），且 timeout_time 列本身就是兜底扫描的依据（见消费者类注释）。
        //    注意：**必须在事务内发送**——若事务提交失败，消息会先于数据存在，消费者按 id 查不到单据只会
        //    打 error 后丢弃（幂等容错，不会误关）。这一取舍已在 docs/plans/plan-w5 的进度日志里记录。
        boolean timeoutMessageSent = sendTimeoutCloseMessage(requisition);

        log.info("[submitApproval][申领单({}) 发起会签审批成功，processInstanceId={}, approvers={}, itemCount={}, "
                        + "userId={}, timeoutTime={}, timeoutMessageSent={}]",
                requisitionId, processInstanceId, approversText, items.size(), userId,
                timeoutTime, timeoutMessageSent);
        return processInstanceId;
    }

    /**
     * 发送超时关单延迟消息（失败只记日志，不影响提交结果）
     *
     * @param requisition 申领单（须已有 id / reqNo / projectId / timeoutTime）
     * @return 是否发送成功（供日志与单测断言）
     */
    private boolean sendTimeoutCloseMessage(RequisitionDO requisition) {
        try {
            return timeoutMessageProducer.send(RequisitionTimeoutMessageProducer.buildMessage(
                    requisition.getId(), requisition.getReqNo(), requisition.getProjectId(),
                    requisition.getTimeoutTime()));
        } catch (Exception e) {
            // 生产者本身已把异常收敛成 false；这里再兜一层，保证"提醒失败"永远不会掀翻提交事务
            log.error("[sendTimeoutCloseMessage][超时关单消息发送异常，单据仍需人工审批 reqNo={}]",
                    requisition.getReqNo(), e);
            return false;
        }
    }

    // ========== 查询审批状态 ==========

    @Override
    public RequisitionApprovalStatusRespVO getApprovalStatus(Long requisitionId) {
        RequisitionDO requisition = validateRequisitionExists(requisitionId);
        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(requisitionId);

        RequisitionApprovalStatusRespVO respVO = new RequisitionApprovalStatusRespVO();
        respVO.setId(requisition.getId());
        respVO.setReqNo(requisition.getReqNo());
        respVO.setProjectId(requisition.getProjectId());
        respVO.setProcessInstanceId(requisition.getProcessInstanceId());
        // approval_status 列 NOT NULL DEFAULT -1，但历史数据/手工导入可能为 null，兜底成「未开始」
        Integer approvalStatus = requisition.getApprovalStatus() != null
                ? requisition.getApprovalStatus() : RequisitionApprovalStatusEnum.NOT_START.getStatus();
        respVO.setApprovalStatus(approvalStatus);
        RequisitionApprovalStatusEnum approvalEnum = RequisitionApprovalStatusEnum.valueOf(approvalStatus);
        respVO.setApprovalStatusName(approvalEnum != null ? approvalEnum.getName() : "未知");
        respVO.setApproverUserIds(parseApprovers(requisition.getApprovers()));
        respVO.setStatus(requisition.getStatus());
        respVO.setStatusName(statusName(requisition.getStatus()));
        respVO.setActualOutTime(requisition.getActualOutTime());
        respVO.setActualReturnTime(requisition.getActualReturnTime());
        respVO.setReason(buildReason(approvalStatus, requisition.getStatus()));
        respVO.setItems(items.stream().map(item -> {
            RequisitionApprovalStatusRespVO.Item itemVO = new RequisitionApprovalStatusRespVO.Item();
            itemVO.setId(item.getId());
            itemVO.setMaterialId(item.getMaterialId());
            itemVO.setQuantity(item.getQuantity());
            BigDecimal returned = item.getReturnedQty() != null ? item.getReturnedQty() : ZERO;
            itemVO.setReturnedQty(returned);
            // 可退量：quantity - returned_qty。退货只在已出库/部分回退有意义，其他状态也照样算出来
            // （前端只在对应状态显示按钮），好处是「一次接口拿全」，不必按状态切换返回结构。
            BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : ZERO;
            itemVO.setReturnableQty(quantity.subtract(returned));
            return itemVO;
        }).toList());
        return respVO;
    }

    // ========== 审批结果回调 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleApprovalResult(Long requisitionId, String processInstanceId,
                                     Integer approvalStatus, String reason) {
        RequisitionDO requisition = requisitionId != null ? requisitionMapper.selectById(requisitionId) : null;
        if (requisition == null) {
            // 回调早于业务提交 / 单据被物理删除：记日志丢弃，**不抛异常**打断 bpm 的转发链
            // （抛异常会让 bpm 侧收到 PROCESS_INSTANCE_HTTP_CALL_ERROR，污染流程结束事务）
            log.warn("[handleApprovalResult][申领单({}) 不存在，忽略审批回调，processInstanceId={}]",
                    requisitionId, processInstanceId);
            return;
        }

        // ========== 幂等 1/3：串号校验 ==========
        // 回调实例号与库中当前实例不一致 → 旧流程的迟到通知（如驳回后重新发起、旧实例随后结束），丢弃。
        if (StrUtil.isNotBlank(processInstanceId)
                && StrUtil.isNotBlank(requisition.getProcessInstanceId())
                && !StrUtil.equals(processInstanceId, requisition.getProcessInstanceId())) {
            log.warn("[handleApprovalResult][申领单({}) 审批回调实例({}) 与当前实例({}) 不一致，忽略]",
                    requisitionId, processInstanceId, requisition.getProcessInstanceId());
            return;
        }

        // ========== 幂等 2/3：终态短路 ==========
        Integer target = normalizeTargetStatus(approvalStatus);
        if (ObjectUtil.equal(requisition.getApprovalStatus(), target)) {
            log.info("[handleApprovalResult][申领单({}) 已是终态 {}, 判定为重复回调，幂等返回]", requisitionId, target);
            return;
        }
        // 还没进入审批（-1 未开始）却收到结果：说明这张单走过 V1 同步链路，或者数据被外部改动。
        // 一律忽略——V1 的表单里从来没发起过流程，任何"审批结果"对它都不成立。
        if (!ObjectUtil.equal(requisition.getApprovalStatus(), RequisitionApprovalStatusEnum.RUNNING.getStatus())) {
            log.warn("[handleApprovalResult][申领单({}) 当前审批状态({}) 不是审批中，忽略回调 status={}]",
                    requisitionId, requisition.getApprovalStatus(), approvalStatus);
            return;
        }

        if (ObjectUtil.equal(target, RequisitionApprovalStatusEnum.APPROVE.getStatus())) {
            handleApproved(requisition, reason);
        } else {
            handleRejected(requisition, target, reason);
        }
    }

    /**
     * 审批通过：已提交(1) → 已扣减(2)，**库存与流水完全不动**
     *
     * 为什么这里一行库存代码都没有：{@link #submitApproval} 在提交时已经通过 V1 的行锁扣减
     * 扣了 stock 并写了 biz_type=2 流水。审批通过只是「放行这个已扣减的单据」。
     * 若在这里再扣一次 = 同一张单扣两遍，而驳回只回补一遍，账永远不平
     * （单测 {@code testHandleApprovalResult_approve_stockUnchanged} 钉住）。
     */
    private void handleApproved(RequisitionDO requisition, String reason) {
        // ========== 幂等 3/3：CAS 主闸门 ==========
        // WHERE approval_status = 1：两个并发回调只有一个能命中，另一个影响行数 0 → 幂等返回。
        int casRows = requisitionMapper.finishApprovalCas(requisition.getId(),
                RequisitionStatusEnum.DEDUCTED.getStatus(),
                RequisitionApprovalStatusEnum.APPROVE.getStatus());
        if (casRows != 1) {
            log.info("[handleApprovalResult][申领单({}) 审批通过 CAS 未命中（已被并发回调处理），幂等返回]",
                    requisition.getId());
            return;
        }
        // 内存态回填：返回值/日志与库值一致（L3-10，「库里对、响应体错」类缺陷的护栏）
        requisition.setStatus(RequisitionStatusEnum.DEDUCTED.getStatus());
        requisition.setApprovalStatus(RequisitionApprovalStatusEnum.APPROVE.getStatus());

        log.info("[handleApprovalResult][申领单({}) 会签全部通过：已提交(1)→已扣减(2)，"
                        + "库存与流水均未变动（提交审批时已扣减），reason={}]",
                requisition.getId(), reason);
    }

    /**
     * 审批驳回/取消：已提交(1) → 已取消(9) + **回补库存** + 写 biz_type=4 冲正流水
     *
     * 为什么「审批不通过(3)」与「已取消(4)」走同一条路：两者都意味着**这张单不批了**，
     * 而库存在提交审批时就已扣掉——不回补就是「料已经扣、单却作废」，库存永久少一块。
     * 业务状态统一落 9(已取消)（状态机 1→9 是既有合法边），审批状态按回调原值区分 3/4，
     * 这样报表既能按业务状态统计，也能看出是"审批驳回"还是"主动撤回"。
     *
     * ========== W5-P3：回补动作改为调用**统一回滚组件**（本类不再自己拼回补逻辑） ==========
     *
     * 「审批驳回」与「超时关单」（{@code RequisitionTimeoutCloseService}）的库存事实完全相同：
     * 提交时已扣、现在要还、都要写一条 biz_type=4 冲正流水。若各写一份，必然分叉
     * （W5-P2 的 V2 cancel 就曾只还了 Redis、没回补 DB）。因此两者统一走
     * {@code StockRollbackService#rollbackForRequisition}——**同一个 Spring bean、同一个方法**，
     * 双边的单测各自用 Mockito `verify` 钉住"调到了它"（驳回侧见
     * {@code testHandleApprovalResult_reject_callsUnifiedRollbackComponent}，
     * 超时侧见 {@code testClose_submitted_closedWithRestore}）。
     *
     * 组件内部仍复用 V1 的行锁回补实现（`SELECT ... FOR UPDATE` → `available_qty += qty` → 冲正流水），
     * 所以 V1 代码一行未改，"回补 + 冲正"从此只有组件这一个入口。
     */
    private void handleRejected(RequisitionDO requisition, Integer target, String reason) {
        // ========== 幂等 3/3：CAS 主闸门（必须在回补库存之前执行） ==========
        // 顺序很关键：先 CAS 拿到「这一次是我处理的」，再做库存回补。
        // 若反过来（先回补再 CAS），两个并发回调都会各回补一次，第二个 CAS 失败时**事务里已经写进去的**
        // 回补却不会被撤销（CAS 失败通常被当作幂等返回而非抛错，事务照常提交）→ 库存翻倍。
        int casRows = requisitionMapper.finishApprovalCas(requisition.getId(),
                RequisitionStatusEnum.CANCELED.getStatus(), target);
        if (casRows != 1) {
            log.info("[handleApprovalResult][申领单({}) 驳回 CAS 未命中（已被并发回调处理），幂等返回，不重复回补]",
                    requisition.getId());
            return;
        }

        // 回补库存 + 写冲正流水（biz_type=4，quantity 正数）——统一回滚组件，
        // 与超时关单是同一条代码路径（内部按 materialId 升序锁行 + 写冲正流水）
        RequisitionRollbackResult rollbackResult = stockRollbackService.rollbackForRequisition(
                requisition, null, "审批未通过");
        if (!rollbackResult.isAcquired()) {
            // 回补不彻底 → 抛错整体回滚（含上面的 CAS），单据回到审批中。
            // 静默提交会留下"单据已取消、库存还有一部分没还"的账不平，且很难与别的问题区分。
            throw new IllegalStateException(String.format(
                    "审批驳回回滚不彻底：reqNo=%s requisitionId=%s result=%s",
                    requisition.getReqNo(), requisition.getId(), rollbackResult));
        }

        requisition.setStatus(RequisitionStatusEnum.CANCELED.getStatus());
        requisition.setApprovalStatus(target);
        log.info("[handleApprovalResult][申领单({}) 审批未通过：已提交(1)→已取消(9)，"
                        + "已回补库存并写 biz_type=4 冲正流水，approvalStatus={}, reason={}, rollback={}]",
                requisition.getId(), target, reason, rollbackResult);
    }

    // ========== 出库 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RequisitionDO outbound(Long requisitionId) {
        RequisitionDO requisition = validateRequisitionExists(requisitionId);
        // 只有已扣减(2) 可出库。已出库(3) 重复出库必须拒绝（否则 actual_out_time 被刷新，报表失真）；
        // 已提交(1) 还没审批通过，料不能先出（`1→3` 也不在状态机允许边上）。
        if (!ObjectUtil.equal(requisition.getStatus(), RequisitionStatusEnum.DEDUCTED.getStatus())) {
            throw exception(REQUISITION_CANNOT_OUTBOUND, statusName(requisition.getStatus()));
        }
        if (!RequisitionStatusEnum.canTransfer(requisition.getStatus(), RequisitionStatusEnum.OUTBOUND.getStatus())) {
            throw exception(REQUISITION_STATUS_INVALID, statusName(requisition.getStatus()),
                    RequisitionStatusEnum.OUTBOUND.getName());
        }

        // CAS 2→3 + 写 actual_out_time。**不动库存**：V1 在提交审批时就扣过了。
        // 时间只算一次并复用给内存态（L3-10 / L3-11：两处各 now() 会让响应体与库值差几毫秒）。
        LocalDateTime actualOutTime = LocalDateTime.now();
        int casRows = requisitionMapper.outboundCas(requisitionId, actualOutTime);
        if (casRows != 1) {
            log.warn("[outbound][单据状态已被并发修改 id={} 期望 status=已扣减]", requisitionId);
            throw exception(REQUISITION_CANNOT_OUTBOUND, statusName(requisition.getStatus()));
        }
        requisition.setStatus(RequisitionStatusEnum.OUTBOUND.getStatus());
        requisition.setActualOutTime(actualOutTime);

        log.info("[outbound][申领单({}) 出库登记完成：已扣减(2)→已出库(3)，库存未变动，actualOutTime={}]",
                requisitionId, actualOutTime);
        return requisition;
    }

    // ========== 退料 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RequisitionDO returnMaterials(RequisitionReturnReqVO reqVO) {
        Long requisitionId = reqVO.getRequisitionId();
        RequisitionDO requisition = validateRequisitionExists(requisitionId);

        // 1. 状态校验：只有已出库(3)/部分回退(5) 可退料
        //    已扣减(2) 还没出库，此时"退料"等于撤销申领（走 cancel 回补+biz_type=4 冲正），
        //    流水类型不同（3-退回 vs 4-冲正），混用会让对账无法按类型分组。
        boolean outbound = ObjectUtil.equal(requisition.getStatus(), RequisitionStatusEnum.OUTBOUND.getStatus());
        boolean partialReturned = ObjectUtil.equal(requisition.getStatus(),
                RequisitionStatusEnum.PARTIAL_RETURNED.getStatus());
        if (!outbound && !partialReturned) {
            throw exception(REQUISITION_CANNOT_RETURN, statusName(requisition.getStatus()));
        }
        Integer fromStatus = requisition.getStatus();

        // 2. 校验退料明细（非空 / 数量>0 / 同一物料不重复）
        List<RequisitionReturnReqVO.Item> reqItems = validateReturnItems(reqVO);
        // 3. 本单明细按 materialId 建索引（用于定位行 + 校验"退没领过的料"）
        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(requisitionId);
        if (CollUtil.isEmpty(items)) {
            throw exception(REQUISITION_ITEM_EMPTY);
        }
        Map<Long, RequisitionItemDO> itemByMaterialId = items.stream()
                .collect(Collectors.toMap(RequisitionItemDO::getMaterialId, item -> item, (a, b) -> a));

        // 4. 按 materialId 升序处理（与 V1 扣减/回补同一加锁顺序，防 AB-BA 死锁）
        List<RequisitionReturnReqVO.Item> orderedReqItems = reqItems.stream()
                .sorted(Comparator.comparing(RequisitionReturnReqVO.Item::getMaterialId))
                .toList();
        LocalDateTime actualReturnTime = LocalDateTime.now();
        for (RequisitionReturnReqVO.Item reqItem : orderedReqItems) {
            RequisitionItemDO item = itemByMaterialId.get(reqItem.getMaterialId());
            if (item == null) {
                // 退一张单没领过的料：会让库存净增却不挂任何单据，是对账断点的典型来源
                throw exception(REQUISITION_RETURN_ITEM_NOT_IN_REQUISITION, reqItem.getMaterialId());
            }
            returnOneItem(requisition, item, reqItem.getQuantity(), actualReturnTime);
        }

        // 5. 判全退：每行 returned_qty == quantity → 已完成(4)，否则 → 部分回退(5)
        //    为什么要重新读明细：第 4 步是原子条件更新（数据库内部累加），应用层内存里的 DO 还是旧值。
        //    直接用内存值判断会永远得出"部分回退"（第一次退料后），这是 L3-10 那类缺陷的变体。
        List<RequisitionItemDO> latestItems = requisitionItemMapper.selectListByRequisitionId(requisitionId);
        boolean allReturned = latestItems.stream().allMatch(item -> {
            BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : ZERO;
            BigDecimal returned = item.getReturnedQty() != null ? item.getReturnedQty() : ZERO;
            return returned.compareTo(quantity) >= 0;
        });
        Integer toStatus = allReturned ? RequisitionStatusEnum.COMPLETED.getStatus()
                : RequisitionStatusEnum.PARTIAL_RETURNED.getStatus();

        // 6. CAS 推进状态：用「调用方读到的 fromStatus」做期望值
        //    两个并发退料请求里，第一个把 3 改成 5，第二个的 fromStatus=3 就会影响行数 0 →
        //    抛错整体回滚（连它已回补的库存一起退掉），不会出现"两笔都各自算了一次可退量"。
        int casRows = requisitionMapper.returnCas(requisitionId, fromStatus, toStatus);
        if (casRows != 1) {
            log.warn("[returnMaterials][单据状态已被并发修改 id={} 期望 status={}]", requisitionId, fromStatus);
            throw exception(REQUISITION_STATUS_INVALID, statusName(fromStatus), statusName(toStatus));
        }
        requisitionMapper.updateReturnTime(requisitionId, actualReturnTime);

        requisition.setStatus(toStatus);
        requisition.setActualReturnTime(actualReturnTime);

        log.info("[returnMaterials][申领单({}) 退料完成：{}→{}，退料行数={}，全退={}，actualReturnTime={}]",
                requisitionId, statusName(fromStatus), statusName(toStatus),
                orderedReqItems.size(), allReturned, actualReturnTime);
        return requisition;
    }

    /**
     * 单行退料：原子累加 returned_qty → 行锁回补库存 → 写 biz_type=3 退回流水
     *
     * @param requisition      申领单（提供 projectId / reqNo）
     * @param item             明细行（提供 id / quantity）
     * @param quantity         本次退料量（> 0）
     * @param actualReturnTime 本次退料时间（写进流水备注，便于与单据上的最近退料时间对照）
     */
    private void returnOneItem(RequisitionDO requisition, RequisitionItemDO item,
                               BigDecimal quantity, LocalDateTime actualReturnTime) {
        Long projectId = requisition.getProjectId();
        Long materialId = item.getMaterialId();

        // ① 原子条件更新：returned_qty += qty，且必须 <= quantity
        //    影响行数 0 = 超过可退量（或明细被删）。这里**不先查再判**：
        //    查-判-写之间存在窗口，并发重复退料会双双通过 → 库存退两次（不可察觉的账务错误）。
        int updatedRows = requisitionItemMapper.increaseReturnedQtyReturnCheck(item.getId(), quantity);
        if (updatedRows != 1) {
            BigDecimal quantityOfItem = item.getQuantity() != null ? item.getQuantity() : ZERO;
            BigDecimal returned = item.getReturnedQty() != null ? item.getReturnedQty() : ZERO;
            BigDecimal returnable = quantityOfItem.subtract(returned);
            log.warn("[returnOneItem][退料超量 reqNo={} materialId={} 可退={} 本次={}]",
                    requisition.getReqNo(), materialId, returnable, quantity);
            throw exception(REQUISITION_RETURN_QUANTITY_EXCEEDED, materialId,
                    returnable.toPlainString(), quantity.toPlainString());
        }

        // ② 行锁回补库存（与 V1 扣减/回补同一把锁，热点行上自然串行）
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialIdForUpdate(projectId, materialId);
        if (stock == null) {
            // 库存行不见了（被管理动作删掉）：回补无处可补。抛错整体回滚，
            // 静默跳过会造成「料已退但库存没加」且无人知晓。
            throw exception(STOCK_NOT_EXISTS);
        }
        int stockRows = materialStockMapper.increaseAvailableQtyUnderLock(stock.getId(), quantity);
        if (stockRows != 1) {
            throw exception(STOCK_NOT_EXISTS);
        }
        BigDecimal available = stock.getAvailableQty() != null ? stock.getAvailableQty() : ZERO;
        BigDecimal balanceAfter = available.add(quantity);

        // ③ 写退回流水：biz_type=3、quantity 记**正数**（库存净增）、balance_after=回补后余额
        //    备注里带上退料时间，便于与 bc_requisition.actual_return_time（只存最近一次）对照定位批次
        StockLogDO stockLog = StockLogDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .bizType(StockBizTypeEnum.RETURN.getBizType())
                .bizNo(requisition.getReqNo())
                .quantity(quantity)
                .balanceAfter(balanceAfter)
                .remark("退料退回：" + requisition.getReqNo() + " @" + actualReturnTime)
                .build();
        stockLogMapper.insert(stockLog);

        log.debug("[returnOneItem][退料成功 reqNo={} materialId={} qty={} balanceAfter={}]",
                requisition.getReqNo(), materialId, quantity, balanceAfter);
    }

    // ========== 私有方法 ==========

    /**
     * 校验单据存在（不存在抛业务错误码，与 V1 Service 同口径）
     */
    private RequisitionDO validateRequisitionExists(Long requisitionId) {
        if (requisitionId == null) {
            throw exception(REQUISITION_NOT_EXISTS);
        }
        RequisitionDO requisition = requisitionMapper.selectById(requisitionId);
        if (requisition == null) {
            throw exception(REQUISITION_NOT_EXISTS);
        }
        return requisition;
    }

    /**
     * 校验并规整会签审批人列表（去重、保序、去空值）
     *
     * 为什么去重而不是报错：前端多选控件偶尔会把同一个人传两次（勾选-取消-再勾选），
     * 报错体验差且无业务意义；去重后 2 人变 1 人，会签语义仍然是正确的（1 人通过即全部通过）。
     * 空列表则**必须报错**：基座多实例对空集合不创建任何任务，流程会直接流到结束并被判定通过
     * ——那意味着这张单在无人审批下扣掉了库存。
     *
     * @param approverUserIds 原始审批人列表
     * @return 去重后的审批人列表
     */
    private List<Long> validateApprovers(List<Long> approverUserIds) {
        if (CollUtil.isEmpty(approverUserIds)) {
            throw exception(REQUISITION_APPROVERS_EMPTY);
        }
        List<Long> approvers = approverUserIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (approvers.isEmpty()) {
            // 全是 null 的列表与空列表等价（都构不成会签人）
            throw exception(REQUISITION_APPROVERS_EMPTY);
        }
        if (approvers.size() > RequisitionApprovalSubmitReqVO.MAX_APPROVERS) {
            throw exception(REQUISITION_APPROVERS_EXCEED,
                    RequisitionApprovalSubmitReqVO.MAX_APPROVERS, approvers.size());
        }
        return approvers;
    }

    /**
     * 校验退料明细（非空 / 数量 > 0 / 同一物料不重复）
     *
     * 注意这里只做**不查库**的校验；"退没领过的料" 与 "退超量" 需要库内数据，在调用方处理
     * （前者靠本单明细索引，后者靠原子条件更新的影响行数）。
     */
    private List<RequisitionReturnReqVO.Item> validateReturnItems(RequisitionReturnReqVO reqVO) {
        List<RequisitionReturnReqVO.Item> reqItems = reqVO.getItems();
        if (CollUtil.isEmpty(reqItems)) {
            throw exception(REQUISITION_RETURN_ITEM_EMPTY);
        }
        Set<Long> materialIds = new HashSet<>();
        for (RequisitionReturnReqVO.Item reqItem : reqItems) {
            if (reqItem.getQuantity() == null || reqItem.getQuantity().compareTo(ZERO) <= 0) {
                // 退 0 等于没退，却会写出一条 quantity=0 的流水，让"退回笔数"与"实际回补量"对不上；
                // 数量为负更严重（会变成反向申领把库存扣掉），必须拦住。
                throw exception(REQUISITION_RETURN_QUANTITY_INVALID,
                        reqItem.getMaterialId(), reqItem.getQuantity());
            }
            if (!materialIds.add(reqItem.getMaterialId())) {
                // 同一物料两行：两行各自校验可退量会同时通过却合计超量（与建单重复物料同一类缺陷）
                throw exception(REQUISITION_RETURN_ITEM_DUPLICATE, reqItem.getMaterialId());
            }
        }
        return reqItems;
    }

    /**
     * 解析发起人（流程里记为 initiator，审批页展示"申请人"）
     *
     * 取值顺序：登录态 → 单据上的申请人。单测无登录态，生产链路走登录态。
     * 两者都为空时用 0 占位而不是抛错：申领单的 applicant_user_id 是 NOT NULL，
     * 理论上不会为空；这里只为防御手工导入的脏数据，不让它打断整个发起动作。
     */
    private Long resolveInitiatorUserId(RequisitionDO requisition) {
        Long loginUserId = SecurityFrameworkUtils.getLoginUserId();
        if (loginUserId != null) {
            return loginUserId;
        }
        if (requisition.getApplicantUserId() != null) {
            return requisition.getApplicantUserId();
        }
        log.warn("[resolveInitiatorUserId][申领单({}) 既无登录态也无申请人，流程发起人记为 0]", requisition.getId());
        return 0L;
    }

    /**
     * 构造流程变量
     *
     * 四个变量各有其不可替代的用途（与 BPMN 文件头「流程变量」一节严格对应）：
     * - {@code requisitionId}：businessKey 的冗余副本，审批页可直接读变量展示，不必反查业务库；
     * - {@code reqNo}：待办标题/审批单号；
     * - {@code items}：明细摘要文本——审批人必须看到"要申领什么、多少"才能决策
     *   （基座审批详情页只渲染流程变量与业务表单，不会反查业务库）；
     * - {@code approvers}：**本流程唯一决定会签实例数的变量**（BPMN 用 candidateStrategy=60 表达式取人），
     *   少传这个变量 → 会签节点取不到人 → 一个任务都不创建 → 流程直接通过（账务事故）。
     */
    private Map<String, Object> buildProcessVariables(RequisitionDO requisition,
                                                      List<RequisitionItemDO> items,
                                                      List<Long> approvers, Long userId) {
        Map<String, Object> variables = new HashMap<>(8);
        variables.put(VARIABLE_REQUISITION_ID, requisition.getId());
        variables.put(VARIABLE_REQ_NO, requisition.getReqNo());
        variables.put(VARIABLE_ITEMS, buildItemsSummary(items));
        variables.put(VARIABLE_APPROVERS, approvers);
        variables.put(VARIABLE_INITIATOR, userId);
        return variables;
    }

    /**
     * 明细摘要文本："物料1×10；物料2×20"
     *
     * 这里只放物料编号与数量，不查物料名称：查名字要跨服务（物料主数据在本服务内，
     * 但审批页是 bpm 渲染的，塞名字会让流程变量体积随明细增长）。审批人点进业务表单看详情即可。
     */
    private String buildItemsSummary(List<RequisitionItemDO> items) {
        return items.stream()
                .map(item -> "物料" + item.getMaterialId() + "×" + item.getQuantity().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining("；"));
    }

    /**
     * 把回调状态值规范化成三个业务终态之一
     *
     * bpm 只会发 APPROVE(2) / REJECT(3) / CANCEL(4)，其余值（RUNNING/NOT_START/未知）
     * 不应进到本方法（Service 里已过滤「不是审批中」的情况）；这里保守地把「非通过」一律当驳回处理，
     * 保证任何意外取值都不会误触发「通过」这个**会让库存账固化**的动作。
     */
    private Integer normalizeTargetStatus(Integer approvalStatus) {
        if (ObjectUtil.equal(approvalStatus, RequisitionApprovalStatusEnum.APPROVE.getStatus())) {
            return RequisitionApprovalStatusEnum.APPROVE.getStatus();
        }
        if (ObjectUtil.equal(approvalStatus, RequisitionApprovalStatusEnum.CANCEL.getStatus())) {
            return RequisitionApprovalStatusEnum.CANCEL.getStatus();
        }
        return RequisitionApprovalStatusEnum.REJECT.getStatus();
    }

    /**
     * 解析 approvers 列（"1,100" → [1, 100]）
     *
     * 脏数据容忍：非数字段直接跳过并打日志（不抛错——详情页不该因为一行脏数据打不开，
     * 而审批人列表只是展示信息，不参与任何决策）。
     */
    private List<Long> parseApprovers(String approvers) {
        if (StrUtil.isBlank(approvers)) {
            return List.of();
        }
        return StrUtil.split(approvers, APPROVERS_SEPARATOR).stream()
                .filter(StrUtil::isNotBlank)
                .map(text -> {
                    try {
                        return Long.valueOf(text.trim());
                    } catch (NumberFormatException e) {
                        log.warn("[parseApprovers][审批人列表含非法值({})，已跳过]", text);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 按审批状态 + 业务状态给出面向操作者的说明
     *
     * 同时看两个状态：审批通过(2) 但业务状态仍是已扣减(2) 时提示"可出库"，
     * 已出库(3) 时提示"可退料"——单看审批状态给不出这类下一步指引。
     */
    private String buildReason(Integer approvalStatus, Integer status) {
        if (ObjectUtil.equal(approvalStatus, RequisitionApprovalStatusEnum.RUNNING.getStatus())) {
            return "会签审批中，全部审批人通过后才会放行";
        }
        if (ObjectUtil.equal(approvalStatus, RequisitionApprovalStatusEnum.REJECT.getStatus())) {
            return "审批未通过，库存已回补并写冲正流水，单据已取消";
        }
        if (ObjectUtil.equal(approvalStatus, RequisitionApprovalStatusEnum.CANCEL.getStatus())) {
            return "审批已取消，库存已回补并写冲正流水";
        }
        if (ObjectUtil.equal(approvalStatus, RequisitionApprovalStatusEnum.NOT_START.getStatus())) {
            return "尚未提交审批（或走的是 V1 同步提交流程）";
        }
        // 通过之后的下一步指引
        if (ObjectUtil.equal(status, RequisitionStatusEnum.DEDUCTED.getStatus())) {
            return "会签已通过，库存已扣减，可出库";
        }
        if (ObjectUtil.equal(status, RequisitionStatusEnum.OUTBOUND.getStatus())) {
            return "已出库，可退料或核销完成";
        }
        if (ObjectUtil.equal(status, RequisitionStatusEnum.PARTIAL_RETURNED.getStatus())) {
            return "部分退料完成，剩余物料仍在使用中";
        }
        return "会签已通过";
    }

    /**
     * 状态值转可读名字（错误码文案用；未知值原样回显，便于排障时看到脏数据）
     */
    private String statusName(Integer status) {
        RequisitionStatusEnum statusEnum = RequisitionStatusEnum.valueOf(status);
        return statusEnum != null ? statusEnum.getName() : Objects.toString(status, "未知");
    }

}
