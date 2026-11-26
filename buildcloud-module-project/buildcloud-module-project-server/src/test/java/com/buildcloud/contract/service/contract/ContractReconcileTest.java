package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangeSaveReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanSaveReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.enums.contract.ContractChangeTypeEnum;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import com.buildcloud.contract.enums.contract.PaymentRecordTypeEnum;
import com.buildcloud.contract.service.contract.bo.ContractReconcileBO;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 合同台账对账（{@link ContractServiceImpl#getReconcile}）的单元测试类
 *
 * 这是 Phase 3 的**核心业务接口**（spec §5.1「台账对账：计划 vs 实际差异」），
 * 断言的是「一条聚合 SQL + 派生计算」后的真实数字，避免只验结构不验算法。
 *
 * 主算例（{@link #testGetReconcile_allNumbers}）：
 * <pre>
 * 合同金额                        100,000,000.00
 * 计划：期1 30,000,000.00 + 期2 50,000,000.00  →  计划合计 80,000,000.00（2 期）
 * 收付：收 20,000,000.00 + 收 10,000,000.00 + 付 8,000,000.00
 *                                             →  收款合计 30,000,000.00、付款合计 8,000,000.00、
 *                                                实际合计 38,000,000.00（3 笔）
 * 变更：设计变更 +3,000,000.00                →  变更净额 3,000,000.00（1 笔）
 * 回写：paid_amount = 38,000,000.00（三笔流水登记时原子累加）
 *
 * 期望：
 *   变更后金额 = 100,000,000 + 3,000,000          = 103,000,000.00
 *   差异1（计划vs实际） = 80,000,000 - 38,000,000  =  42,000,000.00
 *   差异2（变更后vs合同） = 103,000,000 - 100,000,000 = 3,000,000.00
 *   双写一致性 = 38,000,000 - 38,000,000          =           0.00
 * </pre>
 *
 * @author buildcloud
 */
@Import({ContractServiceImpl.class, ContractPaymentPlanServiceImpl.class,
        ContractPaymentRecordServiceImpl.class, ContractChangeServiceImpl.class})
@Sql(scripts = "/sql/contract-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class ContractReconcileTest extends BaseDbUnitTest {

    @Resource
    private ContractServiceImpl contractService;

    @Resource
    private ContractPaymentPlanServiceImpl paymentPlanService;

    @Resource
    private ContractPaymentRecordServiceImpl paymentRecordService;

    @Resource
    private ContractChangeServiceImpl contractChangeService;

    @Resource
    private ContractMapper contractMapper;

    @Test
    public void testGetReconcile_allNumbers() {
        // ========== 造数：合同（履行中，1 亿） ==========
        ContractDO contract = insertContract("HT-RECON-0001", "100000000.00");
        Long contractId = contract.getId();

        // 造数：2 期收付款计划（3000 万 + 5000 万 = 8000 万）
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contractId, 1, "30000000.00"));
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contractId, 2, "50000000.00"));

        // 造数：3 笔实际收付（收 2000 万 + 收 1000 万 + 付 800 万）
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contractId, "PAY-RECON-0001", "20000000.00", PaymentRecordTypeEnum.RECEIPT));
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contractId, "PAY-RECON-0002", "10000000.00", PaymentRecordTypeEnum.RECEIPT));
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contractId, "PAY-RECON-0003", "8000000.00", PaymentRecordTypeEnum.PAYMENT));

        // 造数：1 笔变更签证（+300 万）
        contractChangeService.createContractChange(buildChangeReqVO(contractId, "3000000.00"));

        // ========== 调用对账 ==========
        ContractReconcileBO reconcile = contractService.getReconcile(contractId);

        // ========== 断言基础信息 ==========
        assertNotNull(reconcile);
        assertEquals(contractId, reconcile.getContractId());
        assertEquals("HT-RECON-0001", reconcile.getContractNo());
        assertEquals(ContractStatusEnum.PERFORMING.getStatus(), reconcile.getStatus());
        assertEquals(1, reconcile.getDirection());

        // ========== 断言原始口径数字 ==========
        assertAmount("100000000.00", reconcile.getContractAmount(), "合同金额");
        assertAmount("80000000.00", reconcile.getPlanAmountTotal(), "计划合计");
        assertEquals(2, reconcile.getPlanCount(), "计划期数");
        assertAmount("30000000.00", reconcile.getReceiptAmountTotal(), "实际收款合计");
        assertAmount("8000000.00", reconcile.getPaymentAmountTotal(), "实际付款合计");
        assertEquals(3, reconcile.getRecordCount(), "流水笔数");
        assertAmount("3000000.00", reconcile.getChangeAmountTotal(), "变更净额");
        assertEquals(1, reconcile.getChangeCount(), "变更笔数");

        // ========== 断言派生口径数字（核心） ==========
        assertAmount("38000000.00", reconcile.getActualAmountTotal(), "实际收付合计 = 3000万 + 800万");
        assertAmount("103000000.00", reconcile.getChangedContractAmount(), "变更后金额 = 1亿 + 300万");
        assertAmount("42000000.00", reconcile.getPlanActualDiff(), "差异1 = 8000万 - 3800万");
        assertAmount("3000000.00", reconcile.getContractChangedDiff(), "差异2 = 1.03亿 - 1亿");
        assertAmount("0.00", reconcile.getPaidAmountDiff(), "双写一致性差异（应为 0）");

        // 冗余字段 paid_amount 应等于流水合计（原子累加的结果）
        assertAmount("38000000.00", reconcile.getPaidAmount(), "合同累计已收付");
    }

    @Test
    public void testGetReconcile_negativeChange() {
        // 减项场景：变更净额为负 → 变更后金额 < 合同金额，差异2 为负数
        ContractDO contract = insertContract("HT-RECON-0002", "10000000.00");
        Long contractId = contract.getId();
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contractId, 1, "10000000.00"));
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contractId, null, "4000000.00", PaymentRecordTypeEnum.RECEIPT));
        contractChangeService.createContractChange(buildChangeReqVO(contractId, "-1500000.00"));

        ContractReconcileBO reconcile = contractService.getReconcile(contractId);

        assertAmount("-1500000.00", reconcile.getChangeAmountTotal(), "变更净额（负数）");
        assertAmount("8500000.00", reconcile.getChangedContractAmount(), "变更后金额 = 1000万 - 150万");
        assertAmount("-1500000.00", reconcile.getContractChangedDiff(), "差异2（负数）");
        assertAmount("6000000.00", reconcile.getPlanActualDiff(), "差异1 = 1000万 - 400万");
        assertAmount("0.00", reconcile.getPaidAmountDiff(), "双写一致性差异");
    }

    @Test
    public void testGetReconcile_emptySubTables_allZero() {
        // 无计划 / 无流水 / 无变更：所有子口径应为 0，不能是 null（COALESCE 兜底），
        // 否则前端做减法会 NPE。
        ContractDO contract = insertContract("HT-RECON-0003", "5000000.00");

        ContractReconcileBO reconcile = contractService.getReconcile(contract.getId());

        assertAmount("5000000.00", reconcile.getContractAmount(), "合同金额");
        assertAmount("0.00", reconcile.getPlanAmountTotal(), "计划合计");
        assertEquals(0, reconcile.getPlanCount(), "计划期数");
        assertAmount("0.00", reconcile.getReceiptAmountTotal(), "收款合计");
        assertAmount("0.00", reconcile.getPaymentAmountTotal(), "付款合计");
        assertEquals(0, reconcile.getRecordCount(), "流水笔数");
        assertAmount("0.00", reconcile.getChangeAmountTotal(), "变更净额");
        assertEquals(0, reconcile.getChangeCount(), "变更笔数");
        assertAmount("0.00", reconcile.getActualAmountTotal(), "实际合计");
        assertAmount("5000000.00", reconcile.getChangedContractAmount(), "变更后金额 = 合同金额");
        assertAmount("0.00", reconcile.getPlanActualDiff(), "差异1");
        assertAmount("0.00", reconcile.getContractChangedDiff(), "差异2");
        assertAmount("0.00", reconcile.getPaidAmountDiff(), "双写一致性差异");
    }

    @Test
    public void testGetReconcile_contractNotExists() {
        assertServiceException(() -> contractService.getReconcile(999999L), CONTRACT_NOT_EXISTS);
    }

    // ========== 辅助方法 ==========

    private ContractDO insertContract(String contractNo, String amount) {
        ContractDO contract = randomPojo(ContractDO.class, o -> {
            o.setId(null);
            o.setContractNo(contractNo);
            o.setContractName("对账测试合同-" + contractNo);
            o.setAmount(new BigDecimal(amount));
            o.setStatus(ContractStatusEnum.PERFORMING.getStatus());
            o.setDirection(1);
            o.setPaidAmount(BigDecimal.ZERO);
        });
        contractMapper.insert(contract);
        return contract;
    }

    private ContractPaymentPlanSaveReqVO buildPlanReqVO(Long contractId, Integer period, String planAmount) {
        ContractPaymentPlanSaveReqVO reqVO = new ContractPaymentPlanSaveReqVO();
        reqVO.setContractId(contractId);
        reqVO.setPeriod(period);
        reqVO.setPlanAmount(new BigDecimal(planAmount));
        reqVO.setPlanDate(LocalDate.of(2027, period, 1));
        reqVO.setStatus(0);
        return reqVO;
    }

    private ContractPaymentRecordSaveReqVO buildRecordReqVO(Long contractId, String approvalNo,
                                                            String amount, PaymentRecordTypeEnum type) {
        ContractPaymentRecordSaveReqVO reqVO = new ContractPaymentRecordSaveReqVO();
        reqVO.setContractId(contractId);
        reqVO.setApprovalNo(approvalNo);
        reqVO.setAmount(new BigDecimal(amount));
        reqVO.setPayDate(LocalDate.of(2026, 12, 15));
        reqVO.setType(type.getType());
        return reqVO;
    }

    private ContractChangeSaveReqVO buildChangeReqVO(Long contractId, String changeAmount) {
        ContractChangeSaveReqVO reqVO = new ContractChangeSaveReqVO();
        reqVO.setContractId(contractId);
        reqVO.setType(ContractChangeTypeEnum.DESIGN_CHANGE.getType());
        reqVO.setChangeAmount(new BigDecimal(changeAmount));
        reqVO.setChangeReason("对账测试变更");
        return reqVO;
    }

    private void assertAmount(String expected, BigDecimal actual, String field) {
        assertNotNull(actual, field + " 不应为 null");
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                field + " 金额不匹配：期望 " + expected + "，实际 " + actual);
    }

}
