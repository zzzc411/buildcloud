package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import com.buildcloud.contract.controller.admin.contract.vo.contractapproval.ContractApprovalStatusRespVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentRecordDO;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.dal.mysql.contract.ContractPaymentRecordMapper;
import com.buildcloud.contract.enums.contract.ContractApprovalStatusEnum;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import com.buildcloud.contract.enums.contract.PaymentRecordTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum.APPROVE;
import static cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum.CANCEL;
import static cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum.REJECT;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_ALREADY_RUNNING;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_AMOUNT_INVALID;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_PENDING_AMOUNT_MISSING;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_START_FAILED;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_APPROVAL_STATUS_NOT_ALLOWED;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static com.buildcloud.contract.enums.contract.ContractBpmConstants.PROCESS_KEY_PAYMENT_APPROVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ContractApprovalServiceImpl} 的单元测试类 —— 进度款审批与 BPM 集成
 *
 * 覆盖范围（= 本 Phase 验收清单的 6 条核心断言，逐条对应下面的分节注释）：
 * 1. 提交成功 → 回写 processInstanceId / approvalStatus=审批中 / 在途金额与期次，Feign 入参正确；
 * 2. 通过回调 → **生成 1 条 payment_record + paid_amount 累加 + 合同状态履行中(2) + approval_status=2**；
 * 3. 驳回回调 → **不生成流水、paid_amount 不变**、合同退回草稿(0)、approval_status=3；
 * 4. 重复回调幂等 → 不重复生成流水、不重复累加（这是有资金影响的幂等，必须断言流水条数与金额）；
 * 5. 非法提交被拒 → 合同不存在 / 状态不可审批 / 已在审批中 / 金额超余额 四种各抛对应错误码；
 * 6. 附加护栏 → 审批状态值与基座枚举对齐、取消语义、追加期次驳回不回退业务状态、串号回调用旧实例丢弃。
 *
 * 测试手法：{@code @MockBean} 替换 {@link BpmProcessInstanceApi}（Feign 代理无法在单测里真调）；
 * 数据库用 H2 真表（{@link BaseDbUnitTest} + {@code /sql/contract-test-tables.sql}），
 * 且**同时注册 {@link ContractPaymentRecordServiceImpl}**，因此
 * 「流水是否真的落库」「paid_amount 是否真的被原子累加」都是被真实验证的，
 * 而不是靠 mock 断言自说自话。
 *
 * @author buildcloud
 */
@Import({ContractApprovalServiceImpl.class, ContractPaymentRecordServiceImpl.class})
@Sql(scripts = "/sql/contract-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class ContractApprovalServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ContractApprovalServiceImpl contractApprovalService;

    @Resource
    private ContractMapper contractMapper;

    @Resource
    private ContractPaymentRecordMapper paymentRecordMapper;

    /**
     * BPM 流程实例 RPC 的 Mock
     *
     * 用 @MockBean 而不是手写 stub：需要让 Spring 容器里存在该 Bean 供 @Resource 注入，
     * 同时又能控制返回值。
     */
    @MockBean
    private BpmProcessInstanceApi processInstanceApi;

    // ========== 1. 提交审批：成功回写 ==========

    @Test
    public void testSubmitApproval_draftContract_success() {
        // 草稿合同、金额 1000 万、从未发起过审批
        ContractDO contract = insertContract("HT-AP-0001", "10000000.00",
                ContractStatusEnum.DRAFT, ContractApprovalStatusEnum.NOT_START, null);
        String processInstanceId = PROCESS_KEY_PAYMENT_APPROVE + ":1:" + randomString();
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(processInstanceId));

        // 调用：第 1 期申请 300 万
        String result = contractApprovalService.submitApproval(contract.getId(), 1, new BigDecimal("3000000.00"));

        // 断言 1：返回的就是 BPM 给的实例编号
        assertEquals(processInstanceId, result);

        // 断言 2：库里四列都被正确回写
        ContractDO updated = contractMapper.selectById(contract.getId());
        assertEquals(processInstanceId, updated.getProcessInstanceId());
        assertEquals(ContractApprovalStatusEnum.RUNNING.getStatus(), updated.getApprovalStatus());
        // 草稿 → 审批中（状态机允许的边，走的是 canTransfer 认可的路径）
        assertEquals(ContractStatusEnum.APPROVING.getStatus(), updated.getStatus());
        assertAmount("3000000.00", updated.getPendingPaymentAmount());
        assertEquals(1, updated.getPendingPeriod());
        // 断言 3：**未**生成流水、**未**累加（提交只是申请，不能动账）
        assertEquals(0, paymentRecordMapper.selectListByContractId(contract.getId()).size());
        assertAmount("0.00", updated.getPaidAmount());

        // 断言 4：Feign 入参正确 —— 这是「别臆造 BPM API」的回归护栏
        ArgumentCaptor<BpmProcessInstanceCreateReqDTO> captor =
                ArgumentCaptor.forClass(BpmProcessInstanceCreateReqDTO.class);
        verify(processInstanceApi).createProcessInstance(any(), captor.capture());
        BpmProcessInstanceCreateReqDTO reqDTO = captor.getValue();
        assertEquals(PROCESS_KEY_PAYMENT_APPROVE, reqDTO.getProcessDefinitionKey());
        assertEquals(String.valueOf(contract.getId()), reqDTO.getBusinessKey());
        assertNotNull(reqDTO.getVariables());
        assertEquals(contract.getId(), reqDTO.getVariables().get("contractId"));
        assertEquals(contract.getContractNo(), reqDTO.getVariables().get("contractNo"));
        assertEquals(contract.getContractName(), reqDTO.getVariables().get("contractName"));
        assertAmount("3000000.00", (BigDecimal) reqDTO.getVariables().get("paymentAmount"));
        assertEquals(1, reqDTO.getVariables().get("period"));
        // 自选审批人不使用（审批人由 BPMN 的用户组策略 9202 财务审批组决定）
        assertNull(reqDTO.getStartUserSelectAssignees());
    }

    @Test
    public void testSubmitApproval_performingContract_additionalPeriod() {
        // 已有收付历史的履行中合同追加期次：不动业务状态，只加在途申请
        ContractDO contract = insertContract("HT-AP-0002", "10000000.00",
                ContractStatusEnum.PERFORMING, ContractApprovalStatusEnum.APPROVE, null);
        contractMapper.updatePaidAmount(contract.getId(), new BigDecimal("4000000.00"));
        String processInstanceId = PROCESS_KEY_PAYMENT_APPROVE + ":2:" + randomString();
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(processInstanceId));

        contractApprovalService.submitApproval(contract.getId(), 2, new BigDecimal("2000000.00"));

        ContractDO updated = contractMapper.selectById(contract.getId());
        // 业务状态仍是履行中（2→1 不是状态机允许的边，且合同确实还在履行）
        assertEquals(ContractStatusEnum.PERFORMING.getStatus(), updated.getStatus());
        assertEquals(ContractApprovalStatusEnum.RUNNING.getStatus(), updated.getApprovalStatus());
        assertEquals(processInstanceId, updated.getProcessInstanceId());
        assertEquals(2, updated.getPendingPeriod());
        // 累计收付不变（提交申请不产生实际收付）
        assertAmount("4000000.00", updated.getPaidAmount());
    }

    @Test
    public void testSubmitApproval_bpmReturnsBlankId_rollback() {
        // BPM 返回空实例编号 → 必须抛错让事务回滚，不能留下「审批中但无流程」的死状态
        ContractDO contract = insertContract("HT-AP-0003", "10000000.00",
                ContractStatusEnum.DRAFT, ContractApprovalStatusEnum.NOT_START, null);
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(null));

        assertServiceException(() -> contractApprovalService.submitApproval(
                contract.getId(), 1, new BigDecimal("1000000.00")), CONTRACT_APPROVAL_START_FAILED);

        // 事务已回滚：状态与在途金额都回到原样
        ContractDO unchanged = contractMapper.selectById(contract.getId());
        assertEquals(ContractApprovalStatusEnum.NOT_START.getStatus(), unchanged.getApprovalStatus());
        assertEquals(ContractStatusEnum.DRAFT.getStatus(), unchanged.getStatus());
        assertNull(unchanged.getPendingPaymentAmount());
        assertNull(unchanged.getPendingPeriod());
    }

    // ========== 2. 通过回调 → 生成流水 + 累加 + 履行中 ==========

    @Test
    public void testHandleApprovalResult_approve_createsRecordAndAccumulates() {
        // 准备：审批中的合同（提交时已落在途 300 万），初始 paid_amount=0
        ContractDO contract = insertRunningContract("HT-AP-0011", "10000000.00",
                "3000000.00", 1, ContractStatusEnum.APPROVING);
        String processInstanceId = contract.getProcessInstanceId();

        // 调用：审批通过
        contractApprovalService.handleApprovalResult(contract.getId(), processInstanceId, true, "同意付款");

        ContractDO updated = contractMapper.selectById(contract.getId());
        // 断言 1：审批状态 = 通过(2)
        assertEquals(ContractApprovalStatusEnum.APPROVE.getStatus(), updated.getApprovalStatus());
        // 断言 2：合同业务状态 审批中(1) → 履行中(2)
        assertEquals(ContractStatusEnum.PERFORMING.getStatus(), updated.getStatus());
        // 断言 3：**生成且仅生成 1 条流水**，金额=申请金额、审批单号=流程实例号
        List<ContractPaymentRecordDO> records = paymentRecordMapper.selectListByContractId(contract.getId());
        assertEquals(1, records.size());
        ContractPaymentRecordDO record = records.get(0);
        assertAmount("3000000.00", record.getAmount());
        assertEquals(processInstanceId, record.getApprovalNo());
        // 收入合同(direction=1) → 收款(1)
        assertEquals(PaymentRecordTypeEnum.RECEIPT.getType(), record.getType());
        // 实际收付日期 = 审批通过当天
        assertEquals(LocalDate.now(), record.getPayDate());
        // 断言 4：**paid_amount 被原子累加**（0 + 300 万）
        assertAmount("3000000.00", updated.getPaidAmount());
        // 断言 5：在途申请被清空（出结果后不再有意义）
        assertNull(updated.getPendingPaymentAmount());
        assertNull(updated.getPendingPeriod());
        // 断言 6：流程实例编号保留（审批历史可追溯）
        assertEquals(processInstanceId, updated.getProcessInstanceId());
    }

    @Test
    public void testHandleApprovalResult_approve_expenseContract_createsPaymentType() {
        // 支出方向合同（我方付款）→ 流水类型必须是付款(2)，否则收款/付款口径会倒挂
        ContractDO contract = insertRunningContract("HT-AP-0012", "5000000.00",
                "1000000.00", 1, ContractStatusEnum.DRAFT, 2);
        contractApprovalService.handleApprovalResult(
                contract.getId(), contract.getProcessInstanceId(), true, "同意");

        List<ContractPaymentRecordDO> records = paymentRecordMapper.selectListByContractId(contract.getId());
        assertEquals(1, records.size());
        assertEquals(PaymentRecordTypeEnum.PAYMENT.getType(), records.get(0).getType());
        assertAmount("1000000.00", contractMapper.selectById(contract.getId()).getPaidAmount());
    }

    @Test
    public void testHandleApprovalResult_approve_accumulatesOnExistingPaidAmount() {
        // 履行中合同追加期次通过：paid_amount 在既有 400 万上继续累加，而不是覆盖
        ContractDO contract = insertRunningContract("HT-AP-0013", "10000000.00",
                "2000000.00", 2, ContractStatusEnum.PERFORMING);
        contractMapper.updatePaidAmount(contract.getId(), new BigDecimal("4000000.00"));

        contractApprovalService.handleApprovalResult(
                contract.getId(), contract.getProcessInstanceId(), true, "同意第二期");

        ContractDO updated = contractMapper.selectById(contract.getId());
        assertAmount("6000000.00", updated.getPaidAmount());
        assertEquals(1, paymentRecordMapper.selectListByContractId(contract.getId()).size());
        assertEquals(ContractStatusEnum.PERFORMING.getStatus(), updated.getStatus());
    }

    // ========== 3. 驳回回调 → 无流水、退回草稿 ==========

    @Test
    public void testHandleApprovalResult_reject_noRecordAndBackToDraft() {
        ContractDO contract = insertRunningContract("HT-AP-0021", "10000000.00",
                "3000000.00", 1, ContractStatusEnum.APPROVING);
        String processInstanceId = contract.getProcessInstanceId();

        // 调用：驳回
        contractApprovalService.handleApprovalResult(contract.getId(), processInstanceId, false, "资料不全");

        ContractDO updated = contractMapper.selectById(contract.getId());
        // 断言 1：审批状态 = 不通过(3)
        assertEquals(ContractApprovalStatusEnum.REJECT.getStatus(), updated.getApprovalStatus());
        // 断言 2：**不生成流水**
        assertEquals(0, paymentRecordMapper.selectListByContractId(contract.getId()).size());
        // 断言 3：**paid_amount 不变**
        assertAmount("0.00", updated.getPaidAmount());
        // 断言 4：合同退回草稿(0)——草稿才允许改金额/期次后重新提交
        assertEquals(ContractStatusEnum.DRAFT.getStatus(), updated.getStatus());
        // 断言 5：在途申请清空
        assertNull(updated.getPendingPaymentAmount());
        assertNull(updated.getPendingPeriod());
        // 断言 6：实例编号保留（审批历史可追溯）
        assertEquals(processInstanceId, updated.getProcessInstanceId());
    }

    @Test
    public void testHandleApprovalResult_reject_additionalPeriod_keepsPerforming() {
        // 履行中合同追加期次被驳回：**不回退业务状态**（否则已收过款的合同会变回可删除的草稿）
        ContractDO contract = insertRunningContract("HT-AP-0022", "10000000.00",
                "2000000.00", 2, ContractStatusEnum.PERFORMING);
        contractMapper.updatePaidAmount(contract.getId(), new BigDecimal("4000000.00"));

        contractApprovalService.handleApprovalResult(
                contract.getId(), contract.getProcessInstanceId(), false, "本期不予支付");

        ContractDO updated = contractMapper.selectById(contract.getId());
        assertEquals(ContractApprovalStatusEnum.REJECT.getStatus(), updated.getApprovalStatus());
        assertEquals(ContractStatusEnum.PERFORMING.getStatus(), updated.getStatus());
        assertAmount("4000000.00", updated.getPaidAmount());
        assertEquals(0, paymentRecordMapper.selectListByContractId(contract.getId()).size());
        assertNull(updated.getPendingPaymentAmount());
    }

    @Test
    public void testHandleApprovalResult_cancel_noRecordAndBackToDraft() {
        // 取消(4) 与驳回业务副作用相同（不生成流水），但 approval_status 落 4
        ContractDO contract = insertRunningContract("HT-AP-0023", "10000000.00",
                "3000000.00", 1, ContractStatusEnum.APPROVING);
        String processInstanceId = contract.getProcessInstanceId();

        contractApprovalService.handleApprovalResult(contract.getId(), processInstanceId, CANCEL.getStatus(), "发起人撤回");

        ContractDO updated = contractMapper.selectById(contract.getId());
        assertEquals(ContractApprovalStatusEnum.CANCEL.getStatus(), updated.getApprovalStatus());
        assertEquals(ContractStatusEnum.DRAFT.getStatus(), updated.getStatus());
        assertEquals(0, paymentRecordMapper.selectListByContractId(contract.getId()).size());
        assertAmount("0.00", updated.getPaidAmount());
    }

    // ========== 4. 重复回调幂等 ==========

    @Test
    public void testHandleApprovalResult_repeatApprove_isIdempotent() {
        // 这是有资金影响的幂等：重复回调**不能**重复生成流水、**不能**重复累加 paid_amount
        ContractDO contract = insertRunningContract("HT-AP-0031", "10000000.00",
                "3000000.00", 1, ContractStatusEnum.APPROVING);
        String processInstanceId = contract.getProcessInstanceId();

        // 第一次通过
        contractApprovalService.handleApprovalResult(contract.getId(), processInstanceId, true, "同意");
        // 第二次同样的通过回调（HTTP 重试 / 运维重放 / 多实例重复消费）
        contractApprovalService.handleApprovalResult(contract.getId(), processInstanceId, true, "同意");

        ContractDO updated = contractMapper.selectById(contract.getId());
        // 断言 1：流水仍只有 1 条
        assertEquals(1, paymentRecordMapper.selectListByContractId(contract.getId()).size());
        // 断言 2：paid_amount 仍是 300 万（没有被累加成 600 万）
        assertAmount("3000000.00", updated.getPaidAmount());
        assertEquals(ContractApprovalStatusEnum.APPROVE.getStatus(), updated.getApprovalStatus());
        assertEquals(ContractStatusEnum.PERFORMING.getStatus(), updated.getStatus());
    }

    @Test
    public void testHandleApprovalResult_thirdGuard_approvalStatusResetButRecordExists() {
        // 第三道幂等闸：假设 approval_status 被外部手工改回「审批中」（前两道闸失效），
        // 按 processInstanceId 查流水仍能找到 → 不再生成、不再累加，只把状态补回通过
        ContractDO contract = insertRunningContract("HT-AP-0032", "10000000.00",
                "3000000.00", 1, ContractStatusEnum.APPROVING);
        String processInstanceId = contract.getProcessInstanceId();
        contractApprovalService.handleApprovalResult(contract.getId(), processInstanceId, true, "同意");
        // 手工把审批状态重置为审批中、并重新塞回在途金额（模拟脏数据）
        ContractDO dirty = new ContractDO();
        dirty.setId(contract.getId());
        dirty.setApprovalStatus(ContractApprovalStatusEnum.RUNNING.getStatus());
        dirty.setPendingPaymentAmount(new BigDecimal("3000000.00"));
        contractMapper.updateById(dirty);

        contractApprovalService.handleApprovalResult(contract.getId(), processInstanceId, true, "同意");

        ContractDO updated = contractMapper.selectById(contract.getId());
        assertEquals(1, paymentRecordMapper.selectListByContractId(contract.getId()).size());
        assertAmount("3000000.00", updated.getPaidAmount());
        assertEquals(ContractApprovalStatusEnum.APPROVE.getStatus(), updated.getApprovalStatus());
        assertNull(updated.getPendingPaymentAmount());
    }

    @Test
    public void testHandleApprovalResult_staleProcessInstanceIgnored() {
        // 场景：合同驳回后重新发起新流程，旧实例的迟到回调到达 → 必须丢弃，不能按旧结果覆盖
        ContractDO contract = insertRunningContract("HT-AP-0033", "10000000.00",
                "3000000.00", 1, ContractStatusEnum.APPROVING);
        String currentProcessInstanceId = contract.getProcessInstanceId();
        String staleProcessInstanceId = "payment-approve:1:" + randomString();

        contractApprovalService.handleApprovalResult(contract.getId(), staleProcessInstanceId, true, "旧流程");

        ContractDO updated = contractMapper.selectById(contract.getId());
        assertEquals(ContractApprovalStatusEnum.RUNNING.getStatus(), updated.getApprovalStatus());
        assertEquals(ContractStatusEnum.APPROVING.getStatus(), updated.getStatus());
        assertEquals(0, paymentRecordMapper.selectListByContractId(contract.getId()).size());
        assertAmount("0.00", updated.getPaidAmount());
        assertEquals(currentProcessInstanceId, updated.getProcessInstanceId());
    }

    @Test
    public void testHandleApprovalResult_contractNotExists_ignored() {
        // 业务单据不存在：记日志丢弃，**不抛异常**（抛异常会让 bpm 侧 HTTP 调用失败并回滚流程结束事务）
        contractApprovalService.handleApprovalResult(999999L, "payment-approve:1:x", true, "同意");
        // 无异常即通过
        assertTrue(true);
    }

    @Test
    public void testHandleApprovalResult_approveWithoutPendingAmount_fails() {
        // 在途金额缺失（数据被外部改动）：抛错留痕，而不是造一条金额为 0 的假流水污染对账
        ContractDO contract = insertRunningContract("HT-AP-0034", "10000000.00",
                null, null, ContractStatusEnum.APPROVING);

        assertServiceException(() -> contractApprovalService.handleApprovalResult(
                contract.getId(), contract.getProcessInstanceId(), true, "同意"),
                CONTRACT_APPROVAL_PENDING_AMOUNT_MISSING, contract.getId());

        // 事务回滚：没有流水，状态仍在审批中
        assertEquals(0, paymentRecordMapper.selectListByContractId(contract.getId()).size());
        assertEquals(ContractApprovalStatusEnum.RUNNING.getStatus(),
                contractMapper.selectById(contract.getId()).getApprovalStatus());
    }

    // ========== 5. 非法状态提交被拒 ==========

    @Test
    public void testSubmitApproval_contractNotExists() {
        assertServiceException(() -> contractApprovalService.submitApproval(
                999999L, 1, new BigDecimal("1000000.00")), CONTRACT_NOT_EXISTS);
        // 合同不存在时不应调 BPM
        verify(processInstanceApi, never()).createProcessInstance(any(), any());
    }

    @Test
    public void testSubmitApproval_contractSettled_rejected() {
        // 已结算是终态，不能再产生收付款
        ContractDO contract = insertContract("HT-AP-0041", "10000000.00",
                ContractStatusEnum.SETTLED, ContractApprovalStatusEnum.APPROVE, null);

        assertServiceException(() -> contractApprovalService.submitApproval(
                contract.getId(), 1, new BigDecimal("1000000.00")),
                CONTRACT_APPROVAL_STATUS_NOT_ALLOWED, ContractStatusEnum.SETTLED.getName());

        verify(processInstanceApi, never()).createProcessInstance(any(), any());
        // 状态未被误改
        ContractDO unchanged = contractMapper.selectById(contract.getId());
        assertEquals(ContractStatusEnum.SETTLED.getStatus(), unchanged.getStatus());
        assertNull(unchanged.getPendingPaymentAmount());
    }

    @Test
    public void testSubmitApproval_contractVoided_rejected() {
        ContractDO contract = insertContract("HT-AP-0042", "10000000.00",
                ContractStatusEnum.VOIDED, ContractApprovalStatusEnum.NOT_START, null);

        assertServiceException(() -> contractApprovalService.submitApproval(
                contract.getId(), 1, new BigDecimal("1000000.00")),
                CONTRACT_APPROVAL_STATUS_NOT_ALLOWED, ContractStatusEnum.VOIDED.getName());
        verify(processInstanceApi, never()).createProcessInstance(any(), any());
    }

    @Test
    public void testSubmitApproval_alreadyRunning_rejected() {
        // 已有审批中的流程 → 拒绝重复提交（防同一合同并发两个实例）。
        // 注意这里用「草稿 + approvalStatus=审批中」的库内形态：submitApproval 会先把草稿置成
        // 审批中(1) 再调 Feign，因此这正是「提交过、但还没出结果」的真实数据形态。
        String existing = PROCESS_KEY_PAYMENT_APPROVE + ":1:" + randomString();
        ContractDO contract = insertContract("HT-AP-0043", "10000000.00",
                ContractStatusEnum.APPROVING, ContractApprovalStatusEnum.RUNNING, existing);

        assertServiceException(() -> contractApprovalService.submitApproval(
                contract.getId(), 1, new BigDecimal("1000000.00")),
                CONTRACT_APPROVAL_ALREADY_RUNNING, existing);

        verify(processInstanceApi, never()).createProcessInstance(any(), any());
    }

    @Test
    public void testSubmitApproval_amountExceedsRemaining_rejected() {
        // 合同 1000 万、已收 800 万 → 余额 200 万；申请 300 万必须被拒（否则 paid_amount 会超合同金额）
        ContractDO contract = insertContract("HT-AP-0044", "10000000.00",
                ContractStatusEnum.PERFORMING, ContractApprovalStatusEnum.APPROVE, null);
        contractMapper.updatePaidAmount(contract.getId(), new BigDecimal("8000000.00"));

        assertServiceException(() -> contractApprovalService.submitApproval(
                        contract.getId(), 3, new BigDecimal("3000000.00")), CONTRACT_APPROVAL_AMOUNT_INVALID,
                new BigDecimal("10000000.00"), new BigDecimal("8000000.00"), new BigDecimal("3000000.00"));
        verify(processInstanceApi, never()).createProcessInstance(any(), any());
        assertNull(contractMapper.selectById(contract.getId()).getPendingPaymentAmount());
    }

    @Test
    public void testSubmitApproval_amountZero_rejected() {
        ContractDO contract = insertContract("HT-AP-0045", "10000000.00",
                ContractStatusEnum.DRAFT, ContractApprovalStatusEnum.NOT_START, null);

        assertServiceException(() -> contractApprovalService.submitApproval(
                        contract.getId(), 1, BigDecimal.ZERO), CONTRACT_APPROVAL_AMOUNT_INVALID,
                new BigDecimal("10000000.00"), new BigDecimal("0.00"), BigDecimal.ZERO);
        verify(processInstanceApi, never()).createProcessInstance(any(), any());
    }

    @Test
    public void testSubmitApproval_afterRejectCanResubmit() {
        // 驳回是终态，允许改金额后重新发起（这是业务常态，不能被「防重复提交」挡住）
        ContractDO contract = insertContract("HT-AP-0046", "10000000.00",
                ContractStatusEnum.DRAFT, ContractApprovalStatusEnum.REJECT, "payment-approve:1:old");
        String newInstanceId = PROCESS_KEY_PAYMENT_APPROVE + ":2:" + randomString();
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(newInstanceId));

        String result = contractApprovalService.submitApproval(contract.getId(), 1, new BigDecimal("2500000.00"));

        assertEquals(newInstanceId, result);
        ContractDO updated = contractMapper.selectById(contract.getId());
        // 流程实例编号被覆盖为最新（老实例成为历史，可撤回）
        assertEquals(newInstanceId, updated.getProcessInstanceId());
        assertEquals(ContractApprovalStatusEnum.RUNNING.getStatus(), updated.getApprovalStatus());
        assertAmount("2500000.00", updated.getPendingPaymentAmount());
    }

    // ========== 6. 附加护栏 ==========

    @Test
    public void testApprovalStatusValuesAlignWithBpmEnum() {
        // 回归护栏：本域枚举与基座 BpmProcessInstanceStatusEnum 的数值必须一一对齐，
        // 否则回调时 approval_status 会写错位（这是最容易静默出错的点）
        assertEquals(APPROVE.getStatus(), ContractApprovalStatusEnum.APPROVE.getStatus());
        assertEquals(REJECT.getStatus(), ContractApprovalStatusEnum.REJECT.getStatus());
        assertEquals(CANCEL.getStatus(), ContractApprovalStatusEnum.CANCEL.getStatus());
        assertEquals(-1, ContractApprovalStatusEnum.NOT_START.getStatus());
        assertEquals(1, ContractApprovalStatusEnum.RUNNING.getStatus());
    }

    @Test
    public void testGetApprovalStatus_running() {
        String processInstanceId = PROCESS_KEY_PAYMENT_APPROVE + ":1:" + randomString();
        ContractDO contract = insertRunningContract("HT-AP-0051", "10000000.00",
                "3000000.00", 1, ContractStatusEnum.APPROVING, 1, processInstanceId);

        ContractApprovalStatusRespVO status = contractApprovalService.getApprovalStatus(contract.getId());

        assertEquals(contract.getId(), status.getContractId());
        assertEquals(processInstanceId, status.getProcessInstanceId());
        assertEquals(ContractApprovalStatusEnum.RUNNING.getStatus(), status.getApprovalStatus());
        assertEquals("审批中", status.getApprovalStatusName());
        assertEquals(ContractStatusEnum.APPROVING.getStatus(), status.getContractStatus());
        assertEquals("审批中", status.getContractStatusName());
        assertAmount("10000000.00", status.getContractAmount());
        assertAmount("0.00", status.getPaidAmount());
        assertAmount("3000000.00", status.getPendingPaymentAmount());
        assertEquals(1, status.getPendingPeriod());
        assertEquals("已提交审批，等待财务审批", status.getReason());
    }

    @Test
    public void testGetApprovalStatus_afterApprove() {
        ContractDO contract = insertRunningContract("HT-AP-0052", "10000000.00",
                "3000000.00", 1, ContractStatusEnum.APPROVING);
        contractApprovalService.handleApprovalResult(
                contract.getId(), contract.getProcessInstanceId(), true, "同意");

        ContractApprovalStatusRespVO status = contractApprovalService.getApprovalStatus(contract.getId());

        assertEquals("审批通过", status.getApprovalStatusName());
        assertEquals("履行中", status.getContractStatusName());
        assertAmount("3000000.00", status.getPaidAmount());
        assertNull(status.getPendingPaymentAmount());
        assertEquals("进度款审批已通过，已生成收付款流水并累加合同累计收付", status.getReason());
    }

    @Test
    public void testGetApprovalStatus_nullApprovalStatusFallback() {
        // 历史数据 approvalStatus 为 null 时兜底成「未开始」，不能让前端拿到 null
        ContractDO contract = insertContract("HT-AP-0053", "10000000.00",
                ContractStatusEnum.DRAFT, null, null);

        ContractApprovalStatusRespVO status = contractApprovalService.getApprovalStatus(contract.getId());

        assertEquals(ContractApprovalStatusEnum.NOT_START.getStatus(), status.getApprovalStatus());
        assertEquals("尚未提交进度款审批", status.getReason());
    }

    @Test
    public void testGetApprovalStatus_contractNotExists() {
        assertServiceException(() -> contractApprovalService.getApprovalStatus(999999L), CONTRACT_NOT_EXISTS);
    }

    // ========== 测试数据构造 ==========

    /**
     * 插入合同（通用）
     */
    private ContractDO insertContract(String contractNo, String amount, ContractStatusEnum status,
                                      ContractApprovalStatusEnum approvalStatus, String processInstanceId) {
        ContractDO contract = randomPojo(ContractDO.class, o -> {
            o.setId(null);
            o.setContractNo(contractNo);
            o.setContractName("测试合同-" + contractNo);
            o.setAmount(new BigDecimal(amount));
            o.setStatus(status.getStatus());
            o.setApprovalStatus(approvalStatus != null ? approvalStatus.getStatus() : null);
            o.setProcessInstanceId(processInstanceId);
            o.setDirection(1);
            o.setPaidAmount(BigDecimal.ZERO);
            o.setPendingPaymentAmount(null);
            o.setPendingPeriod(null);
        });
        contractMapper.insert(contract);
        return contract;
    }

    /**
     * 插入「已提交审批」的合同（审批中 + 在途金额 + 流程实例号），模拟 submitApproval 之后的库内状态
     */
    private ContractDO insertRunningContract(String contractNo, String amount, String pendingAmount,
                                             Integer pendingPeriod, ContractStatusEnum status) {
        return insertRunningContract(contractNo, amount, pendingAmount, pendingPeriod, status, 1,
                PROCESS_KEY_PAYMENT_APPROVE + ":1:" + randomString());
    }

    private ContractDO insertRunningContract(String contractNo, String amount, String pendingAmount,
                                             Integer pendingPeriod, ContractStatusEnum status, Integer direction) {
        return insertRunningContract(contractNo, amount, pendingAmount, pendingPeriod, status, direction,
                PROCESS_KEY_PAYMENT_APPROVE + ":1:" + randomString());
    }

    private ContractDO insertRunningContract(String contractNo, String amount, String pendingAmount,
                                             Integer pendingPeriod, ContractStatusEnum status,
                                             Integer direction, String processInstanceId) {
        ContractDO contract = insertContract(contractNo, amount, status,
                ContractApprovalStatusEnum.RUNNING, processInstanceId);
        contract.setDirection(direction);
        contract.setPendingPaymentAmount(pendingAmount != null ? new BigDecimal(pendingAmount) : null);
        contract.setPendingPeriod(pendingPeriod);
        contractMapper.updateById(contract);
        return contractMapper.selectById(contract.getId());
    }

    /**
     * 金额断言：只比数值不比 scale（H2 decimal(18,2) 与 BigDecimal 的 scale 可能不同）
     */
    private void assertAmount(String expected, BigDecimal actual) {
        assertNotNull(actual, "金额不应为 null");
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                "金额不匹配：期望 " + expected + "，实际 " + actual);
    }

}
