package com.buildcloud.contract.service.contract;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import com.buildcloud.contract.controller.admin.contract.vo.contractapproval.ContractApprovalStatusRespVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentRecordDO;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.dal.mysql.contract.ContractPaymentRecordMapper;
import com.buildcloud.contract.enums.contract.ContractApprovalStatusEnum;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import com.buildcloud.contract.enums.contract.PaymentRecordTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_ALREADY_RUNNING;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_AMOUNT_INVALID;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_PENDING_AMOUNT_MISSING;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_START_FAILED;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_STATUS_NOT_ALLOWED;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static com.buildcloud.contract.enums.contract.ContractBpmConstants.PROCESS_KEY_PAYMENT_APPROVE;
import static com.buildcloud.contract.enums.contract.ContractBpmConstants.VARIABLE_CONTRACT_ID;
import static com.buildcloud.contract.enums.contract.ContractBpmConstants.VARIABLE_CONTRACT_NAME;
import static com.buildcloud.contract.enums.contract.ContractBpmConstants.VARIABLE_CONTRACT_NO;
import static com.buildcloud.contract.enums.contract.ContractBpmConstants.VARIABLE_INITIATOR;
import static com.buildcloud.contract.enums.contract.ContractBpmConstants.VARIABLE_PAYMENT_AMOUNT;
import static com.buildcloud.contract.enums.contract.ContractBpmConstants.VARIABLE_PERIOD;

/**
 * 合同进度款审批 Service 实现类
 *
 * 复刻项目立项审批（{@code ProjectApprovalServiceImpl}，W3-P4）的集成模式，与之对齐的关键点：
 * 1. **先落「审批中」再调 Feign**：避免 bpm 回调先于本地提交到达时读到的还是旧状态；
 * 2. **businessKey = 业务单据主键**（这里是 contractId 的字符串形态），回调时原样带回，
 *    回调侧据此定位合同并做「串号校验」；
 * 3. **发起人 userId 显式传参**（Feign 第一个参数），bpm 侧以该身份执行流程；
 * 4. **Feign 返回空实例编号必须抛错回滚**，否则会留下「审批中但没有流程」的死状态。
 *
 * 本域特有的三件事（spec §5.1「进度款申请走审批，通过后生成 payment_record 并回写合同累计收付」）：
 * 1. **在途申请金额的落地**：基座回调事件 {@code BpmProcessInstanceStatusEvent} 只带
 *    id / key / status / reason / businessKey **五个字段，不含流程变量**，而「通过后按申请金额
 *    生成流水」必须知道金额。故提交时把金额与期次暂存在
 *    {@code bc_contract.pending_payment_amount / pending_period}，出结果后清空
 *    （详细取舍见 sql/buildcloud/06-contract-approval.sql 文件头）；
 * 2. **通过的副作用复用既有能力、不重复造轮子**：调
 *    {@link ContractPaymentRecordService#createPaymentRecord} 落流水，它内部就用
 *    {@code ContractMapper#updatePaidAmount} 做 `paid_amount = paid_amount + #{delta}` 原子累加
 *    （P3 已实现并单测覆盖），本类不再自己写一条累加 SQL——两处口径分叉是账实不符的典型根源；
 * 3. **幂等三件套**：串号校验 → 终态短路 → 按 processInstanceId 判重（见 handleApprovalResult）。
 *
 * 驳回/取消的语义与立项审批不同：立项驳回「项目保持筹备」；本域驳回要
 * **把合同退回草稿**（首次申请的场景），因为进度款金额/期次往往要改，而草稿才允许 update；
 * 但若合同已在履行中（追加期次被驳回），**不回退业务状态**——否则一份已收过款的合同会变回
 * 可删除的草稿，台账口径直接崩。
 *
 * @author buildcloud
 */
@Service
@Validated
@Slf4j
public class ContractApprovalServiceImpl implements ContractApprovalService {

    /**
     * 金额比较用的零值（避免每次 new）
     */
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @Resource
    private ContractMapper contractMapper;

    @Resource
    private ContractPaymentRecordMapper paymentRecordMapper;

    @Resource
    private ContractPaymentRecordService paymentRecordService;

    /**
     * 基座 BPM 流程实例 RPC（bpm-server，@FeignClient(name="bpm-server")）
     */
    @Resource
    private BpmProcessInstanceApi processInstanceApi;

    // ========== 提交审批 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String submitApproval(Long contractId, Integer period, BigDecimal amount) {
        // 1.1 校验合同存在
        ContractDO contract = contractId != null ? contractMapper.selectById(contractId) : null;
        if (contract == null) {
            throw exception(CONTRACT_NOT_EXISTS);
        }
        // 1.2 校验没有审批中的流程：防同一合同并发发起两个实例
        //     放在业务状态校验之前，是因为「首次申请尚未出结果」时合同业务状态就是审批中(1)，
        //     若先按状态拦，用户只会看到笼统的「状态不允许」，而真正原因是已有在途流程——
        //     先给最具体的错误码（ALREADY_RUNNING 还带出实例号），排障更快。
        //     只拦 approvalStatus=审批中；终态（通过/驳回/取消）允许重新发起——驳回后改金额再提交是常态
        if (ObjectUtil.equal(contract.getApprovalStatus(), ContractApprovalStatusEnum.RUNNING.getStatus())) {
            throw exception(CONTRACT_APPROVAL_ALREADY_RUNNING,
                    StrUtil.blankToDefault(contract.getProcessInstanceId(), "-"));
        }
        // 1.3 校验合同业务状态可发起审批
        //     允许两种入口：
        //     ① 草稿(0)：首次进度款申请（合同登记后还没开始履行）；
        //     ② 履行中(2)：后续期次追加（工程进度款本就是按期申请的）。
        //     已结算(3)/已作废(9) 是终态，不允许再产生收付款
        //     （与 ContractPaymentRecordServiceImpl#validateContractEffective 同口径）。
        boolean draft = ContractStatusEnum.DRAFT.match(contract.getStatus());
        boolean performing = ContractStatusEnum.PERFORMING.match(contract.getStatus());
        if (!draft && !performing) {
            throw exception(CONTRACT_APPROVAL_STATUS_NOT_ALLOWED, getContractStatusName(contract.getStatus()));
        }
        // 1.4 校验申请金额：> 0 且不超合同未收付余额
        //     超付会让 paid_amount 大于 amount，台账对账的「计划 vs 实际」口径直接失真（spec §5.1）
        validateApplyAmount(contract, amount);

        // 2. 置「审批中」+ 暂存在途金额/期次
        //    草稿 → 审批中(1) 是状态机允许的边（ContractStatusEnum#canTransfer）；
        //    履行中追加期次时**不动 status**（2→1 不在状态机允许边上，且合同确实还在履行）。
        ContractDO updateObj = new ContractDO();
        updateObj.setId(contract.getId());
        updateObj.setApprovalStatus(ContractApprovalStatusEnum.RUNNING.getStatus());
        updateObj.setPendingPaymentAmount(amount);
        updateObj.setPendingPeriod(period);
        if (draft) {
            updateObj.setStatus(ContractStatusEnum.APPROVING.getStatus());
        }
        contractMapper.updateById(updateObj);

        // 3. 发起流程实例（读码笔记 §2.1：四个入参 = key + variables + businessKey + 自选审批人）
        //    审批人由 BPMN 里配置的「用户组 9202 财务审批组」决定，不用发起人自选，第四个参数传 null
        Long userId = getLoginUserId();
        String processInstanceId = processInstanceApi.createProcessInstance(userId,
                        new BpmProcessInstanceCreateReqDTO()
                                .setProcessDefinitionKey(PROCESS_KEY_PAYMENT_APPROVE)
                                .setBusinessKey(String.valueOf(contract.getId()))
                                .setVariables(buildProcessVariables(contract, period, amount, userId)))
                .getCheckedData();
        // 3.1 基座返回空实例编号说明 bpm 侧没有真正建实例，必须抛错回滚，
        //     否则业务表会留下「审批中但没有流程」的死状态（永远等不到回调）
        if (StrUtil.isBlank(processInstanceId)) {
            throw exception(CONTRACT_APPROVAL_START_FAILED);
        }

        // 4. 回写流程实例编号（approvalStatus / 在途金额已在第 2 步写入）
        contractMapper.updateById(new ContractDO().setId(contract.getId()).setProcessInstanceId(processInstanceId));
        log.info("[submitApproval][合同({}) 发起进度款审批成功，processInstanceId={}, period={}, amount={}, userId={}]",
                contract.getId(), processInstanceId, period, amount, userId);
        return processInstanceId;
    }

    // ========== 查询状态 ==========

    @Override
    public ContractApprovalStatusRespVO getApprovalStatus(Long contractId) {
        ContractDO contract = contractId != null ? contractMapper.selectById(contractId) : null;
        if (contract == null) {
            throw exception(CONTRACT_NOT_EXISTS);
        }
        ContractApprovalStatusRespVO respVO = new ContractApprovalStatusRespVO();
        respVO.setContractId(contract.getId());
        respVO.setContractNo(contract.getContractNo());
        respVO.setProcessInstanceId(contract.getProcessInstanceId());
        // approval_status 列为 NOT NULL DEFAULT -1，但历史数据/手工导入可能为 null，兜底成「未开始」
        Integer approvalStatus = contract.getApprovalStatus() != null
                ? contract.getApprovalStatus() : ContractApprovalStatusEnum.NOT_START.getStatus();
        respVO.setApprovalStatus(approvalStatus);
        ContractApprovalStatusEnum statusEnum = ContractApprovalStatusEnum.valueOf(approvalStatus);
        respVO.setApprovalStatusName(statusEnum != null ? statusEnum.getDesc() : "未知");
        respVO.setContractStatus(contract.getStatus());
        respVO.setContractStatusName(getContractStatusName(contract.getStatus()));
        respVO.setContractAmount(contract.getAmount());
        respVO.setPaidAmount(contract.getPaidAmount());
        respVO.setPendingPaymentAmount(contract.getPendingPaymentAmount());
        respVO.setPendingPeriod(contract.getPendingPeriod());
        respVO.setReason(buildReason(approvalStatus));
        return respVO;
    }

    // ========== 回调处理 ==========

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleApprovalResult(Long contractId, String processInstanceId, boolean approved, String reason) {
        handleApprovalResult(contractId, processInstanceId,
                approved ? ContractApprovalStatusEnum.APPROVE.getStatus()
                        : ContractApprovalStatusEnum.REJECT.getStatus(), reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleApprovalResult(Long contractId, String processInstanceId, Integer approvalStatus, String reason) {
        ContractDO contract = contractId != null ? contractMapper.selectById(contractId) : null;
        if (contract == null) {
            // 回调早于业务提交 / 业务单据被物理删除：记日志丢弃，不抛异常打断 bpm 的转发链
            // （抛异常会让 bpm 侧 HTTP 调用抛 PROCESS_INSTANCE_HTTP_CALL_ERROR，污染流程结束事务）
            log.warn("[handleApprovalResult][合同({}) 不存在，忽略审批回调，processInstanceId={}]",
                    contractId, processInstanceId);
            return;
        }

        // ========== 幂等 1/3：串号校验 ==========
        // 回调带的实例编号与库中当前实例不一致 → 旧流程的迟到通知，丢弃。
        // 典型场景：驳回后重新发起新流程，旧实例的取消/完成事件随后到达。
        if (StrUtil.isNotBlank(processInstanceId)
                && StrUtil.isNotBlank(contract.getProcessInstanceId())
                && !StrUtil.equals(processInstanceId, contract.getProcessInstanceId())) {
            log.warn("[handleApprovalResult][合同({}) 审批回调实例({}) 与当前实例({}) 不一致，忽略]",
                    contractId, processInstanceId, contract.getProcessInstanceId());
            return;
        }

        // ========== 幂等 2/3：终态短路 ==========
        // 已是同一终态 → 重复通知，直接返回。这是防止「重复生成流水 / 重复累加 paid_amount」的第一道闸。
        Integer target = normalizeTargetStatus(approvalStatus);
        Integer current = contract.getApprovalStatus();
        if (ObjectUtil.equal(current, target)) {
            log.info("[handleApprovalResult][合同({}) 已是终态 {}, 判定为重复回调，幂等返回]",
                    contractId, target);
            return;
        }

        boolean approved = ObjectUtil.equal(target, ContractApprovalStatusEnum.APPROVE.getStatus());
        if (approved) {
            handleApproved(contract, processInstanceId, reason);
        } else {
            handleRejected(contract, target, reason);
        }
    }

    /**
     * 审批通过：生成收付款流水 + 原子累加累计收付 + 合同转履行中
     *
     * 执行顺序有讲究：
     * 1. 先把合同业务状态推到「履行中(2)」——因为
     *    {@link ContractPaymentRecordService#createPaymentRecord} 的前置校验就是
     *    「合同必须履行中」（{@code CONTRACT_NOT_EFFECTIVE}），这也是 spec 的既定语义：
     *    只有生效合同才能发生实际收付；
     * 2. 再落流水（内部完成 paid_amount 原子累加）；
     * 3. 最后清空在途金额/期次并写 approval_status=2。
     *
     * 整个方法在一个本地事务里，任一步抛异常都回滚，不会出现「有流水没累加」或反过来的中间态。
     */
    private void handleApproved(ContractDO contract, String processInstanceId, String reason) {
        // ========== 幂等 3/3：按 processInstanceId 判重 ==========
        // 同一个流程实例只允许生成一条流水。即使前两道闸因数据被外部改动而失效
        // （例如 approval_status 被手工重置成 1），这里仍能挡住重复生成与重复累加。
        ContractPaymentRecordDO existed = paymentRecordMapper.selectByApprovalNo(processInstanceId);
        if (existed != null) {
            log.warn("[handleApproved][合同({}) 流程实例({}) 已生成流水({})，判定为重复回调，不重复生成/累加]",
                    contract.getId(), processInstanceId, existed.getId());
            contractMapper.updateById(new ContractDO().setId(contract.getId())
                    .setApprovalStatus(ContractApprovalStatusEnum.APPROVE.getStatus()));
            contractMapper.clearPendingPayment(contract.getId());
            return;
        }

        // 1. 在途申请金额：通过时必须存在（提交时必写），缺了说明数据被外部改动，
        //    此时无法凭空空生成流水，抛错留痕让人工核对，而不是造一条金额为 0 的假流水
        BigDecimal amount = contract.getPendingPaymentAmount();
        if (amount == null || amount.compareTo(ZERO) <= 0) {
            log.error("[handleApproved][合同({}) 审批通过但缺少在途申请金额(pending_payment_amount={})，无法生成流水]",
                    contract.getId(), amount);
            throw exception(CONTRACT_APPROVAL_PENDING_AMOUNT_MISSING, contract.getId());
        }

        // 2. 合同业务状态 → 履行中(2)（草稿→审批中→履行中 走状态机允许的边；已在履行中则无需变更）
        if (!ContractStatusEnum.PERFORMING.match(contract.getStatus())) {
            contractMapper.updateById(new ContractDO().setId(contract.getId())
                    .setStatus(ContractStatusEnum.PERFORMING.getStatus()));
        }

        // 3. 生成实际收付款流水（内部用 updatePaidAmount 原子累加 paid_amount，P3 已单测覆盖）
        //    type 由合同方向决定：收入合同(1)=我方收款 → 收款(1)；支出合同(2)=我方付款 → 付款(2)
        ContractPaymentRecordSaveReqVO recordReqVO = new ContractPaymentRecordSaveReqVO();
        recordReqVO.setContractId(contract.getId());
        // approval_no 存流程实例编号：既是幂等键，也是「流水→审批单」的可追溯线索
        recordReqVO.setApprovalNo(processInstanceId);
        recordReqVO.setAmount(amount);
        // 实际收付款日期取审批通过当天（spec §5.1：实际收付按 date 入账，付款日即到账日/出账日）
        recordReqVO.setPayDate(LocalDate.now());
        recordReqVO.setType(resolveRecordType(contract));
        recordReqVO.setRemark("进度款审批通过自动生成（第 " + StrUtil.nullToDefault(
                contract.getPendingPeriod() != null ? String.valueOf(contract.getPendingPeriod()) : null, "-")
                + " 期）");
        Long recordId = paymentRecordService.createPaymentRecord(recordReqVO);

        // 4. 回写审批状态 + 清空在途申请（流水已落库，在途数据不再有意义）
        contractMapper.updateById(new ContractDO().setId(contract.getId())
                .setApprovalStatus(ContractApprovalStatusEnum.APPROVE.getStatus()));
        contractMapper.clearPendingPayment(contract.getId());

        log.info("[handleApprovalResult][合同({}) 进度款审批通过：生成流水({})，金额={}，期次={}，"
                        + "合同状态 {}→履行中(2)，reason={}]",
                contract.getId(), recordId, amount, contract.getPendingPeriod(),
                contract.getStatus(), reason);
    }

    /**
     * 审批驳回/取消：不生成流水、不累加，合同按场景退回
     *
     * 退回规则（与合同状态机 ContractStatusEnum#canTransfer 严格一致，不绕过状态机）：
     * - 当前「审批中(1)」→ 退回「草稿(0)」：首次进度款申请未通过，合同还没进入履行，
     *   退回草稿才允许改金额/期次后重新提交（这正是 canTransfer 里新补的那条边）；
     * - 当前「履行中(2)」→ **保持履行中**：这是「追加期次被驳回」，合同本身早已生效并已收款，
     *   若退回草稿，合同会变成可删除状态、且 canPay 失效导致历史流水无法维护，台账口径崩坏。
     */
    private void handleRejected(ContractDO contract, Integer target, String reason) {
        ContractDO updateObj = new ContractDO();
        updateObj.setId(contract.getId());
        updateObj.setApprovalStatus(target);
        if (ContractStatusEnum.APPROVING.match(contract.getStatus())) {
            updateObj.setStatus(ContractStatusEnum.DRAFT.getStatus());
        }
        contractMapper.updateById(updateObj);
        // 在途申请作废：必须显式置 NULL（updateById 会忽略 null 字段，故单列一条 SQL）
        contractMapper.clearPendingPayment(contract.getId());

        log.info("[handleApprovalResult][合同({}) 进度款审批未通过：approvalStatus {}→{}，"
                        + "合同状态 {}→{}，未生成流水、未累加累计收付，reason={}]",
                contract.getId(), contract.getApprovalStatus(), target,
                contract.getStatus(), updateObj.getStatus() != null ? updateObj.getStatus() : contract.getStatus(),
                reason);
    }

    // ========== 私有方法 ==========

    /**
     * 把回调状态值规范化成三个业务终态之一
     *
     * bpm 只会发 APPROVE(2) / REJECT(3) / CANCEL(4)，其余值（RUNNING/NOT_START/未知）
     * 不应进到本方法（监听器已过滤）；这里保守地把「非通过」一律当驳回处理，
     * 保证任何意外取值都不会误触发「生成流水」这一有资金影响的动作。
     */
    private Integer normalizeTargetStatus(Integer approvalStatus) {
        if (ObjectUtil.equal(approvalStatus, ContractApprovalStatusEnum.APPROVE.getStatus())) {
            return ContractApprovalStatusEnum.APPROVE.getStatus();
        }
        if (ObjectUtil.equal(approvalStatus, ContractApprovalStatusEnum.CANCEL.getStatus())) {
            return ContractApprovalStatusEnum.CANCEL.getStatus();
        }
        return ContractApprovalStatusEnum.REJECT.getStatus();
    }

    /**
     * 校验申请金额：必须 > 0 且不超过合同未收付余额（合同金额 - 已收付）
     */
    private void validateApplyAmount(ContractDO contract, BigDecimal amount) {
        BigDecimal contractAmount = contract.getAmount() != null ? contract.getAmount() : ZERO;
        BigDecimal paidAmount = contract.getPaidAmount() != null ? contract.getPaidAmount() : ZERO;
        BigDecimal remaining = contractAmount.subtract(paidAmount);
        if (amount == null || amount.compareTo(ZERO) <= 0 || amount.compareTo(remaining) > 0) {
            log.warn("[validateApplyAmount][合同({}) 申请金额({}) 非法：合同金额={}，已收付={}，余额={}]",
                    contract.getId(), amount, contractAmount, paidAmount, remaining);
            throw exception(CONTRACT_APPROVAL_AMOUNT_INVALID, contractAmount, paidAmount, amount);
        }
    }

    /**
     * 收付款流水类型：收入合同 → 收款(1)；支出合同 → 付款(2)
     *
     * 与 {@code ContractDirectionEnum} 的语义一致：direction 描述「这份合同整体是我方该收还是该付」，
     * record.type 描述「这笔钱实际是收进来还是付出去」，同一份合同这两个口径必须同向。
     */
    private Integer resolveRecordType(ContractDO contract) {
        return ObjectUtil.equal(contract.getDirection(), 2)
                ? PaymentRecordTypeEnum.PAYMENT.getType()
                : PaymentRecordTypeEnum.RECEIPT.getType();
    }

    /**
     * 构造流程变量
     *
     * businessKey 已经传了合同编号，这里再冗余一份 {@code contractId} 是为了让流程详情页/待办列表
     * 能直接读变量展示，不必反查业务库（基座对 OA 请假也是同样做法：变量里带 days 等业务字段）。
     * {@code paymentAmount} / {@code period} 同时是审批页的核心展示信息——财务审批人必须看到
     * 「这份合同要付多少钱、第几期」才能决策。
     */
    private Map<String, Object> buildProcessVariables(ContractDO contract, Integer period,
                                                      BigDecimal amount, Long userId) {
        Map<String, Object> variables = new HashMap<>(8);
        variables.put(VARIABLE_CONTRACT_ID, contract.getId());
        variables.put(VARIABLE_CONTRACT_NO, contract.getContractNo());
        variables.put(VARIABLE_CONTRACT_NAME, contract.getContractName());
        variables.put(VARIABLE_PAYMENT_AMOUNT, amount);
        variables.put(VARIABLE_PERIOD, period);
        variables.put(VARIABLE_INITIATOR, userId);
        // 合同表没有 dept_id 列（部门归属在项目侧 bc_project.dept_id），本域不臆造部门变量：
        // 审批人由 BPMN 的用户组策略（9202 财务审批组）决定，不依赖部门维度。
        return variables;
    }

    /**
     * 按审批状态给出面向操作者的说明
     *
     * 本域不落审批意见（需要审批意见时经 bpm 的评论/任务详情接口查，那是审批中心的本职）。
     */
    private String buildReason(Integer approvalStatus) {
        if (ObjectUtil.equal(approvalStatus, ContractApprovalStatusEnum.RUNNING.getStatus())) {
            return "已提交审批，等待财务审批";
        }
        if (ObjectUtil.equal(approvalStatus, ContractApprovalStatusEnum.NOT_START.getStatus())) {
            return "尚未提交进度款审批";
        }
        if (ObjectUtil.equal(approvalStatus, ContractApprovalStatusEnum.APPROVE.getStatus())) {
            return "进度款审批已通过，已生成收付款流水并累加合同累计收付";
        }
        if (ObjectUtil.equal(approvalStatus, ContractApprovalStatusEnum.REJECT.getStatus())) {
            return "进度款审批未通过，未生成流水，可修改金额或期次后重新提交";
        }
        if (ObjectUtil.equal(approvalStatus, ContractApprovalStatusEnum.CANCEL.getStatus())) {
            return "审批已取消，未生成流水";
        }
        return null;
    }

    /**
     * 取合同业务状态中文名；未知状态返回「未知状态(x)」而不是抛异常
     * （与 ContractStatusEnum 同口径，便于错误提示可读）
     */
    private String getContractStatusName(Integer status) {
        for (ContractStatusEnum value : ContractStatusEnum.values()) {
            if (ObjectUtil.equal(value.getStatus(), status)) {
                return value.getName();
            }
        }
        return "未知状态(" + status + ")";
    }

}
