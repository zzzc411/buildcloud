package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentRecordDO;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.dal.mysql.contract.ContractPaymentRecordMapper;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import com.buildcloud.contract.enums.contract.PaymentRecordTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EFFECTIVE;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_RECORD_APPROVAL_DUPLICATE;
import static com.buildcloud.contract.enums.ErrorCodeConstants.PAYMENT_RECORD_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ContractPaymentRecordServiceImpl} 的单元测试类
 *
 * 重点覆盖 **paid_amount 原子累加回写**（spec §5.1「通过后生成 payment_record 并回写合同累计收付」）：
 * 登记 +amount、更新按差额、删除 -amount，三个方向都断言合同表 paid_amount 的实际数值。
 *
 * @author buildcloud
 */
@Import({ContractPaymentRecordServiceImpl.class})
@Sql(scripts = "/sql/contract-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class ContractPaymentRecordServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ContractPaymentRecordServiceImpl paymentRecordService;

    @Resource
    private ContractPaymentRecordMapper paymentRecordMapper;

    @Resource
    private ContractMapper contractMapper;

    // ========== 登记 ==========

    @Test
    public void testCreatePaymentRecord_success_andPaidAmountAccumulated() {
        // 合同履行中，初始 paid_amount = 0
        ContractDO contract = insertContract("HT-REC-0001", "10000000.00", ContractStatusEnum.PERFORMING);
        ContractPaymentRecordSaveReqVO createReqVO = buildRecordReqVO(
                contract.getId(), "PAY-20261127-0001", "3000000.00", PaymentRecordTypeEnum.RECEIPT);

        Long recordId = paymentRecordService.createPaymentRecord(createReqVO);

        // 1) 流水落库正确
        assertNotNull(recordId);
        ContractPaymentRecordDO record = paymentRecordMapper.selectById(recordId);
        assertPojoEquals(createReqVO, record, "id");
        // 2) 合同累计收付被原子累加：0 + 3000000 = 3000000
        assertAmount("3000000.00", contractMapper.selectById(contract.getId()).getPaidAmount());
    }

    @Test
    public void testCreatePaymentRecord_accumulateTwice() {
        // 连续两笔：累加而不是覆盖（验证是 += 而非 set）
        ContractDO contract = insertContract("HT-REC-0002", "10000000.00", ContractStatusEnum.PERFORMING);
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-000001", "3000000.00", PaymentRecordTypeEnum.RECEIPT));
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-000002", "1500000.50", PaymentRecordTypeEnum.PAYMENT));

        assertAmount("4500000.50", contractMapper.selectById(contract.getId()).getPaidAmount());
    }

    @Test
    public void testCreatePaymentRecord_contractNotEffective() {
        // 草稿状态的合同不允许登记收付款
        ContractDO contract = insertContract("HT-REC-0003", "10000000.00", ContractStatusEnum.DRAFT);
        ContractPaymentRecordSaveReqVO createReqVO = buildRecordReqVO(
                contract.getId(), null, "1000000.00", PaymentRecordTypeEnum.RECEIPT);

        assertServiceException(() -> paymentRecordService.createPaymentRecord(createReqVO),
                CONTRACT_NOT_EFFECTIVE);
        // 失败时不得回写
        assertAmount("0.00", contractMapper.selectById(contract.getId()).getPaidAmount());
    }

    @Test
    public void testCreatePaymentRecord_contractNotExists() {
        ContractPaymentRecordSaveReqVO createReqVO = buildRecordReqVO(
                999999L, null, "1000000.00", PaymentRecordTypeEnum.RECEIPT);

        assertServiceException(() -> paymentRecordService.createPaymentRecord(createReqVO),
                CONTRACT_NOT_EXISTS);
    }

    @Test
    public void testCreatePaymentRecord_approvalNoDuplicate() {
        // 审批回调幂等：同一审批单号第二笔应被拒
        ContractDO contract = insertContract("HT-REC-0004", "10000000.00", ContractStatusEnum.PERFORMING);
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-DUP-0001", "1000000.00", PaymentRecordTypeEnum.RECEIPT));
        ContractPaymentRecordSaveReqVO second = buildRecordReqVO(
                contract.getId(), "PAY-DUP-0001", "2000000.00", PaymentRecordTypeEnum.RECEIPT);

        assertServiceException(() -> paymentRecordService.createPaymentRecord(second),
                PAYMENT_RECORD_APPROVAL_DUPLICATE);
        // 被拒的那笔不能影响累计收付
        assertAmount("1000000.00", contractMapper.selectById(contract.getId()).getPaidAmount());
    }

    @Test
    public void testCreatePaymentRecord_nullApprovalNoTwice_ok() {
        // 手工补录（approval_no 为空）不受审批幂等限制，可以登记多笔
        ContractDO contract = insertContract("HT-REC-0005", "10000000.00", ContractStatusEnum.PERFORMING);
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), null, "1000000.00", PaymentRecordTypeEnum.RECEIPT));
        Long secondId = paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), null, "2000000.00", PaymentRecordTypeEnum.RECEIPT));

        assertNotNull(secondId);
        assertAmount("3000000.00", contractMapper.selectById(contract.getId()).getPaidAmount());
    }

    // ========== 更新 ==========

    @Test
    public void testUpdatePaymentRecord_increaseAmount_deltaApplied() {
        // 300 万改成 500 万 → paid_amount 应 +200 万
        ContractDO contract = insertContract("HT-REC-0011", "10000000.00", ContractStatusEnum.PERFORMING);
        Long recordId = paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-UPD-0001", "3000000.00", PaymentRecordTypeEnum.RECEIPT));
        ContractPaymentRecordSaveReqVO updateReqVO = buildRecordReqVO(
                contract.getId(), "PAY-UPD-0001", "5000000.00", PaymentRecordTypeEnum.RECEIPT);
        updateReqVO.setId(recordId);

        paymentRecordService.updatePaymentRecord(updateReqVO);

        assertAmount("5000000.00", contractMapper.selectById(contract.getId()).getPaidAmount());
    }

    @Test
    public void testUpdatePaymentRecord_decreaseAmount_deltaNegative() {
        // 500 万改成 200 万 → paid_amount 应 -300 万
        ContractDO contract = insertContract("HT-REC-0012", "10000000.00", ContractStatusEnum.PERFORMING);
        Long recordId = paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-UPD-0002", "5000000.00", PaymentRecordTypeEnum.RECEIPT));
        ContractPaymentRecordSaveReqVO updateReqVO = buildRecordReqVO(
                contract.getId(), "PAY-UPD-0002", "2000000.00", PaymentRecordTypeEnum.RECEIPT);
        updateReqVO.setId(recordId);

        paymentRecordService.updatePaymentRecord(updateReqVO);

        assertAmount("2000000.00", contractMapper.selectById(contract.getId()).getPaidAmount());
    }

    @Test
    public void testUpdatePaymentRecord_notExists() {
        ContractDO contract = insertContract("HT-REC-0013", "10000000.00", ContractStatusEnum.PERFORMING);
        ContractPaymentRecordSaveReqVO updateReqVO = buildRecordReqVO(
                contract.getId(), null, "1000000.00", PaymentRecordTypeEnum.RECEIPT);
        updateReqVO.setId(999999L);

        assertServiceException(() -> paymentRecordService.updatePaymentRecord(updateReqVO),
                PAYMENT_RECORD_NOT_EXISTS);
    }

    // ========== 删除 ==========

    @Test
    public void testDeletePaymentRecord_success_paidAmountRollback() {
        // 删除流水要冲减 paid_amount，保证 paid_amount 与流水之和一致
        ContractDO contract = insertContract("HT-REC-0021", "10000000.00", ContractStatusEnum.PERFORMING);
        Long recordId = paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-DEL-0001", "3000000.00", PaymentRecordTypeEnum.RECEIPT));
        assertAmount("3000000.00", contractMapper.selectById(contract.getId()).getPaidAmount());

        paymentRecordService.deletePaymentRecord(recordId);

        assertNull(paymentRecordMapper.selectById(recordId));
        assertAmount("0.00", contractMapper.selectById(contract.getId()).getPaidAmount());
    }

    @Test
    public void testDeletePaymentRecord_notExists() {
        assertServiceException(() -> paymentRecordService.deletePaymentRecord(999999L),
                PAYMENT_RECORD_NOT_EXISTS);
    }

    // ========== 查询 ==========

    @Test
    public void testGetPaymentRecordPage_byContractAndType() {
        ContractDO contract = insertContract("HT-REC-0031", "10000000.00", ContractStatusEnum.PERFORMING);
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-PAGE-0001", "1000000.00", PaymentRecordTypeEnum.RECEIPT));
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-PAGE-0002", "2000000.00", PaymentRecordTypeEnum.PAYMENT));

        ContractPaymentRecordPageReqVO reqVO = new ContractPaymentRecordPageReqVO();
        reqVO.setContractId(contract.getId());
        reqVO.setType(PaymentRecordTypeEnum.RECEIPT.getType());

        var pageResult = paymentRecordService.getPaymentRecordPage(reqVO);
        assertEquals(1, pageResult.getTotal());
        assertEquals("PAY-PAGE-0001", pageResult.getList().get(0).getApprovalNo());
    }

    @Test
    public void testGetPaymentRecordListByContractId() {
        ContractDO contract = insertContract("HT-REC-0032", "10000000.00", ContractStatusEnum.PERFORMING);
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-LIST-0001", "1000000.00", PaymentRecordTypeEnum.RECEIPT));
        paymentRecordService.createPaymentRecord(buildRecordReqVO(
                contract.getId(), "PAY-LIST-0002", "2000000.00", PaymentRecordTypeEnum.PAYMENT));

        var list = paymentRecordService.getPaymentRecordListByContractId(contract.getId());

        assertEquals(2, list.size());
    }

    // ========== 辅助方法 ==========

    private ContractDO insertContract(String contractNo, String amount, ContractStatusEnum status) {
        ContractDO contract = randomPojo(ContractDO.class, o -> {
            o.setId(null);
            o.setContractNo(contractNo);
            o.setContractName("测试合同-" + contractNo);
            o.setAmount(new BigDecimal(amount));
            o.setStatus(status.getStatus());
            o.setDirection(1);
            o.setPaidAmount(BigDecimal.ZERO);
        });
        contractMapper.insert(contract);
        return contract;
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

    /**
     * 金额断言：只比数值不比 scale（H2 decimal(18,2) 与 BigDecimal 的 scale 可能不同）
     */
    private void assertAmount(String expected, BigDecimal actual) {
        assertNotNull(actual, "金额不应为 null");
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                "金额不匹配：期望 " + expected + "，实际 " + actual);
    }

}
