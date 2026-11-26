package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentPlanDO;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.dal.mysql.contract.ContractPaymentPlanMapper;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_PLAN_AMOUNT_EXCEED;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_PLAN_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_PLAN_PERIOD_DUPLICATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ContractPaymentPlanServiceImpl} 的单元测试类
 *
 * 覆盖（spec §5.1「计划编制」）：按期次 CRUD、期次唯一（PAYMENT_PLAN_PERIOD_DUPLICATE）、
 * 计划金额合计不得超过合同金额（PAYMENT_PLAN_AMOUNT_EXCEED，含「更新时排除自身」的反向验证）。
 *
 * @author buildcloud
 */
@Import({ContractPaymentPlanServiceImpl.class})
@Sql(scripts = "/sql/contract-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class ContractPaymentPlanServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ContractPaymentPlanServiceImpl paymentPlanService;

    @Resource
    private ContractPaymentPlanMapper paymentPlanMapper;

    @Resource
    private ContractMapper contractMapper;

    // ========== 创建 ==========

    @Test
    public void testCreatePaymentPlan_success() {
        ContractDO contract = insertContract("HT-PLAN-0001", "10000000.00");
        ContractPaymentPlanSaveReqVO createReqVO = buildPlanReqVO(contract.getId(), 1, "3000000.00");

        Long planId = paymentPlanService.createPaymentPlan(createReqVO);

        assertNotNull(planId);
        ContractPaymentPlanDO plan = paymentPlanMapper.selectById(planId);
        assertPojoEquals(createReqVO, plan, "id");
    }

    @Test
    public void testCreatePaymentPlan_contractNotExists() {
        ContractPaymentPlanSaveReqVO createReqVO = buildPlanReqVO(999999L, 1, "1000.00");

        assertServiceException(() -> paymentPlanService.createPaymentPlan(createReqVO), CONTRACT_NOT_EXISTS);
    }

    @Test
    public void testCreatePaymentPlan_periodDuplicate() {
        // 同一合同同期次第二笔应被拒
        ContractDO contract = insertContract("HT-PLAN-0002", "10000000.00");
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 1, "1000000.00"));
        ContractPaymentPlanSaveReqVO second = buildPlanReqVO(contract.getId(), 1, "2000000.00");

        assertServiceException(() -> paymentPlanService.createPaymentPlan(second),
                PAYMENT_PLAN_PERIOD_DUPLICATE);
    }

    @Test
    public void testCreatePaymentPlan_amountExceed() {
        // 合同 1000 万，已编 900 万，再编 200 万 → 合计 1100 万 > 1000 万
        ContractDO contract = insertContract("HT-PLAN-0003", "10000000.00");
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 1, "9000000.00"));
        ContractPaymentPlanSaveReqVO exceed = buildPlanReqVO(contract.getId(), 2, "2000000.00");

        assertServiceException(() -> paymentPlanService.createPaymentPlan(exceed), PAYMENT_PLAN_AMOUNT_EXCEED);
    }

    @Test
    public void testCreatePaymentPlan_amountEqualsContractAmount_ok() {
        // 边界：合计恰好等于合同金额应放行（只有「超过」才拒）
        ContractDO contract = insertContract("HT-PLAN-0004", "10000000.00");
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 1, "6000000.00"));

        Long planId = paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 2, "4000000.00"));

        assertNotNull(planId);
        assertEquals(0, paymentPlanMapper.selectPlanAmountSum(contract.getId(), null)
                .compareTo(new BigDecimal("10000000.00")));
    }

    // ========== 更新 ==========

    @Test
    public void testUpdatePaymentPlan_success() {
        ContractDO contract = insertContract("HT-PLAN-0011", "10000000.00");
        Long planId = paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 1, "3000000.00"));
        // 更新：金额调到 500 万（合同 1000 万，仍不超）
        ContractPaymentPlanSaveReqVO updateReqVO = buildPlanReqVO(contract.getId(), 1, "5000000.00");
        updateReqVO.setId(planId);

        paymentPlanService.updatePaymentPlan(updateReqVO);

        ContractPaymentPlanDO plan = paymentPlanMapper.selectById(planId);
        assertEquals(0, plan.getPlanAmount().compareTo(new BigDecimal("5000000.00")));
    }

    @Test
    public void testUpdatePaymentPlan_excludeSelfOnAmountCheck() {
        // 关键回归：合同 1000 万，单笔计划 900 万再「原值改成 950 万」必须成功——
        // 校验时要把自身旧值排除，否则会算出 900+950=1850 万而误判超额。
        ContractDO contract = insertContract("HT-PLAN-0012", "10000000.00");
        Long planId = paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 1, "9000000.00"));
        ContractPaymentPlanSaveReqVO updateReqVO = buildPlanReqVO(contract.getId(), 1, "9500000.00");
        updateReqVO.setId(planId);

        paymentPlanService.updatePaymentPlan(updateReqVO);

        assertEquals(0, paymentPlanMapper.selectById(planId).getPlanAmount()
                .compareTo(new BigDecimal("9500000.00")));
    }

    @Test
    public void testUpdatePaymentPlan_amountExceed() {
        // 合同 1000 万，已编 600 万(期1) + 300 万(期2)，把期2改到 500 万 → 600+500=1100 > 1000
        ContractDO contract = insertContract("HT-PLAN-0013", "10000000.00");
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 1, "6000000.00"));
        Long planId2 = paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 2, "3000000.00"));
        ContractPaymentPlanSaveReqVO updateReqVO = buildPlanReqVO(contract.getId(), 2, "5000000.00");
        updateReqVO.setId(planId2);

        assertServiceException(() -> paymentPlanService.updatePaymentPlan(updateReqVO),
                PAYMENT_PLAN_AMOUNT_EXCEED);
    }

    @Test
    public void testUpdatePaymentPlan_notExists() {
        ContractDO contract = insertContract("HT-PLAN-0014", "10000000.00");
        ContractPaymentPlanSaveReqVO updateReqVO = buildPlanReqVO(contract.getId(), 1, "1000.00");
        updateReqVO.setId(999999L);

        assertServiceException(() -> paymentPlanService.updatePaymentPlan(updateReqVO),
                PAYMENT_PLAN_NOT_EXISTS);
    }

    // ========== 删除 ==========

    @Test
    public void testDeletePaymentPlan_success() {
        ContractDO contract = insertContract("HT-PLAN-0021", "10000000.00");
        Long planId = paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 1, "1000000.00"));

        paymentPlanService.deletePaymentPlan(planId);

        assertNull(paymentPlanMapper.selectById(planId));
        // 删除后计划合计回落，可以再编满额
        assertEquals(0, paymentPlanMapper.selectPlanAmountSum(contract.getId(), null).compareTo(BigDecimal.ZERO));
    }

    @Test
    public void testDeletePaymentPlan_notExists() {
        assertServiceException(() -> paymentPlanService.deletePaymentPlan(999999L), PAYMENT_PLAN_NOT_EXISTS);
    }

    // ========== 查询 ==========

    @Test
    public void testGetPaymentPlanPage_byContract() {
        ContractDO contract1 = insertContract("HT-PLAN-0031", "10000000.00");
        ContractDO contract2 = insertContract("HT-PLAN-0032", "10000000.00");
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract1.getId(), 1, "1000000.00"));
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract1.getId(), 2, "2000000.00"));
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract2.getId(), 1, "3000000.00"));

        ContractPaymentPlanPageReqVO reqVO = new ContractPaymentPlanPageReqVO();
        reqVO.setContractId(contract1.getId());

        var pageResult = paymentPlanService.getPaymentPlanPage(reqVO);
        assertEquals(2, pageResult.getTotal());
    }

    @Test
    public void testGetPaymentPlanListByContractId_orderedByPeriod() {
        ContractDO contract = insertContract("HT-PLAN-0033", "10000000.00");
        // 故意乱序插入
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 3, "1000000.00"));
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 1, "1000000.00"));
        paymentPlanService.createPaymentPlan(buildPlanReqVO(contract.getId(), 2, "1000000.00"));

        var list = paymentPlanService.getPaymentPlanListByContractId(contract.getId());

        assertEquals(3, list.size());
        assertEquals(1, list.get(0).getPeriod());
        assertEquals(2, list.get(1).getPeriod());
        assertEquals(3, list.get(2).getPeriod());
    }

    // ========== 辅助方法 ==========

    private ContractDO insertContract(String contractNo, String amount) {
        ContractDO contract = randomPojo(ContractDO.class, o -> {
            o.setId(null);
            o.setContractNo(contractNo);
            o.setContractName("测试合同-" + contractNo);
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

}
