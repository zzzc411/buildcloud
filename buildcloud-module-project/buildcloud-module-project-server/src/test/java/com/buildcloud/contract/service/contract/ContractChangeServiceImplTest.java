package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangePageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangeSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractChangeDO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.mysql.contract.ContractChangeMapper;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.enums.contract.ContractChangeTypeEnum;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_CHANGE_AMOUNT_ZERO;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_CHANGE_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ContractChangeServiceImpl} 的单元测试类
 *
 * 重点：change_amount **可负不可 0**（ER 图 §3.2 设计取舍），且变更不改写合同原金额。
 *
 * @author buildcloud
 */
@Import({ContractChangeServiceImpl.class})
@Sql(scripts = "/sql/contract-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class ContractChangeServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ContractChangeServiceImpl contractChangeService;

    @Resource
    private ContractChangeMapper contractChangeMapper;

    @Resource
    private ContractMapper contractMapper;

    // ========== 创建 ==========

    @Test
    public void testCreateContractChange_positiveAmount() {
        // 增项：设计变更 +500 万
        ContractDO contract = insertContract("HT-CHG-0001", "100000000.00");
        ContractChangeSaveReqVO createReqVO = buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.DESIGN_CHANGE, "5000000.00");

        Long changeId = contractChangeService.createContractChange(createReqVO);

        assertNotNull(changeId);
        ContractChangeDO change = contractChangeMapper.selectById(changeId);
        assertPojoEquals(createReqVO, change, "id");
        // 变更不改写合同原金额（原金额是签约事实）
        assertAmount("100000000.00", contractMapper.selectById(contract.getId()).getAmount());
    }

    @Test
    public void testCreateContractChange_negativeAmount() {
        // 减项：核减 -200 万，带符号并存
        ContractDO contract = insertContract("HT-CHG-0002", "100000000.00");
        ContractChangeSaveReqVO createReqVO = buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.SITE_VISA, "-2000000.00");

        Long changeId = contractChangeService.createContractChange(createReqVO);

        ContractChangeDO change = contractChangeMapper.selectById(changeId);
        assertAmount("-2000000.00", change.getChangeAmount());
        // 净额 SUM 也应带符号
        assertAmount("-2000000.00", contractChangeMapper.selectChangeAmountSum(contract.getId()));
    }

    @Test
    public void testCreateContractChange_amountZero() {
        // 0 值无业务含义：增项/减项都表达不出来，拒绝
        ContractDO contract = insertContract("HT-CHG-0003", "100000000.00");
        ContractChangeSaveReqVO createReqVO = buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.CLAIM, "0.00");

        assertServiceException(() -> contractChangeService.createContractChange(createReqVO),
                CONTRACT_CHANGE_AMOUNT_ZERO);
    }

    @Test
    public void testCreateContractChange_contractNotExists() {
        ContractChangeSaveReqVO createReqVO = buildChangeReqVO(
                999999L, ContractChangeTypeEnum.DESIGN_CHANGE, "1000000.00");

        assertServiceException(() -> contractChangeService.createContractChange(createReqVO),
                CONTRACT_NOT_EXISTS);
    }

    // ========== 更新 ==========

    @Test
    public void testUpdateContractChange_success() {
        ContractDO contract = insertContract("HT-CHG-0011", "100000000.00");
        Long changeId = contractChangeService.createContractChange(buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.DESIGN_CHANGE, "5000000.00"));
        ContractChangeSaveReqVO updateReqVO = buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.CLAIM, "-1000000.00");
        updateReqVO.setId(changeId);

        contractChangeService.updateContractChange(updateReqVO);

        ContractChangeDO change = contractChangeMapper.selectById(changeId);
        assertEquals(ContractChangeTypeEnum.CLAIM.getType(), change.getType());
        assertAmount("-1000000.00", change.getChangeAmount());
    }

    @Test
    public void testUpdateContractChange_amountZero() {
        ContractDO contract = insertContract("HT-CHG-0012", "100000000.00");
        Long changeId = contractChangeService.createContractChange(buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.DESIGN_CHANGE, "5000000.00"));
        ContractChangeSaveReqVO updateReqVO = buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.DESIGN_CHANGE, "0.00");
        updateReqVO.setId(changeId);

        assertServiceException(() -> contractChangeService.updateContractChange(updateReqVO),
                CONTRACT_CHANGE_AMOUNT_ZERO);
    }

    @Test
    public void testUpdateContractChange_notExists() {
        ContractDO contract = insertContract("HT-CHG-0013", "100000000.00");
        ContractChangeSaveReqVO updateReqVO = buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.OTHER, "1000.00");
        updateReqVO.setId(999999L);

        assertServiceException(() -> contractChangeService.updateContractChange(updateReqVO),
                CONTRACT_CHANGE_NOT_EXISTS);
    }

    // ========== 删除 ==========

    @Test
    public void testDeleteContractChange_success() {
        ContractDO contract = insertContract("HT-CHG-0021", "100000000.00");
        Long changeId = contractChangeService.createContractChange(buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.DESIGN_CHANGE, "5000000.00"));

        contractChangeService.deleteContractChange(changeId);

        assertNull(contractChangeMapper.selectById(changeId));
    }

    @Test
    public void testDeleteContractChange_notExists() {
        assertServiceException(() -> contractChangeService.deleteContractChange(999999L),
                CONTRACT_CHANGE_NOT_EXISTS);
    }

    // ========== 查询 ==========

    @Test
    public void testGetContractChangePage_byContractAndType() {
        ContractDO contract = insertContract("HT-CHG-0031", "100000000.00");
        contractChangeService.createContractChange(buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.DESIGN_CHANGE, "5000000.00"));
        contractChangeService.createContractChange(buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.CLAIM, "1000000.00"));

        ContractChangePageReqVO reqVO = new ContractChangePageReqVO();
        reqVO.setContractId(contract.getId());
        reqVO.setType(ContractChangeTypeEnum.CLAIM.getType());

        var pageResult = contractChangeService.getContractChangePage(reqVO);
        assertEquals(1, pageResult.getTotal());
        assertAmount("1000000.00", pageResult.getList().get(0).getChangeAmount());
    }

    @Test
    public void testGetContractChangeListByContractId() {
        ContractDO contract = insertContract("HT-CHG-0032", "100000000.00");
        contractChangeService.createContractChange(buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.DESIGN_CHANGE, "5000000.00"));
        contractChangeService.createContractChange(buildChangeReqVO(
                contract.getId(), ContractChangeTypeEnum.CLAIM, "-1000000.00"));

        var list = contractChangeService.getContractChangeListByContractId(contract.getId());

        assertEquals(2, list.size());
        // 净额 = +500 万 -100 万 = +400 万
        assertAmount("4000000.00", contractChangeMapper.selectChangeAmountSum(contract.getId()));
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

    private ContractChangeSaveReqVO buildChangeReqVO(Long contractId, ContractChangeTypeEnum type,
                                                     String changeAmount) {
        ContractChangeSaveReqVO reqVO = new ContractChangeSaveReqVO();
        reqVO.setContractId(contractId);
        reqVO.setType(type.getType());
        reqVO.setChangeAmount(new BigDecimal(changeAmount));
        reqVO.setChangeReason("测试变更原因");
        return reqVO;
    }

    private void assertAmount(String expected, BigDecimal actual) {
        assertNotNull(actual, "金额不应为 null");
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                "金额不匹配：期望 " + expected + "，实际 " + actual);
    }

}
