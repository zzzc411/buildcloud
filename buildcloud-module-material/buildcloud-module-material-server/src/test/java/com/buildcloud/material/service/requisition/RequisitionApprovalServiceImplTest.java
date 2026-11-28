package com.buildcloud.material.service.requisition;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionApprovalStatusRespVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionApprovalSubmitReqVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionReturnReqVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionSaveReqVO;
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
import com.buildcloud.material.service.requisition.strategy.RequisitionSubmitHandlerFactory;
import com.buildcloud.material.service.requisition.strategy.RequisitionV1SubmitHandler;
import com.buildcloud.material.service.requisition.strategy.RequisitionV2SubmitHandler;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVAL_ALREADY_RUNNING;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVAL_START_FAILED;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVAL_STATUS_NOT_ALLOWED;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVERS_EMPTY;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_APPROVERS_EXCEED;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_CANNOT_OUTBOUND;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_CANNOT_RETURN;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_ITEM_DUPLICATE;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_ITEM_EMPTY;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_ITEM_NOT_IN_REQUISITION;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_QUANTITY_EXCEEDED;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_QUANTITY_INVALID;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_ENOUGH;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.PROCESS_KEY_REQUISITION_APPROVE;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.VARIABLE_APPROVERS;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.VARIABLE_REQ_NO;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.VARIABLE_REQUISITION_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RequisitionApprovalServiceImpl} 的单元测试类 —— 申领审批（会签）/ 出库 / 退料闭环
 *
 * ========== 覆盖点与 plan-w4-material-v1 Phase 5 验收标准的对应关系 ==========
 *
 * | 验收标准 / 任务书要求 | 用例 |
 * | --- | --- |
 * | 会签多人全过才通过（单次回调不通过） | testCoSign_* 一组 3 个 |
 * | 审批通过 → 状态 2、**库存不变**（最易错点） | testHandleApprovalResult_approve_stockUnchanged |
 * | 审批驳回 → 状态 9、库存回补、冲正流水 biz_type=4 且正数 | testHandleApprovalResult_reject_* |
 * | 出库 → 状态 3、库存不变 | testOutbound_* |
 * | 退料部分 → 状态 5、库存增加、流水 biz_type=3 | testReturn_partial_* |
 * | 退料全退 → 状态 4 | testReturn_full_* |
 * | 退料超量 → 报错 | testReturn_exceed_* |
 * | 重复回调幂等 | testHandleApprovalResult_*_twice_* |
 * | 提交审批 → 扣库存 + 发起流程（Feign 入参） | testSubmitApproval_* |
 *
 * ========== 关于「会签两次回调才通过」（本类最需要说清的一组用例） ==========
 *
 * 会签的**任务级**并行是 Flowable 引擎的行为（每个审批人一个 task，全部 complete 后实例才结束），
 * 业务侧只能看到**一次**实例结束事件。因此「单次回调不通过」在**业务侧**的正确体现是：
 * 流程实例还没结束时**根本不会有回调**；一旦收到回调，就一定是「全过」或「任一驳回」。
 * 为了在同一层面验证「两个人、两次动作」，本类用**两个不同流程实例编号**模拟两轮：
 * 1. 第一个审批人通过 → 业务侧没有任何回调（用「未调用 handleApprovalResult」表达）→ 状态仍是审批中(1)；
 * 2. 第二个人通过 → 引擎判定全过 → 回调 APPROVE → 状态才变成已扣减(2)；
 * 并且额外钉死「重复的 APPROVE 回调不会把库存再处理一遍」。
 * 真正的 Flowable 多人任务流转在端到端联调里验证（scripts 下的演示脚本 + ACT_RU_TASK 计数）。
 *
 * ========== 为什么不加 @Transactional（同 P4 的类注释） ==========
 *
 * 每个用例自己通过 Service（内部 @Transactional）产生真实提交，表由 @Sql 前置重建做隔离。
 * 若外层再套测试事务，Service 的提交点不存在（只是 savepoint），
 * 「回滚后库存/流水/状态都没变」这类断言就会看到事务内中间态，失去证明力。
 *
 * @author buildcloud
 */
@Import({RequisitionApprovalServiceImpl.class, RequisitionServiceImpl.class,
        RequisitionV1SubmitHandler.class, RequisitionV2SubmitHandler.class,
        RequisitionSubmitHandlerFactory.class})
@Sql(scripts = "/sql/requisition-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class RequisitionApprovalServiceImplTest extends BaseDbUnitTest {

    @Resource
    private RequisitionApprovalServiceImpl approvalService;

    @Resource
    private RequisitionServiceImpl requisitionService;

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private RequisitionItemMapper requisitionItemMapper;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    /**
     * BPM 流程实例 RPC 的 Mock（Feign 代理无法在单测里真调）
     */
    @MockBean
    private BpmProcessInstanceApi processInstanceApi;

    // ========== 一、提交审批：扣库存 + 发起流程 ==========

    @Test
    public void testSubmitApproval_draft_deductStockAndStartProcess() {
        // 预置：库存 100，建单申领 30
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        String processInstanceId = PROCESS_KEY_REQUISITION_APPROVE + ":1:inst-1";
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(processInstanceId));

        // 提交审批：会签人 1、2
        String result = approvalService.submitApproval(id, List.of(1L, 2L));

        // ===== 断言 1：返回流程实例编号 =====
        assertEquals(processInstanceId, result);

        // ===== 断言 2：单据状态 = 已提交(1)、审批中(1)、实例号/会签人/超时时间已写 =====
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(), db.getStatus(),
                "审批路径的提交后状态应为已提交(1)（不是 V1 的已扣减(2)——审批通过才推进到 2）");
        assertEquals(RequisitionApprovalStatusEnum.RUNNING.getStatus(), db.getApprovalStatus());
        assertEquals(processInstanceId, db.getProcessInstanceId());
        assertEquals("1,2", db.getApprovers(), "会签人列表应以逗号分隔落库");
        assertNotNull(db.getTimeoutTime(), "提交时写 timeout_time（W5 延迟消息据此关单）");

        // ===== 断言 3（核心）：库存已扣减 100 → 70，且有一条 biz_type=2 负流水 =====
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(1, logs.size());
        assertEquals(StockBizTypeEnum.REQUISITION_OUT.getBizType(), logs.get(0).getBizType());
        assertQty("-30", logs.get(0).getQuantity());
        assertQty("70", logs.get(0).getBalanceAfter());
        assertEquals(db.getReqNo(), logs.get(0).getBizNo());
        assertBalanceConsistent(1L, 1L, "100", "70");

        // ===== 断言 4：Feign 入参正确（别臆造 BPM API 的回归护栏） =====
        ArgumentCaptor<BpmProcessInstanceCreateReqDTO> captor =
                ArgumentCaptor.forClass(BpmProcessInstanceCreateReqDTO.class);
        verify(processInstanceApi).createProcessInstance(any(), captor.capture());
        BpmProcessInstanceCreateReqDTO reqDTO = captor.getValue();
        assertEquals(PROCESS_KEY_REQUISITION_APPROVE, reqDTO.getProcessDefinitionKey());
        assertEquals(String.valueOf(id), reqDTO.getBusinessKey(), "businessKey 必须是申领单 id（回调据此定位）");
        assertNotNull(reqDTO.getVariables());
        assertEquals(id, reqDTO.getVariables().get(VARIABLE_REQUISITION_ID));
        assertEquals(db.getReqNo(), reqDTO.getVariables().get(VARIABLE_REQ_NO));
        assertEquals(List.of(1L, 2L), reqDTO.getVariables().get(VARIABLE_APPROVERS),
                "approvers 是决定会签实例数的唯一变量，少传会导致一个任务都不创建");
        // 发起人自选审批人必须为 null：会签走的是 BPMN 的 candidateStrategy=60 + ${approvers}，
        // 若同时传 startUserSelectAssignees 会覆盖实例数（见 BPMN 文件头「会签」一节）
        assertNull(reqDTO.getStartUserSelectAssignees());
    }

    @Test
    public void testSubmitApproval_stockNotEnough_rollbackEverything() {
        // 库存 10，申领 50 → 提交审批必须失败并整体回滚
        insertStock(1L, 1L, "10.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "50.0000")));

        assertServiceException(() -> approvalService.submitApproval(id, List.of(1L)),
                STOCK_NOT_ENOUGH, "10.0000", "50.0000");

        // 断言：库存/流水/单据状态全部回滚，且**没有**发起流程
        assertQty("10", stockOf(1L, 1L).getAvailableQty());
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).isEmpty());
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(), db.getStatus(), "失败后必须回到草稿(0)");
        assertEquals(RequisitionApprovalStatusEnum.NOT_START.getStatus(), db.getApprovalStatus());
        assertNull(db.getApprovers(), "回滚后不应留下会签人");
        assertNull(db.getProcessInstanceId(), "回滚后不应留下实例号");
    }

    @Test
    public void testSubmitApproval_bpmReturnsBlankId_rollbackStock() {
        // bpm 返回空实例号 → 必须抛错回滚，否则「库存已扣、凭证审批中、但没有流程」的死状态
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(null));

        assertServiceException(() -> approvalService.submitApproval(id, List.of(1L)),
                REQUISITION_APPROVAL_START_FAILED);

        // 断言：库存与流水都回到原样，单据回草稿
        assertQty("100", stockOf(1L, 1L).getAvailableQty(), "发起失败必须回滚扣减");
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).isEmpty());
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(), db.getStatus());
        assertNull(db.getProcessInstanceId());
    }

    @Test
    public void testSubmitApproval_notDraft_forbidden() {
        // 已扣减(2) 的单再提交审批会造成第二次扣库存 —— 必须拦
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));
        requisitionService.submitRequisition(id); // V1 同步链路先把它推成 2

        assertServiceException(() -> approvalService.submitApproval(id, List.of(1L)),
                REQUISITION_APPROVAL_STATUS_NOT_ALLOWED, RequisitionStatusEnum.DEDUCTED.getName());

        // 断言：库存没有被第二次扣减（仍是 90），流水仍是 1 条
        assertQty("90", stockOf(1L, 1L).getAvailableQty());
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());
    }

    @Test
    public void testSubmitApproval_alreadyRunning_forbidden() {
        // 已在审批中的单再次提交 → ALREADY_RUNNING（带出在途实例号）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));
        String inst = PROCESS_KEY_REQUISITION_APPROVE + ":1:inst-running";
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(inst));
        approvalService.submitApproval(id, List.of(1L));

        assertServiceException(() -> approvalService.submitApproval(id, List.of(2L)),
                REQUISITION_APPROVAL_ALREADY_RUNNING, inst);

        // 断言：库存只扣了一次
        assertQty("90", stockOf(1L, 1L).getAvailableQty());
    }

    @Test
    public void testSubmitApproval_approversEmpty_throws() {
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));

        assertServiceException(() -> approvalService.submitApproval(id, List.of()), REQUISITION_APPROVERS_EMPTY);
        assertServiceException(() -> approvalService.submitApproval(id, null), REQUISITION_APPROVERS_EMPTY);
        // 全是 null 的列表等价于空列表
        List<Long> nulls = new ArrayList<>();
        nulls.add(null);
        assertServiceException(() -> approvalService.submitApproval(id, nulls), REQUISITION_APPROVERS_EMPTY);
    }

    @Test
    public void testSubmitApproval_approversExceedLimit_throws() {
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));
        List<Long> tooMany = new ArrayList<>();
        for (long i = 1; i <= RequisitionApprovalSubmitReqVO.MAX_APPROVERS + 1; i++) {
            tooMany.add(i);
        }

        assertServiceException(() -> approvalService.submitApproval(id, tooMany),
                REQUISITION_APPROVERS_EXCEED, RequisitionApprovalSubmitReqVO.MAX_APPROVERS, tooMany.size());
    }

    @Test
    public void testSubmitApproval_approversDeduplicated() {
        // 同一人被传两次（勾选-取消-再勾选）→ 去重后 1 人，不应报错也不应重复建任务
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(PROCESS_KEY_REQUISITION_APPROVE + ":1:inst-dedup"));

        approvalService.submitApproval(id, List.of(1L, 1L, 2L, 2L));

        assertEquals("1,2", requisitionMapper.selectById(id).getApprovers());
    }

    @Test
    public void testSubmitApproval_notExists_throws() {
        assertServiceException(() -> approvalService.submitApproval(999999L, List.of(1L)),
                REQUISITION_NOT_EXISTS);
    }

    // ========== 二、审批通过：状态 1→2，**库存与流水完全不变**（最易错点） ==========

    @Test
    public void testHandleApprovalResult_approve_stockUnchanged() {
        // 准备：库存 100，提交审批申领 30 → 已提交(1)，库存 70
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L, 2L));
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        int logCountBefore = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size();

        // 审批通过
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "全票通过");

        // ===== 断言 1：业务状态 已提交(1) → 已扣减(2)；审批状态 → 通过(2) =====
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(), db.getStatus(),
                "审批通过后状态应为已扣减(2)");
        assertEquals(RequisitionApprovalStatusEnum.APPROVE.getStatus(), db.getApprovalStatus());

        // ===== 断言 2（本用例的核心，也是全 Phase 最贵的一条断言）：库存一分不动 =====
        // V1 的口径是「提交即扣减」，submitApproval 已经扣过 30。审批通过只是放行，
        // 若在这里再扣一次 → 同一张单扣了两遍，而驳回只补一遍，账永远不平。
        assertQty("70", stockOf(1L, 1L).getAvailableQty(),
                "审批通过绝不能重复扣库存（提交审批时已扣减）");

        // ===== 断言 3：流水条数不变（不为「通过」写任何流水） =====
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(logCountBefore, logs.size(), "审批通过不应产生新流水");
        assertQty("-30", stockLogMapper.selectSumQuantity(1L, 1L), "该单净影响仍应是 -30");

        // ===== 断言 4：一致性不变量仍成立（期初 + Σ流水 = 期末） =====
        assertBalanceConsistent(1L, 1L, "100", "70");
    }

    @Test
    public void testHandleApprovalResult_approve_twice_idempotent() {
        // 重复回调（bpm 重投/人工补投）：第二次必须幂等，不重复处理任何库存
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L));

        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "通过");
        assertQty("70", stockOf(1L, 1L).getAvailableQty());

        // 第二次回调：终态短路（已是 APPROVE(2)）→ 直接返回
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "通过");

        // 断言：状态、库存、流水全部没变
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(), db.getStatus());
        assertEquals(RequisitionApprovalStatusEnum.APPROVE.getStatus(), db.getApprovalStatus());
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());
    }

    @Test
    public void testHandleApprovalResult_approve_withWrongInstance_ignored() {
        // 串号校验：回调实例号与库中不一致（旧流程的迟到通知）→ 丢弃，不动任何数据
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        prepareSubmittedApproval(id, List.of(1L));

        approvalService.handleApprovalResult(id, "requisition-approve:1:OLD-INSTANCE",
                BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "迟到的旧流程");

        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(), db.getStatus(), "旧实例的回调不应推进状态");
        assertEquals(RequisitionApprovalStatusEnum.RUNNING.getStatus(), db.getApprovalStatus());
        assertQty("70", stockOf(1L, 1L).getAvailableQty(), "库存不变");
    }

    @Test
    public void testHandleApprovalResult_v1Requisition_ignored() {
        // V1 同步链路的单据（approval_status = -1 未开始）收到任何"审批结果"都不成立 → 忽略。
        // 这是防止「回调误投到没发起过审批的单据」把库存搞乱的护栏。
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        requisitionService.submitRequisition(id);
        assertQty("70", stockOf(1L, 1L).getAvailableQty());

        approvalService.handleApprovalResult(id, null, BpmProcessInstanceStatusEnum.REJECT.getStatus(), "乱投");

        // 断言：状态仍是已扣减(2)，库存没有被"驳回"回补
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(), requisitionMapper.selectById(id).getStatus());
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
    }

    // ========== 三、审批驳回：状态 1→9，**库存回补** + biz_type=4 冲正流水 ==========

    @Test
    public void testHandleApprovalResult_reject_restoreStockAndReversalLog() {
        // 准备：库存 100，提交审批申领 30 → 已提交(1)，库存 70
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L, 2L));
        assertQty("70", stockOf(1L, 1L).getAvailableQty());

        // 审批驳回
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.REJECT.getStatus(), "料号不对");

        // ===== 断言 1：业务状态 → 已取消(9)；审批状态 → 不通过(3) =====
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), db.getStatus());
        assertEquals(RequisitionApprovalStatusEnum.REJECT.getStatus(), db.getApprovalStatus());

        // ===== 断言 2（核心）：库存回补到申领前的 100 =====
        assertQty("100", stockOf(1L, 1L).getAvailableQty(), "驳回必须把提交审批时扣掉的库存补回来");

        // ===== 断言 3：两条流水，扣减(-30) + 冲正(+30)，冲正记正数、balance_after 正确 =====
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(2, logs.size(), "驳回应追加一条冲正流水（原扣减流水保留，审计要求）");
        StockLogDO deductLog = logs.get(0);
        StockLogDO reversalLog = logs.get(1);
        assertEquals(StockBizTypeEnum.REQUISITION_OUT.getBizType(), deductLog.getBizType());
        assertQty("-30", deductLog.getQuantity());
        assertEquals(StockBizTypeEnum.REVERSAL.getBizType(), reversalLog.getBizType(),
                "冲正流水 biz_type 必须为 4");
        assertEquals(1, reversalLog.getQuantity().signum(), "冲正流水 quantity 必须为正数");
        assertQty("30", reversalLog.getQuantity());
        assertQty("100", reversalLog.getBalanceAfter(), "冲正后余额必须是回补后的 100");
        assertEquals(db.getReqNo(), reversalLog.getBizNo(), "冲正流水挂同一单号（可按单追溯）");

        // ===== 断言 4：SUM(quantity) 归零（该单的净库存影响为 0） =====
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
        assertBalanceConsistent(1L, 1L, "100", "100");
    }

    @Test
    public void testHandleApprovalResult_cancel_treatedAsReject() {
        // 审批取消(4)（发起人撤回/管理员终止）与驳回同一条路径：都意味着"这张单不批了"，必须回补
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "25.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L));

        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.CANCEL.getStatus(), "发起人撤回");

        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), db.getStatus());
        assertEquals(RequisitionApprovalStatusEnum.CANCEL.getStatus(), db.getApprovalStatus(),
                "审批状态应保留 4（已取消），与驳回(3) 可区分");
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
    }

    @Test
    public void testHandleApprovalResult_reject_twice_noDoubleRestore() {
        // **幂等最重要的一条**：重复驳回不能把库存补两次（料凭空多出来的账务错误）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L));

        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.REJECT.getStatus(), "驳回");
        assertQty("100", stockOf(1L, 1L).getAvailableQty());

        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.REJECT.getStatus(), "驳回");

        // 断言：仍是 100（不是 130），流水仍是 2 条（不是 3 条）
        assertQty("100", stockOf(1L, 1L).getAvailableQty(), "重复驳回绝不能把库存补两次");
        assertEquals(2, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size(),
                "重复驳回不应产生第三条流水");
    }

    @Test
    public void testHandleApprovalResult_approve_after_reject_noFurtherAction() {
        // 先驳回（回补 + 取消），随后又来一个「通过」回调 → 必须忽略：
        // 单据已是终态，若让它"通过"会把一张已取消的单推成已扣减（库存却没扣）——账直接崩。
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L));

        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.REJECT.getStatus(), "驳回");
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "迟到的通过");

        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), db.getStatus(), "已取消不能被迟到回调推成已扣减");
        assertEquals(RequisitionApprovalStatusEnum.REJECT.getStatus(), db.getApprovalStatus());
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
    }

    @Test
    public void testHandleApprovalResult_multiItemsReject_allRestored() {
        insertStock(1L, 1L, "100.0000");
        insertStock(1L, 2L, "50.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L,
                item(1L, "30.0000"), item(2L, "20.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L));
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        assertQty("30", stockOf(1L, 2L).getAvailableQty());

        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.REJECT.getStatus(), "驳回");

        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        assertQty("50", stockOf(1L, 2L).getAvailableQty());
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 2L));
    }

    @Test
    public void testHandleApprovalResult_requisitionNotExists_ignored() {
        // 单据不存在：记日志丢弃，不抛异常（抛异常会污染 bpm 的流程结束事务）
        approvalService.handleApprovalResult(999999L, "inst-x",
                BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "不存在");
        // 没有异常即通过
        assertNull(requisitionMapper.selectById(999999L));
    }

    // ========== 四、会签：全部通过才通过（单次回调不通过） ==========

    @Test
    public void testCoSign_singleApproverPass_doesNotCompleteInstance() {
        // 会签 2 人：第一个人批准后，**流程实例还没结束**，因此业务侧没有任何回调。
        // 业务侧能观察到的现象就是：单据仍是已提交(1)/审批中(1)，库存与状态都没被推进。
        // （Flowable 的并行多实例在 nrOfCompletedInstances < nrOfInstances 时不会触发结束事件，
        //   这是引擎行为，端到端联调里用 ACT_RU_TASK 计数验证；此处钉住业务侧的"未通过"表达。）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L, 2L));

        // 第一个审批人通过（业务侧无回调——这里用"什么都不调用"来表达）
        RequisitionDO afterFirst = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(), afterFirst.getStatus(),
                "只有 1/2 会签批准时不能推进到已扣减(2)");
        assertEquals(RequisitionApprovalStatusEnum.RUNNING.getStatus(), afterFirst.getApprovalStatus());

        // 第二个人也通过 → 引擎判定全过 → 才回调 APPROVE
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "两人全过");

        RequisitionDO afterSecond = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(), afterSecond.getStatus(),
                "2/2 全部通过后才推进到已扣减(2)");
        assertEquals(RequisitionApprovalStatusEnum.APPROVE.getStatus(), afterSecond.getApprovalStatus());
        // 库存只扣过一次（提交时），"通过"不碰库存
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
    }

    @Test
    public void testCoSign_anyOneReject_cancelsWholeRequisition() {
        // 会签语义：任一人驳回 → 实例结束为 REJECT → 整单取消 + 回补（不需要等其他人也否决）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L, 2L, 3L));

        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.REJECT.getStatus(), "第 2 人驳回");

        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), requisitionMapper.selectById(id).getStatus());
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        // 会签人列表保留（审计要看这张单当时由谁会签）
        assertEquals("1,2,3", requisitionMapper.selectById(id).getApprovers());
    }

    @Test
    public void testCoSign_approversPersistedInProcessVariable() {
        // 会签人必须原样进入流程变量（基座按人数创建任务实例）；且落库供审批页展示"还差谁"
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(PROCESS_KEY_REQUISITION_APPROVE + ":1:inst-cosign"));

        approvalService.submitApproval(id, List.of(1L, 2L, 3L));

        ArgumentCaptor<BpmProcessInstanceCreateReqDTO> captor =
                ArgumentCaptor.forClass(BpmProcessInstanceCreateReqDTO.class);
        verify(processInstanceApi).createProcessInstance(any(), captor.capture());
        assertEquals(List.of(1L, 2L, 3L), captor.getValue().getVariables().get(VARIABLE_APPROVERS));
        assertEquals("1,2,3", requisitionMapper.selectById(id).getApprovers());
    }

    // ========== 五、出库：2 → 3，**库存不变** ==========

    @Test
    public void testOutbound_deducted_status3_stockUnchanged() {
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        assertQty("70", stockOf(1L, 1L).getAvailableQty());

        LocalDateTime before = LocalDateTime.now();
        RequisitionDO result = approvalService.outbound(id);

        // ===== 断言 1（返回值，不查库 —— L3-10 护栏）：status / actualOutTime 已回填 =====
        assertEquals(RequisitionStatusEnum.OUTBOUND.getStatus(), result.getStatus(),
                "outbound 返回值 status 必须已回填为已出库(3)");
        assertNotNull(result.getActualOutTime(), "outbound 返回值必须带 actualOutTime");

        // ===== 断言 2：返回值 == 库值（逐字段） =====
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(db.getStatus(), result.getStatus());
        assertTrue(Duration.between(db.getActualOutTime(), result.getActualOutTime()).abs().toMillis() < 1,
                "返回值与库值的 actualOutTime 应一致（容差 1 毫秒，L3-11）");
        assertTrue(!result.getActualOutTime().isBefore(before.minusSeconds(5)),
                "actualOutTime 应≈当前时间，实际：" + result.getActualOutTime());

        // ===== 断言 3（核心）：库存一分不动，也不产生新流水 =====
        assertQty("70", stockOf(1L, 1L).getAvailableQty(), "出库不动库存（V1 在提交审批时已扣减）");
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size(),
                "出库不应产生库存流水");
        assertBalanceConsistent(1L, 1L, "100", "70");
    }

    @Test
    public void testOutbound_twice_forbidden() {
        // 重复出库必须拒绝（否则 actual_out_time 被刷新，"在途料时长"报表失真）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);
        LocalDateTime firstOutTime = requisitionMapper.selectById(id).getActualOutTime();

        assertServiceException(() -> approvalService.outbound(id),
                REQUISITION_CANNOT_OUTBOUND, RequisitionStatusEnum.OUTBOUND.getName());

        // 断言：出库时间没有被刷新
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.OUTBOUND.getStatus(), db.getStatus());
        assertEquals(firstOutTime, db.getActualOutTime(), "重复出库不应刷新实际出库时间");
    }

    @Test
    public void testOutbound_submitted_notAllowed() {
        // 已提交(1)（审批还没通过）不能出库：料不能先出（状态机也没有 1→3 这条边）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        prepareSubmittedApproval(id, List.of(1L));

        assertServiceException(() -> approvalService.outbound(id),
                REQUISITION_CANNOT_OUTBOUND, RequisitionStatusEnum.SUBMITTED.getName());
    }

    @Test
    public void testOutbound_draft_notAllowed() {
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));

        assertServiceException(() -> approvalService.outbound(id),
                REQUISITION_CANNOT_OUTBOUND, RequisitionStatusEnum.DRAFT.getName());
    }

    // ========== 六、退料：部分 → 5；全退 → 4；回补库存 + biz_type=3 流水 ==========

    @Test
    public void testReturn_partial_status5_stockIncreaseAndReturnLog() {
        // 库存 100 → 申领 30（扣到 70）→ 出库 → 部分退 10（回到 80）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);
        assertQty("70", stockOf(1L, 1L).getAvailableQty());

        RequisitionDO result = approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "10.0000")));

        // ===== 断言 1（返回值 == 库值，L3-10）：状态 5、actualReturnTime 已回填 =====
        assertEquals(RequisitionStatusEnum.PARTIAL_RETURNED.getStatus(), result.getStatus(),
                "部分退料后状态应为部分回退(5)");
        assertNotNull(result.getActualReturnTime());
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(db.getStatus(), result.getStatus());
        assertEquals(RequisitionStatusEnum.PARTIAL_RETURNED.getStatus(), db.getStatus());
        assertTrue(Duration.between(db.getActualReturnTime(), result.getActualReturnTime()).abs().toMillis() < 1,
                "返回值与库值的 actualReturnTime 应一致（容差 1 毫秒，L3-11）");

        // ===== 断言 2（核心）：库存回补 70 → 80 =====
        assertQty("80", stockOf(1L, 1L).getAvailableQty(), "退料必须把库存补回来");

        // ===== 断言 3：流水 2 条（扣减 -30、退回 +10），biz_type=3 且为正数、balance_after 正确 =====
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(2, logs.size());
        StockLogDO returnLog = logs.get(1);
        assertEquals(StockBizTypeEnum.RETURN.getBizType(), returnLog.getBizType(), "退回流水 biz_type 必须为 3");
        assertEquals(1, returnLog.getQuantity().signum(), "退回流水 quantity 必须为正数");
        assertQty("10", returnLog.getQuantity());
        assertQty("80", returnLog.getBalanceAfter(), "balance_after 必须是回补后的余额");
        assertEquals(db.getReqNo(), returnLog.getBizNo());

        // ===== 断言 4：明细 returned_qty = 10，可退量 = 20 =====
        RequisitionItemDO item = requisitionItemMapper.selectListByRequisitionId(id).get(0);
        assertQty("10", item.getReturnedQty());

        // ===== 断言 5：一致性（期初 100 + (-30 + 10) = 80） =====
        assertBalanceConsistent(1L, 1L, "100", "80");
    }

    @Test
    public void testReturn_full_status4_completed() {
        // 申领 30 全部退回 → 已完成(4)，库存回到 100
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);

        RequisitionDO result = approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "30.0000")));

        assertEquals(RequisitionStatusEnum.COMPLETED.getStatus(), result.getStatus(), "全退后状态应为已完成(4)");
        assertEquals(RequisitionStatusEnum.COMPLETED.getStatus(), requisitionMapper.selectById(id).getStatus());
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        // 流水净额归零（扣减 -30 + 退回 +30）
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
        assertBalanceConsistent(1L, 1L, "100", "100");
    }

    @Test
    public void testReturn_partialTwiceThenFull_status4() {
        // 分两次退：10 → 5（仍部分回退）→ 15（累计 30，全退）→ 已完成(4)
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);

        assertPartStatus(approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "10.0000"))));
        assertQty("80", stockOf(1L, 1L).getAvailableQty());

        assertPartStatus(approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "5.0000"))));
        assertQty("85", stockOf(1L, 1L).getAvailableQty());

        RequisitionDO last = approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "15.0000")));
        assertEquals(RequisitionStatusEnum.COMPLETED.getStatus(), last.getStatus(), "累计退完应为已完成(4)");
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
    }

    @Test
    public void testReturn_multiItemsPartial_updatesOnlyGivenLines() {
        // 两行明细：物料1 申领 30、物料2 申领 20；只退物料1 的 10 → 物料2 的 returned_qty 保持 0
        insertStock(1L, 1L, "100.0000");
        insertStock(1L, 2L, "50.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L,
                item(1L, "30.0000"), item(2L, "20.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        assertQty("30", stockOf(1L, 2L).getAvailableQty());

        approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "10.0000")));

        assertQty("80", stockOf(1L, 1L).getAvailableQty());
        assertQty("30", stockOf(1L, 2L).getAvailableQty(), "未退料的物料库存不动");
        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(id);
        assertQty("10", items.get(0).getReturnedQty());
        assertQty("0", items.get(1).getReturnedQty());
        // 未全退（物料2 一行都没退）→ 部分回退
        assertPartStatus(requisitionMapper.selectById(id));
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 2L).size(),
                "物料2 只有申领扣减那一条流水");
    }

    @Test
    public void testReturn_exceed_throwsAndNothingChanges() {
        // 退料超量：申领 30 却退 31 → 报错，且库存/流水/状态全不变
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);
        assertQty("70", stockOf(1L, 1L).getAvailableQty());

        assertServiceException(() -> approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "31.0000"))),
                REQUISITION_RETURN_QUANTITY_EXCEEDED, 1L, "30.0000", "31.0000");

        // 断言：整单回滚，什么都没变
        assertQty("70", stockOf(1L, 1L).getAvailableQty(), "超量退料必须整体回滚");
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());
        assertEquals(RequisitionStatusEnum.OUTBOUND.getStatus(),
                requisitionMapper.selectById(id).getStatus());
        assertQty("0", requisitionItemMapper.selectListByRequisitionId(id).get(0).getReturnedQty());
    }

    @Test
    public void testReturn_exceedAfterPartial_throws() {
        // 先退 25（剩 5 可退），再退 10 → 必须报错并给出"可退 5"
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);
        approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "25.0000")));
        assertQty("95", stockOf(1L, 1L).getAvailableQty());

        assertServiceException(() -> approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "10.0000"))),
                REQUISITION_RETURN_QUANTITY_EXCEEDED, 1L, "5.0000", "10.0000");

        assertQty("95", stockOf(1L, 1L).getAvailableQty(), "失败的第二笔不应回补");
    }

    @Test
    public void testReturn_itemNotInRequisition_throws() {
        // 退一张单没领过的料 → 报错（否则库存净增却不挂单据，是对账断点）
        insertStock(1L, 1L, "100.0000");
        insertStock(1L, 9L, "50.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);

        assertServiceException(() -> approvalService.returnMaterials(buildReturnReqVO(id, retItem(9L, "5.0000"))),
                REQUISITION_RETURN_ITEM_NOT_IN_REQUISITION, 9L);

        assertQty("50", stockOf(1L, 9L).getAvailableQty(), "不在单据里的物料库存不动");
    }

    @Test
    public void testReturn_draftOrDeducted_forbidden() {
        // 未出库的单不能退料（已扣减(2) 时"退料"其实是撤销申领，走 cancel 的 biz_type=4 冲正，不是 3 退回）
        Long draftId = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));
        assertServiceException(() -> approvalService.returnMaterials(buildReturnReqVO(draftId, retItem(1L, "1.0000"))),
                REQUISITION_CANNOT_RETURN, RequisitionStatusEnum.DRAFT.getName());

        insertStock(1L, 1L, "100.0000");
        Long deductedId = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));
        approveRequisition(deductedId, List.of(1L));
        assertServiceException(() -> approvalService.returnMaterials(buildReturnReqVO(deductedId, retItem(1L, "1.0000"))),
                REQUISITION_CANNOT_RETURN, RequisitionStatusEnum.DEDUCTED.getName());
    }

    @Test
    public void testReturn_completed_forbidden() {
        // 已完成(4) 是终态，不能再退料（否则会超退）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);
        approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "30.0000")));

        assertServiceException(() -> approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "1.0000"))),
                REQUISITION_CANNOT_RETURN, RequisitionStatusEnum.COMPLETED.getName());
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
    }

    @Test
    public void testReturn_invalidQuantity_throws() {
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);

        // 退 0：会写出 quantity=0 的流水，让"退回笔数"与"实际回补量"对不上 → 拦住
        // （错误消息里回显的是原始入参 BigDecimal 的 toString，故期望值带 4 位小数）
        assertServiceException(() -> approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "0.0000"))),
                REQUISITION_RETURN_QUANTITY_INVALID, 1L, new BigDecimal("0.0000"));
        // 退负数更严重：会变成反向申领把库存扣掉
        assertServiceException(() -> approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "-5.0000"))),
                REQUISITION_RETURN_QUANTITY_INVALID, 1L, new BigDecimal("-5.0000"));
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
    }

    @Test
    public void testReturn_itemsEmptyOrDuplicate_throws() {
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);

        RequisitionReturnReqVO empty = new RequisitionReturnReqVO();
        empty.setRequisitionId(id);
        empty.setItems(new ArrayList<>());
        assertServiceException(() -> approvalService.returnMaterials(empty), REQUISITION_RETURN_ITEM_EMPTY);

        // 同一物料两行：各自校验会同时通过却合计超量（与建单重复物料同一类缺陷）
        RequisitionReturnReqVO dup = buildReturnReqVO(id, retItem(1L, "1.0000"), retItem(1L, "2.0000"));
        assertServiceException(() -> approvalService.returnMaterials(dup), REQUISITION_RETURN_ITEM_DUPLICATE, 1L);
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
    }

    @Test
    public void testReturn_notExists_throws() {
        RequisitionReturnReqVO reqVO = buildReturnReqVO(999999L, retItem(1L, "1.0000"));
        assertServiceException(() -> approvalService.returnMaterials(reqVO), REQUISITION_NOT_EXISTS);
    }

    // ========== 七、查询审批状态 ==========

    @Test
    public void testGetApprovalStatus_reflectsDbValues() {
        // 走完整链路：提交审批 → 通过 → 出库 → 部分退料，每步后用 getApprovalStatus 核对（L3-10）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));

        // 草稿态
        RequisitionApprovalStatusRespVO vo0 = approvalService.getApprovalStatus(id);
        assertEquals(RequisitionApprovalStatusEnum.NOT_START.getStatus(), vo0.getApprovalStatus());
        assertEquals("未开始", vo0.getApprovalStatusName());
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(), vo0.getStatus());
        assertTrue(vo0.getApproverUserIds().isEmpty());

        // 提交审批后
        String inst = prepareSubmittedApproval(id, List.of(1L, 2L));
        RequisitionApprovalStatusRespVO vo1 = approvalService.getApprovalStatus(id);
        assertEquals(RequisitionApprovalStatusEnum.RUNNING.getStatus(), vo1.getApprovalStatus());
        assertEquals("审批中", vo1.getApprovalStatusName());
        assertEquals(inst, vo1.getProcessInstanceId());
        assertEquals(List.of(1L, 2L), vo1.getApproverUserIds(), "会签人列表应可读回");
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(), vo1.getStatus());
        assertEquals("已提交", vo1.getStatusName());
        // 明细可退量 = 申领量（还没退过）
        assertEquals(0, vo1.getItems().get(0).getReturnableQty().compareTo(new BigDecimal("30")));
        assertQty("0", vo1.getItems().get(0).getReturnedQty());

        // 通过 + 出库 + 部分退料后
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "通过");
        approvalService.outbound(id);
        approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "10.0000")));

        RequisitionApprovalStatusRespVO vo2 = approvalService.getApprovalStatus(id);
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionApprovalStatusEnum.APPROVE.getStatus(), vo2.getApprovalStatus());
        assertEquals(RequisitionStatusEnum.PARTIAL_RETURNED.getStatus(), vo2.getStatus());
        // 返回值与库值逐字段一致（L3-10）
        assertEquals(db.getStatus(), vo2.getStatus());
        assertEquals(db.getApprovalStatus(), vo2.getApprovalStatus());
        assertEquals(db.getProcessInstanceId(), vo2.getProcessInstanceId());
        assertNotNull(vo2.getActualOutTime(), "已出库后应能读回实际出库时间");
        assertNotNull(vo2.getActualReturnTime(), "退料后应能读回最近退料时间");
        // 退料进度：已退 10，可退 20
        assertQty("10", vo2.getItems().get(0).getReturnedQty());
        assertQty("20", vo2.getItems().get(0).getReturnableQty());
    }

    @Test
    public void testGetApprovalStatus_notExists_throws() {
        assertServiceException(() -> approvalService.getApprovalStatus(999999L), REQUISITION_NOT_EXISTS);
    }

    // ========== 辅助方法 ==========

    /**
     * 走完「提交审批」并把 Feign 返回值固定下来，返回流程实例编号
     *
     * 为什么不直接改库造「已提交」态：那样会绕过 submitApproval 的扣减与校验，
     * 测出来的"通过/驳回"与真实链路的前提不一致（库存其实没扣，驳回回补就变成了凭空加库存）。
     */
    private String prepareSubmittedApproval(Long id, List<Long> approvers) {
        String inst = PROCESS_KEY_REQUISITION_APPROVE + ":1:inst-" + id + "-" + approvers.size();
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(inst));
        approvalService.submitApproval(id, approvers);
        return inst;
    }

    /**
     * 提交审批 + 审批通过（到达「已扣减(2)」，库存已被扣一次）
     */
    private void approveRequisition(Long id, List<Long> approvers) {
        String inst = prepareSubmittedApproval(id, approvers);
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "通过");
    }

    /**
     * 断言单据处于「部分回退(5)」
     */
    private void assertPartStatus(RequisitionDO requisition) {
        assertEquals(RequisitionStatusEnum.PARTIAL_RETURNED.getStatus(), requisition.getStatus(),
                "未全部退完时状态应为部分回退(5)");
    }

    /**
     * 构造建单请求（申请人显式指定 1，避免依赖登录态——单测无 SecurityContext）
     */
    private RequisitionSaveReqVO buildCreateReqVO(Long projectId, RequisitionSaveReqVO.Item... items) {
        RequisitionSaveReqVO reqVO = new RequisitionSaveReqVO();
        reqVO.setProjectId(projectId);
        reqVO.setApplicantUserId(1L);
        reqVO.setItems(new ArrayList<>(List.of(items)));
        return reqVO;
    }

    private RequisitionSaveReqVO.Item item(Long materialId, String quantity) {
        RequisitionSaveReqVO.Item item = new RequisitionSaveReqVO.Item();
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal(quantity));
        return item;
    }

    /**
     * 构造退料请求
     */
    private RequisitionReturnReqVO buildReturnReqVO(Long requisitionId, RequisitionReturnReqVO.Item... items) {
        RequisitionReturnReqVO reqVO = new RequisitionReturnReqVO();
        reqVO.setRequisitionId(requisitionId);
        reqVO.setItems(new ArrayList<>(List.of(items)));
        return reqVO;
    }

    private RequisitionReturnReqVO.Item retItem(Long materialId, String quantity) {
        RequisitionReturnReqVO.Item item = new RequisitionReturnReqVO.Item();
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal(quantity));
        return item;
    }

    /**
     * 预置库存行（直接插 mapper：不产生流水，保证「流水条数」类断言精确）
     */
    private void insertStock(Long projectId, Long materialId, String availableQty) {
        materialStockMapper.insert(MaterialStockDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .availableQty(new BigDecimal(availableQty))
                .lockedQty(BigDecimal.ZERO)
                .version(0)
                .build());
    }

    private MaterialStockDO stockOf(Long projectId, Long materialId) {
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        assertNotNull(stock, String.format("库存行应存在：projectId=%s materialId=%s", projectId, materialId));
        return stock;
    }

    /**
     * 一致性断言：`available_qty == 期初库存 + SUM(stock_log.quantity)`（与 P4 的同名 helper 同口径）
     */
    private void assertBalanceConsistent(Long projectId, Long materialId, String opening, String expected) {
        MaterialStockDO stock = stockOf(projectId, materialId);
        BigDecimal logSum = stockLogMapper.selectSumQuantity(projectId, materialId);
        assertNotNull(logSum, "SUM(quantity) 不应为 null（SQL 已 COALESCE）");
        BigDecimal derived = new BigDecimal(opening).add(logSum);
        assertEquals(0, stock.getAvailableQty().compareTo(derived),
                String.format("期初 + 流水之和 与可用量不一致：期初=%s, SUM(quantity)=%s, 推导期末=%s, 实际 available_qty=%s",
                        opening, logSum, derived, stock.getAvailableQty()));
        assertQty(expected, stock.getAvailableQty());
        assertQty(expected, derived);
    }

    /**
     * 数量断言：只比数值不比 scale
     */
    private void assertQty(String expected, BigDecimal actual) {
        assertQty(expected, actual, null);
    }

    private void assertQty(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, "数量不应为 null" + (message == null ? "" : "：" + message));
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                (message == null ? "" : message + "；") + "数量不匹配：期望 " + expected + "，实际 " + actual);
    }

}
