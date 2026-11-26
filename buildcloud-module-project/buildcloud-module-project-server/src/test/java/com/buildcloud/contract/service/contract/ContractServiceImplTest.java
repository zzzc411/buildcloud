package com.buildcloud.contract.service.contract;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.contract.controller.admin.contract.vo.ContractPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.ContractSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.dal.mysql.contract.ContractMapper;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertPojoEquals;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomString;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_AMOUNT_INVALID;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_DRAFT;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NOT_EXISTS;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_NO_DUPLICATE;
import static com.buildcloud.contract.enums.ErrorCodeConstants.CONTRACT_STATUS_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ContractServiceImpl} 的单元测试类
 *
 * 风格照基座（与 ProjectServiceImplTest 一致）：继承 {@link BaseDbUnitTest}，@Import 被测 Service。
 *
 * **H2 建表用本类自带的 {@code /sql/contract-test-tables.sql}（@Sql BEFORE_TEST_METHOD）**：
 * 共享的 create_tables.sql / clean.sql 由 project 子域（W3-P2）统一维护，且在并行开发期不能互相踩，
 * 故合同 4 张表自备脚本。该脚本「先 DELETE 再 CREATE IF NOT EXISTS」，因此每方法前置都会先清
 * 合同表数据（BaseDbUnitTest 默认的 clean.sql 只清 bc_project），用例之间不串数据。
 *
 * @author buildcloud
 */
@Import({ContractServiceImpl.class})
@Sql(scripts = "/sql/contract-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class ContractServiceImplTest extends BaseDbUnitTest {

    @Resource
    private ContractServiceImpl contractService;

    @Resource
    private ContractMapper contractMapper;

    // ========== 创建 ==========

    @Test
    public void testCreateContract_success() {
        // 准备参数
        ContractSaveReqVO createReqVO = randomPojo(ContractSaveReqVO.class, o -> {
            o.setId(null);
            o.setContractNo(randomString());
            o.setAmount(new BigDecimal("120000000.00"));
            o.setDirection(1);
            o.setProjectId(100L);
        });

        // 调用
        Long contractId = contractService.createContract(createReqVO);
        // 断言：落库字段一致 + 状态固定为草稿 + 累计收付为 0
        assertNotNull(contractId);
        ContractDO contract = contractMapper.selectById(contractId);
        assertPojoEquals(createReqVO, contract, "id");
        assertEquals(ContractStatusEnum.DRAFT.getStatus(), contract.getStatus());
        assertEquals(0, contract.getPaidAmount().compareTo(BigDecimal.ZERO));
    }

    @Test
    public void testCreateContract_contractNoDuplicate() {
        // mock 数据：先插一条已有编号的合同
        // 注意：ContractDO 的 direction 是 tinyint，randomPojo 对非 type/status 结尾的 Integer 字段
        // 会生成全量随机 int（可能溢出 tinyint），故枚举型字段一律显式赋值。
        ContractDO dbContract = randomPojo(ContractDO.class, o -> {
            o.setId(null);
            o.setContractNo("HT-DUP-0001");
            o.setContractName("已存在的合同");
            o.setAmount(new BigDecimal("1000.00"));
            o.setDirection(1);
            o.setStatus(ContractStatusEnum.DRAFT.getStatus());
            o.setPaidAmount(new BigDecimal("0.00"));
        });
        contractMapper.insert(dbContract);
        // 准备参数：复用同编号
        ContractSaveReqVO createReqVO = randomPojo(ContractSaveReqVO.class, o -> {
            o.setId(null);
            o.setContractNo("HT-DUP-0001");
            o.setAmount(new BigDecimal("2000.00"));
            o.setDirection(1);
        });

        // 调用，并断言异常
        assertServiceException(() -> contractService.createContract(createReqVO), CONTRACT_NO_DUPLICATE);
    }

    @Test
    public void testCreateContract_amountInvalid() {
        // 准备参数：金额为 0（< 0.01），应被金额校验拦掉
        ContractSaveReqVO createReqVO = randomPojo(ContractSaveReqVO.class, o -> {
            o.setId(null);
            o.setContractNo(randomString());
            o.setAmount(BigDecimal.ZERO);
            o.setDirection(1);
        });

        // 调用，并断言异常
        assertServiceException(() -> contractService.createContract(createReqVO), CONTRACT_AMOUNT_INVALID);
    }

    // ========== 更新 ==========

    @Test
    public void testUpdateContract_success() {
        // mock 数据（草稿）
        ContractDO dbContract = insertDraftContract("HT-UPD-0001", "1000.00");
        // 准备参数
        ContractSaveReqVO updateReqVO = randomPojo(ContractSaveReqVO.class, o -> {
            o.setId(dbContract.getId());
            o.setContractNo(dbContract.getContractNo());
            o.setAmount(new BigDecimal("2000.00"));
            o.setDirection(2);
        });

        // 调用
        contractService.updateContract(updateReqVO);
        // 校验：字段已更新，状态与累计收付不受影响
        ContractDO contract = contractMapper.selectById(updateReqVO.getId());
        assertPojoEquals(updateReqVO, contract);
        assertEquals(ContractStatusEnum.DRAFT.getStatus(), contract.getStatus());
    }

    @Test
    public void testUpdateContract_notExists() {
        ContractSaveReqVO updateReqVO = randomPojo(ContractSaveReqVO.class, o -> {
            o.setId(999999L);
            o.setAmount(new BigDecimal("1000.00"));
            o.setDirection(1);
        });

        assertServiceException(() -> contractService.updateContract(updateReqVO), CONTRACT_NOT_EXISTS);
    }

    @Test
    public void testUpdateContract_notDraft() {
        // mock 数据：履行中的合同
        ContractDO dbContract = insertContract("HT-UPD-0002", "1000.00", ContractStatusEnum.PERFORMING);
        ContractSaveReqVO updateReqVO = randomPojo(ContractSaveReqVO.class, o -> {
            o.setId(dbContract.getId());
            o.setContractNo(dbContract.getContractNo());
            o.setAmount(new BigDecimal("3000.00"));
            o.setDirection(1);
        });

        // 非草稿不允许改字段
        assertServiceException(() -> contractService.updateContract(updateReqVO), CONTRACT_NOT_DRAFT);
    }

    // ========== 删除 ==========

    @Test
    public void testDeleteContract_success() {
        ContractDO dbContract = insertDraftContract("HT-DEL-0001", "1000.00");

        contractService.deleteContract(dbContract.getId());

        assertNull(contractMapper.selectById(dbContract.getId()));
    }

    @Test
    public void testDeleteContract_notDraft() {
        // 履行中的合同不允许删除（要作废走状态机）
        ContractDO dbContract = insertContract("HT-DEL-0002", "1000.00", ContractStatusEnum.PERFORMING);

        assertServiceException(() -> contractService.deleteContract(dbContract.getId()), CONTRACT_NOT_DRAFT);
        assertNotNull(contractMapper.selectById(dbContract.getId()));
    }

    @Test
    public void testDeleteContract_notExists() {
        assertServiceException(() -> contractService.deleteContract(999999L), CONTRACT_NOT_EXISTS);
    }

    @Test
    public void testDeleteContractList_notDraftRollback() {
        // 一条草稿 + 一条履行中：整体应因履行中而失败，且草稿那条不能被删掉
        ContractDO draft = insertDraftContract("HT-DEL-0003", "1000.00");
        ContractDO performing = insertContract("HT-DEL-0004", "1000.00", ContractStatusEnum.PERFORMING);

        assertServiceException(() -> contractService.deleteContractListByIds(
                List.of(draft.getId(), performing.getId())), CONTRACT_NOT_DRAFT);
        assertNotNull(contractMapper.selectById(draft.getId()));
        assertNotNull(contractMapper.selectById(performing.getId()));
    }

    // ========== 查询 ==========

    @Test
    public void testGetContract_success() {
        ContractDO dbContract = insertDraftContract("HT-GET-0001", "1000.00");

        ContractDO contract = contractService.getContract(dbContract.getId());

        assertPojoEquals(dbContract, contract);
    }

    @Test
    public void testGetContractPage_filterByProjectStatusDirection() {
        // mock 数据：命中（项目 1 / 履行中 / 收入）与三条不命中
        insertContractWithFilter("HT-PAGE-0001", 1L, ContractStatusEnum.PERFORMING, 1);
        insertContractWithFilter("HT-PAGE-0002", 2L, ContractStatusEnum.PERFORMING, 1);
        insertContractWithFilter("HT-PAGE-0003", 1L, ContractStatusEnum.DRAFT, 1);
        insertContractWithFilter("HT-PAGE-0004", 1L, ContractStatusEnum.PERFORMING, 2);

        // 准备参数：项目 + 状态 + 方向三条件组合
        ContractPageReqVO reqVO = new ContractPageReqVO();
        reqVO.setProjectId(1L);
        reqVO.setStatus(ContractStatusEnum.PERFORMING.getStatus());
        reqVO.setDirection(1);

        // 调用
        var pageResult = contractService.getContractPage(reqVO);
        // 断言：只剩一条
        assertEquals(1, pageResult.getTotal());
        assertEquals("HT-PAGE-0001", pageResult.getList().get(0).getContractNo());
    }

    // ========== 状态机（合法流转全覆盖） ==========

    @Test
    public void testUpdateContractStatus_fullPath() {
        // 草稿 → 审批中 → 履行中 → 已结算
        ContractDO contract = insertDraftContract("HT-ST-0001", "1000.00");

        contractService.updateContractStatus(contract.getId(), ContractStatusEnum.APPROVING.getStatus());
        assertEquals(ContractStatusEnum.APPROVING.getStatus(),
                contractMapper.selectById(contract.getId()).getStatus());

        contractService.updateContractStatus(contract.getId(), ContractStatusEnum.PERFORMING.getStatus());
        assertEquals(ContractStatusEnum.PERFORMING.getStatus(),
                contractMapper.selectById(contract.getId()).getStatus());

        contractService.updateContractStatus(contract.getId(), ContractStatusEnum.SETTLED.getStatus());
        assertEquals(ContractStatusEnum.SETTLED.getStatus(),
                contractMapper.selectById(contract.getId()).getStatus());
    }

    @Test
    public void testUpdateContractStatus_voidFromDraft() {
        // 旁路：草稿 → 已作废
        ContractDO contract = insertDraftContract("HT-ST-0002", "1000.00");

        contractService.updateContractStatus(contract.getId(), ContractStatusEnum.VOIDED.getStatus());

        assertEquals(ContractStatusEnum.VOIDED.getStatus(),
                contractMapper.selectById(contract.getId()).getStatus());
    }

    @Test
    public void testUpdateContractStatus_voidFromApproving() {
        // 旁路：审批中 → 已作废
        ContractDO contract = insertContract("HT-ST-0003", "1000.00", ContractStatusEnum.APPROVING);

        contractService.updateContractStatus(contract.getId(), ContractStatusEnum.VOIDED.getStatus());

        assertEquals(ContractStatusEnum.VOIDED.getStatus(),
                contractMapper.selectById(contract.getId()).getStatus());
    }

    @Test
    public void testUpdateContractStatus_voidFromPerforming() {
        // 旁路：履行中 → 已作废
        ContractDO contract = insertContract("HT-ST-0004", "1000.00", ContractStatusEnum.PERFORMING);

        contractService.updateContractStatus(contract.getId(), ContractStatusEnum.VOIDED.getStatus());

        assertEquals(ContractStatusEnum.VOIDED.getStatus(),
                contractMapper.selectById(contract.getId()).getStatus());
    }

    // ========== 状态机（非法流转全覆盖） ==========

    @Test
    public void testUpdateContractStatus_draftToPerforming_illegal() {
        // 草稿不能跳级到履行中（必须先审批中）
        ContractDO contract = insertDraftContract("HT-ST-0011", "1000.00");

        assertServiceException(() -> contractService.updateContractStatus(
                contract.getId(), ContractStatusEnum.PERFORMING.getStatus()), CONTRACT_STATUS_INVALID);
    }

    @Test
    public void testUpdateContractStatus_draftToSettled_illegal() {
        ContractDO contract = insertDraftContract("HT-ST-0012", "1000.00");

        assertServiceException(() -> contractService.updateContractStatus(
                contract.getId(), ContractStatusEnum.SETTLED.getStatus()), CONTRACT_STATUS_INVALID);
    }

    @Test
    public void testUpdateContractStatus_approvingToSettled_illegal() {
        ContractDO contract = insertContract("HT-ST-0013", "1000.00", ContractStatusEnum.APPROVING);

        assertServiceException(() -> contractService.updateContractStatus(
                contract.getId(), ContractStatusEnum.SETTLED.getStatus()), CONTRACT_STATUS_INVALID);
    }

    @Test
    public void testUpdateContractStatus_settledIsTerminal_illegal() {
        // 已结算是终态，任何目标状态都不允许
        ContractDO contract = insertContract("HT-ST-0014", "1000.00", ContractStatusEnum.SETTLED);

        assertServiceException(() -> contractService.updateContractStatus(
                contract.getId(), ContractStatusEnum.VOIDED.getStatus()), CONTRACT_STATUS_INVALID);
    }

    @Test
    public void testUpdateContractStatus_voidedIsTerminal_illegal() {
        // 已作废是终态，不能复活
        ContractDO contract = insertContract("HT-ST-0015", "1000.00", ContractStatusEnum.VOIDED);

        assertServiceException(() -> contractService.updateContractStatus(
                contract.getId(), ContractStatusEnum.DRAFT.getStatus()), CONTRACT_STATUS_INVALID);
    }

    @Test
    public void testUpdateContractStatus_sameStatus_illegal() {
        // 同状态流转（草稿→草稿）无业务含义，按非法处理
        ContractDO contract = insertDraftContract("HT-ST-0016", "1000.00");

        assertServiceException(() -> contractService.updateContractStatus(
                contract.getId(), ContractStatusEnum.DRAFT.getStatus()), CONTRACT_STATUS_INVALID);
    }

    @Test
    public void testUpdateContractStatus_notExists() {
        assertServiceException(() -> contractService.updateContractStatus(
                999999L, ContractStatusEnum.APPROVING.getStatus()), CONTRACT_NOT_EXISTS);
    }

    // ========== 辅助方法 ==========

    private ContractDO insertDraftContract(String contractNo, String amount) {
        return insertContract(contractNo, amount, ContractStatusEnum.DRAFT);
    }

    private ContractDO insertContract(String contractNo, String amount, ContractStatusEnum status) {
        ContractDO contract = randomPojo(ContractDO.class, o -> {
            o.setId(null);
            o.setContractNo(contractNo);
            o.setContractName("测试合同-" + contractNo);
            o.setAmount(new BigDecimal(amount));
            o.setStatus(status.getStatus());
            o.setDirection(1);
            // 用 0.00 而非 0：assertPojoEquals 比的是 BigDecimal.equals（含 scale），
            // H2 decimal(18,2) 读回 scale=2，期望值也必须是 scale=2 才相等。
            o.setPaidAmount(new BigDecimal("0.00"));
        });
        contractMapper.insert(contract);
        return contract;
    }

    private void insertContractWithFilter(String contractNo, Long projectId, ContractStatusEnum status,
                                          Integer direction) {
        ContractDO contract = randomPojo(ContractDO.class, o -> {
            o.setId(null);
            o.setContractNo(contractNo);
            o.setContractName("测试合同-" + contractNo);
            o.setProjectId(projectId);
            o.setStatus(status.getStatus());
            o.setDirection(direction);
            o.setAmount(new BigDecimal("1000.00"));
            o.setPaidAmount(new BigDecimal("0.00"));
        });
        contractMapper.insert(contract);
    }

}
