package com.buildcloud.material.service.requisition;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionApprovalStatusRespVO;
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
import com.buildcloud.material.service.requisition.mq.RequisitionDeductLocalTransactionService;
import com.buildcloud.material.service.requisition.mq.RequisitionDeductMessageProducer;
import com.buildcloud.material.service.requisition.mq.RequisitionTimeoutMessageProducer;
import com.buildcloud.material.service.requisition.strategy.RequisitionSubmitHandlerFactory;
import com.buildcloud.material.service.requisition.strategy.RequisitionV1SubmitHandler;
import com.buildcloud.material.service.requisition.strategy.RequisitionV2SubmitHandler;
import com.buildcloud.material.service.stock.StockPredeductService;
import com.buildcloud.material.service.stock.StockRollbackServiceImpl;
import com.buildcloud.material.service.stock.redis.StockRollbackResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_CANNOT_RETURN;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_RETURN_QUANTITY_EXCEEDED;
import static com.buildcloud.material.enums.requisition.RequisitionBpmConstants.PROCESS_KEY_REQUISITION_APPROVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * W4-P6 补强用例：覆盖 P4/P5 留下的**盲点**（不是重复既有用例）
 *
 * ========== 为什么单独一个测试类（而不是继续往 P5 的类里加） ==========
 *
 * P5 的 {@link RequisitionApprovalServiceImplTest} 已经 40 个用例，覆盖的是「单条链路正确」；
 * 本类是 W4 收官的**防守型**用例，按「跨步骤 / 跨边界」组织，每一条都能独立说明一个曾未被钉住的风险。
 * 分开的第二个理由是**可读性**：面试/复核时「P6 补了什么」应当一眼可见。
 *
 * ========== 本类覆盖的 8 类盲点（对应 plan-w4-material-v1 Phase 6 任务书） ==========
 *
 * | # | 盲点 | 用例 |
 * | --- | --- | --- |
 * | 1 | 退料**多次**：第一次退 20、再退 30，returned_qty 累加、状态 5→4 | testReturn_twice_returnedQtyAccumulates_status5Then4 |
 * | 2 | 退料跨行累加：多物料最终一起判全退 | testReturn_multiItemsDifferentRounds_accumulatePerLine |
 * | 3 | 审批**中间态**：会签只过 1 人 → 业务状态与库存都不变 | testCoSign_oneOfTwoApproved_businessStateAndStockUnchanged |
 * | 4 | 中间态再驳回：剩下那位驳回 → 整单取消 + 回补 | testCoSign_partialApprovedThenReject_restoresStock |
 * | 5 | **跨项目库存隔离**：A/B 各扣各的，互不影响 | testStockIsolation_sameMaterialTwoProjects_independent |
 * | 6 | 跨项目退料只回本项目的库存 | testStockIsolation_returnOnlyRestoresOwnProject |
 * | 7 | A 类「B 类防线仍在」：H2 里 uk_project_material 重复插入抛 DuplicateKeyException | testBClassIndex_stillBlocksDuplicateStockRow |
 * | 8 | 退料**数量不变量**：Σ退料量 == 库存回补量，且 SUM(quantity)==available_qty | testReturn_multiRound_perfectBalanceInvariant |
 *
 * ========== 与其他类共享的约定（照抄，勿各写一套） ==========
 *
 * - 不加 {@code @Transactional}：Service 内部自带事务，用例要在**真实提交后**读库断言（见 P5 类注释）；
 * - H2 建表用 {@code /sql/requisition-test-tables.sql}（前置 DROP+CREATE，天然隔离）；
 * - 时间断言用 1 毫秒容差（L3-11）；至少一条断言直接比 Service **返回值**与其库值（L3-10）；
 * - 涉及库存的都用 {@code assertBalanceConsistent}：`available_qty == 期初 + SUM(stock_log.quantity)`。
 *
 * @author buildcloud
 */
@Import({RequisitionApprovalServiceImpl.class, RequisitionServiceImpl.class,
        RequisitionV1SubmitHandler.class, RequisitionV2SubmitHandler.class,
        RequisitionSubmitHandlerFactory.class, StockRollbackServiceImpl.class})
@Sql(scripts = "/sql/requisition-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class RequisitionP6BlindSpotTest extends BaseDbUnitTest {

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

    @MockBean
    private BpmProcessInstanceApi processInstanceApi;

    /**
     * W5-P2 起 {@code RequisitionV2SubmitHandler} 依赖 {@code StockPredeductService}（Redis 预扣），
     * 而本类的 `@Import` 列表里包含它（V1/V2 两实现必须常驻，工厂才能分发）。
     *
     * 本类测的是 V1 链路（行锁同步扣减）与审批/退料，**不涉及 Redis**，故用 `@MockBean` 占位；
     * V2 的真实 Redis 行为由 {@code RequisitionV2SubmitHandlerTest} 与端到端集成测试覆盖。
     */
    @MockBean
    private StockPredeductService stockPredeductService;
    /**
     * V2 处理器还依赖消息发送器与本地事务服务（@Resource 注入，缺 bean 会让整个容器起不来）。
     * 本类测 V1/审批链路，不涉及 MQ，用 @MockBean 占位即可。
     */
    @MockBean
    private RequisitionDeductMessageProducer requisitionDeductMessageProducer;

    @MockBean
    private RequisitionDeductLocalTransactionService requisitionDeductLocalTransactionService;

    /**
     * W5-P3 起 {@code RequisitionApprovalServiceImpl#submitApproval} 末尾会发一条超时关单延迟消息。
     * 本类不关心 MQ，用 `@MockBean` 占位（默认返回 false = 未发送，只打 error 日志，不影响提交结果）。
     */
    @MockBean
    private RequisitionTimeoutMessageProducer timeoutMessageProducer;

    /**
     * W5-P3 起「驳回 / 取消 / 超时关单」的 DB 回补由统一回滚组件完成，组件会**无条件**调用
     * {@code StockPredeductService#rollback} 还原 Redis 预扣（明细不存在 = NOT_FOUND，无副作用）。
     *
     * 本类的 `@MockBean` 替身默认对 `rollback(...)` 返回 `null`——而组件的"是否结算清楚"判断
     * 依赖它的枚举值。这里给替身补一个与真实现一致的默认应答：NOT_FOUND（没有预扣过），
     * 与 P5 类里"不涉及 Redis"的前提保持一致。**真正的 Redis 语义**由
     * {@code RequisitionTimeoutCloseServiceTest}（替身按语义应答）与
     * {@code StockRollbackServiceIntegrationTest}（连真 Redis）覆盖。
     */
    @BeforeEach
    public void stubRedisRollbackDefault() {
        when(stockPredeductService.rollback(any(), any(), any(), any()))
                .thenReturn(StockRollbackResult.NOT_FOUND);
    }

    // =================================================================================
    // 盲点 1：退料多次 —— 第一次退 20、再退 30（任务书指定数字）
    // =================================================================================

    @Test
    public void testReturn_twice_returnedQtyAccumulates_status5Then4() {
        // 准备：库存 100 → 申领 50（扣到 50）→ 审批通过 → 出库
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "50.0000")));
        approveRequisition(id, List.of(1L, 2L));
        approvalService.outbound(id);
        assertQty("50", stockOf(1L, 1L).getAvailableQty());

        // ===== 第一次退 20：部分回退 =====
        RequisitionDO first = approvalService.returnMaterials(
                buildReturnReqVO(id, retItem(1L, "20.0000")));

        // 断言 1（L3-10：直接断言 Service 返回值，不查库）
        assertEquals(RequisitionStatusEnum.PARTIAL_RETURNED.getStatus(), first.getStatus(),
                "第一次退 20（未退完 50）返回值应为部分回退(5)");
        assertNotNull(first.getActualReturnTime(), "返回值必须带本次退料时间");

        // 断言 2：返回值 == 库值（逐字段）
        RequisitionDO db1 = requisitionMapper.selectById(id);
        assertEquals(db1.getStatus(), first.getStatus());
        assertTrue(Duration.between(db1.getActualReturnTime(), first.getActualReturnTime()).abs().toMillis() < 1,
                "actualReturnTime 返回值与库值应一致（容差 1ms，L3-11）");
        // 断言 3：明细 returned_qty 累加到 20，可退量 30
        RequisitionItemDO item1 = onlyItem(id);
        assertQty("20", item1.getReturnedQty(), "第一次退料后 returned_qty 应为 20");
        // 断言 4：库存回补 50 → 70，流水追加 biz_type=3 (+20)
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        List<StockLogDO> logs1 = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(2, logs1.size(), "此时应只有 2 条流水：申领扣减(-50) + 退回(+20)");
        assertEquals(StockBizTypeEnum.RETURN.getBizType(), logs1.get(1).getBizType());
        assertQty("20", logs1.get(1).getQuantity());
        assertQty("70", logs1.get(1).getBalanceAfter());

        // ===== 第二次再退 30（累计 50）：全退 → 已完成 =====
        RequisitionDO second = approvalService.returnMaterials(
                buildReturnReqVO(id, retItem(1L, "30.0000")));

        // 断言 5（L3-10）：返回值 == 已完成(4)，且与库一致
        assertEquals(RequisitionStatusEnum.COMPLETED.getStatus(), second.getStatus(),
                "累计退完 50 后返回值应为已完成(4)");
        assertEquals(requisitionMapper.selectById(id).getStatus(), second.getStatus());
        // 断言 6：returned_qty 累加到 50（**累加**而不是被覆盖成 30）
        RequisitionItemDO item2 = onlyItem(id);
        assertQty("50", item2.getReturnedQty(), "第二次退料后 returned_qty 应为累加值 50");
        assertEquals(0, item2.getReturnedQty().compareTo(item2.getQuantity()),
                "全退后 returned_qty 必须等于 quantity");
        // 断言 7：库存回到 100，流水 3 条（-50 / +20 / +30）且 balance_after 递增正确
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        List<StockLogDO> logs2 = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(3, logs2.size());
        assertQty("70", logs2.get(1).getBalanceAfter());
        assertQty("100", logs2.get(2).getBalanceAfter());
        // 断言 8：净额归零（该单对库存的净影响为 0）
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
        assertBalanceConsistent(1L, 1L, "100", "100");
        // 断言 9：已完成(4) 是终态，不能再退
        assertServiceException(() -> approvalService.returnMaterials(
                        buildReturnReqVO(id, retItem(1L, "1.0000"))),
                REQUISITION_CANNOT_RETURN, RequisitionStatusEnum.COMPLETED.getName());
    }

    // =================================================================================
    // 盲点 2：多行明细分轮退料 —— 每行 returned_qty 各自累加，全退判定看**所有行**
    // =================================================================================

    @Test
    public void testReturn_multiItemsDifferentRounds_accumulatePerLine() {
        // 准备：物料1 库存 100、物料2 库存 100；申领 物料1×30、物料2×40
        insertStock(1L, 1L, "100.0000");
        insertStock(1L, 2L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L,
                item(1L, "30.0000"), item(2L, "40.0000")));
        approveRequisition(id, List.of(1L));
        approvalService.outbound(id);
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        assertQty("60", stockOf(1L, 2L).getAvailableQty());

        // 第 1 轮：只退物料1 的 10 → 物料2 未动，仍部分回退
        RequisitionDO r1 = approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "10.0000")));
        assertEquals(RequisitionStatusEnum.PARTIAL_RETURNED.getStatus(), r1.getStatus());
        assertQty("10", itemOf(id, 1L).getReturnedQty());
        assertQty("0", itemOf(id, 2L).getReturnedQty(), "未退的行 returned_qty 必须保持 0");

        // 第 2 轮：同一请求里退 物料1 的剩余 20 + 物料2 的 40 → 两行都满，判全退
        RequisitionDO r2 = approvalService.returnMaterials(buildReturnReqVO(id,
                retItem(1L, "20.0000"), retItem(2L, "40.0000")));
        assertEquals(RequisitionStatusEnum.COMPLETED.getStatus(), r2.getStatus(),
                "两行都退完才应判已完成(4)");
        assertQty("30", itemOf(id, 1L).getReturnedQty());
        assertQty("40", itemOf(id, 2L).getReturnedQty());

        // 库存分别回到 100/100；两条线各自净额归零
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        assertQty("100", stockOf(1L, 2L).getAvailableQty());
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 2L));
        // 物料1：扣减(-30) + 退回(+10) + 退回(+20)；物料2：扣减(-40) + 退回(+40)
        assertEquals(3, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());
        assertEquals(2, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 2L).size());
        assertBalanceConsistent(1L, 1L, "100", "100");
        assertBalanceConsistent(1L, 2L, "100", "100");
    }

    // =================================================================================
    // 盲点 3：审批中间态（会签只过 1/2）—— 业务状态不变、库存不变、单据可查
    // =================================================================================

    @Test
    public void testCoSign_oneOfTwoApproved_businessStateAndStockUnchanged() {
        // 准备：库存 200，申领 60 → 会签人 [1, 100]
        insertStock(1L, 1L, "200.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "60.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L, 100L));
        // 提交审批时已扣（V1 口径：提交即扣减）
        assertQty("140", stockOf(1L, 1L).getAvailableQty(), "提交审批即扣减（与 V1 submit 同段代码）");

        // ===== 第 1 位审批人通过：业务侧**没有任何回调**（Flowable 并行多实例未收敛） =====
        // 这里用「不调用 handleApprovalResult」表达：引擎没结束实例就不会发事件（L2-16）。

        // 断言 1：业务状态仍是已提交(1)、审批中(1)
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(), db.getStatus(),
                "1/2 会签通过时业务状态不得推进（否则等于单人审批就能放行）");
        assertEquals(RequisitionApprovalStatusEnum.RUNNING.getStatus(), db.getApprovalStatus());
        // 断言 2：库存与流水完全没动（没有第二次扣减，也没有任何回补）
        assertQty("140", stockOf(1L, 1L).getAvailableQty(), "审批中间态不得触碰库存");
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size(),
                "审批中间态不应产生第二条流水");
        // 断言 3：审批详情接口在中间态下的可读性（前端要显示「还差谁」）
        RequisitionApprovalStatusRespVO vo = approvalService.getApprovalStatus(id);
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(), vo.getStatus());
        assertEquals(RequisitionApprovalStatusEnum.RUNNING.getStatus(), vo.getApprovalStatus());
        assertEquals("审批中", vo.getApprovalStatusName());
        assertEquals(List.of(1L, 100L), vo.getApproverUserIds(), "中间态必须能读回全部会签人");
        assertEquals("会签审批中，全部审批人通过后才会放行", vo.getReason());
        assertEquals(inst, vo.getProcessInstanceId());
        // 断言 4：中间态**不能**出库（料不能先出）
        assertServiceException(() -> approvalService.outbound(id),
                com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_CANNOT_OUTBOUND,
                RequisitionStatusEnum.SUBMITTED.getName());
        // 断言 5：全票通过后才推进到已扣减(2)，库存仍**不变**
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "全票通过");
        RequisitionDO after = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(), after.getStatus());
        assertEquals(RequisitionApprovalStatusEnum.APPROVE.getStatus(), after.getApprovalStatus());
        assertQty("140", stockOf(1L, 1L).getAvailableQty(), "通过也不碰库存（提交时已扣）");
        assertBalanceConsistent(1L, 1L, "200", "140");
    }

    // =================================================================================
    // 盲点 4：中间态之后被驳回 —— 整单取消 + 库存一次性回补（不多补）
    // =================================================================================

    @Test
    public void testCoSign_partialApprovedThenReject_restoresStock() {
        // 准备：库存 120，申领 40，会签 3 人
        insertStock(1L, 1L, "120.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "40.0000")));
        String inst = prepareSubmittedApproval(id, List.of(1L, 100L, 101L));
        assertQty("80", stockOf(1L, 1L).getAvailableQty());

        // 前两人通过（业务侧无回调），第三人驳回 → 整单取消
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.REJECT.getStatus(), "第三人驳回");

        // 断言 1：状态 9 / 审批 3
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), db.getStatus());
        assertEquals(RequisitionApprovalStatusEnum.REJECT.getStatus(), db.getApprovalStatus());
        // 断言 2：库存回到 120（只补一次，不是补三次）
        assertQty("120", stockOf(1L, 1L).getAvailableQty(), "会签部分通过后驳回只回补一次");
        // 断言 3：流水 2 条：扣减(-40) + 冲正(+40)，净额 0
        assertEquals(2, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
        // 断言 4：会签人仍留在单据上（审计要看当时是谁会签）
        assertEquals("1,100,101", db.getApprovers());
        // 断言 5：迟到的「通过」回调不能把已取消的单推成已扣减
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "迟到");
        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), requisitionMapper.selectById(id).getStatus());
        assertQty("120", stockOf(1L, 1L).getAvailableQty(), "迟到回调不得再动库存");
    }

    // =================================================================================
    // 盲点 5：跨项目库存隔离 —— 同一物料在两个项目各自扣减，互不影响
    // =================================================================================

    @Test
    public void testStockIsolation_sameMaterialTwoProjects_independent() {
        // 准备：物料 1 在项目 A 库存 100、项目 B 库存 300（同一个 material_id，两行库存）
        insertStock(1L, 1L, "100.0000");
        insertStock(2L, 1L, "300.0000");

        // 项目 A 申领 30 并走完全链路到出库
        Long reqA = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        approveRequisition(reqA, List.of(1L));
        approvalService.outbound(reqA);

        // 断言 1：A 扣减、B 完全不动
        assertQty("70", stockOf(1L, 1L).getAvailableQty(), "项目 A 应扣减 30");
        assertQty("300", stockOf(2L, 1L).getAvailableQty(), "项目 B 的库存不得受影响（跨项目隔离）");
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(2L, 1L).isEmpty(),
                "项目 B 不应产生任何流水");

        // 项目 B 申领 250
        Long reqB = requisitionService.createRequisition(buildCreateReqVO(2L, item(1L, "250.0000")));
        approveRequisition(reqB, List.of(100L));
        approvalService.outbound(reqB);

        // 断言 2：两边各自正确，互不影响
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        assertQty("50", stockOf(2L, 1L).getAvailableQty());
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(2L, 1L).size());
        // 断言 3：两笔流水的 biz_no 是各自单号（按单号追溯不会串项目）
        RequisitionDO dbA = requisitionMapper.selectById(reqA);
        RequisitionDO dbB = requisitionMapper.selectById(reqB);
        assertEquals(dbA.getReqNo(), stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).get(0).getBizNo());
        assertEquals(dbB.getReqNo(), stockLogMapper.selectListByProjectIdAndMaterialId(2L, 1L).get(0).getBizNo());
        // 断言 4：两边一致性不变量各自成立
        assertBalanceConsistent(1L, 1L, "100", "70");
        assertBalanceConsistent(2L, 1L, "300", "50");
    }

    // =================================================================================
    // 盲点 6：跨项目退料只回本项目库存
    // =================================================================================

    @Test
    public void testStockIsolation_returnOnlyRestoresOwnProject() {
        // 准备：项目 A / B 各 200；各申领 50 并出库
        insertStock(1L, 1L, "200.0000");
        insertStock(2L, 1L, "200.0000");
        Long reqA = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "50.0000")));
        approveRequisition(reqA, List.of(1L));
        approvalService.outbound(reqA);
        Long reqB = requisitionService.createRequisition(buildCreateReqVO(2L, item(1L, "50.0000")));
        approveRequisition(reqB, List.of(1L));
        approvalService.outbound(reqB);
        assertQty("150", stockOf(1L, 1L).getAvailableQty());
        assertQty("150", stockOf(2L, 1L).getAvailableQty());

        // 只退项目 A 的 20
        approvalService.returnMaterials(buildReturnReqVO(reqA, retItem(1L, "20.0000")));

        // 断言：A 回补到 170；B 仍是 150（退料只认单据上的 projectId）
        assertQty("170", stockOf(1L, 1L).getAvailableQty(), "退料必须回到单据所属项目");
        assertQty("150", stockOf(2L, 1L).getAvailableQty(), "另一个项目的库存不得被退料影响");
        // 断言：退回流水只落在 A（biz_type=3 用单据 reqNo）
        List<StockLogDO> logsA = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(2, logsA.size());
        assertEquals(StockBizTypeEnum.RETURN.getBizType(), logsA.get(1).getBizType());
        assertEquals(requisitionMapper.selectById(reqA).getReqNo(), logsA.get(1).getBizNo());
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(2L, 1L).size(),
                "项目 B 只有申领扣减那一条流水");
    }

    // =================================================================================
    // 盲点 7：B 类唯一索引（结构唯一）仍是并发防线 —— 与 09 脚本 drop 的 A 类形成对照
    // =================================================================================

    @Test
    public void testBClassIndex_stillBlocksDuplicateStockRow() {
        // bc_material_stock 的 uk_project_material 属 ADR-004 **B 类**：结构唯一，09 脚本不动它。
        // 这条用例钉住「并发首插不会造出两行库存」——那是 V1 行锁热点成立的前提。
        insertStock(1L, 1L, "10.0000");

        // 第二次插入同 (project, material) → 必须被唯一约束拦下（不是静默插出第二行）
        assertThrows(DuplicateKeyException.class,
                () -> insertStock(1L, 1L, "20.0000"),
                "B 类唯一索引必须拦住重复库存行（否则行锁锁不到同一行，并发用例会假绿）");

        // 断言：库里仍只有一行，且数量是原值（失败插入没有留下半条修改）
        assertEquals(1, materialStockMapper.selectCount(MaterialStockDO::getProjectId, 1L));
        assertQty("10", stockOf(1L, 1L).getAvailableQty());
        // 对照：不同 project 或不同 material 是合法的新行（B 类只约束三元组）
        insertStock(1L, 2L, "30.0000");
        insertStock(2L, 1L, "40.0000");
        assertEquals(1, materialStockMapper.selectCount(MaterialStockDO::getMaterialId, 2L));
    }

    // =================================================================================
    // 盲点 8：多轮退料的整体对账 —— 退多少补多少，SUM(quantity) == available_qty
    // =================================================================================

    @Test
    public void testReturn_multiRound_perfectBalanceInvariant() {
        // 准备：库存 500，申领 200 → 审批 → 出库
        insertStock(1L, 1L, "500.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "200.0000")));
        approveRequisition(id, List.of(1L, 2L));
        approvalService.outbound(id);
        assertQty("300", stockOf(1L, 1L).getAvailableQty());

        // 三轮部分退 + 一次超量被拒（拒的那次必须整体回滚，不影响余额）
        BigDecimal[] rounds = {new BigDecimal("20.0000"), new BigDecimal("35.5000"), new BigDecimal("14.5000")};
        BigDecimal returned = BigDecimal.ZERO;
        for (BigDecimal qty : rounds) {
            RequisitionDO result = approvalService.returnMaterials(
                    buildReturnReqVO(id, retItem(1L, qty.toPlainString())));
            returned = returned.add(qty);
            // 每轮都断言返回值与库值一致（L3-10），且仍是部分回退（累计 70 < 200）
            assertEquals(RequisitionStatusEnum.PARTIAL_RETURNED.getStatus(), result.getStatus());
            assertEquals(requisitionMapper.selectById(id).getStatus(), result.getStatus());
            // 每轮结束都校验：库存 == 期初(500) + SUM(流水) 且 == 300 + 累计退回
            BigDecimal expectedStock = new BigDecimal("300").add(returned);
            assertQty(expectedStock.toPlainString(), stockOf(1L, 1L).getAvailableQty(),
                    String.format("第 %s 轮退料后库存应等于 300 + 累计退回 %s", rounds.length, returned));
            assertBalanceConsistent(1L, 1L, "500", expectedStock.toPlainString());
        }

        // 超量退一次：可退 200-70=130，退 131 → 报错且**余额不变**
        assertServiceException(() -> approvalService.returnMaterials(
                        buildReturnReqVO(id, retItem(1L, "131.0000"))),
                REQUISITION_RETURN_QUANTITY_EXCEEDED, 1L, "130.0000", "131.0000");
        assertQty("370", stockOf(1L, 1L).getAvailableQty(), "超量失败必须整体回滚，余额不动");
        assertQty("70", onlyItem(id).getReturnedQty(), "超量失败不得推进 returned_qty");

        // 退掉剩余 130 → 已完成(4)
        RequisitionDO last = approvalService.returnMaterials(buildReturnReqVO(id, retItem(1L, "130.0000")));
        assertEquals(RequisitionStatusEnum.COMPLETED.getStatus(), last.getStatus());
        // 终态守恒：库存回到 500，全部流水净额 0，逐笔 balance_after 单调递增到 500
        assertQty("500", stockOf(1L, 1L).getAvailableQty());
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        BigDecimal running = new BigDecimal("500");
        int returnLogCount = 0;
        for (StockLogDO log : logs) {
            running = running.add(log.getQuantity());
            assertQty(running.toPlainString(), log.getBalanceAfter(),
                    "逐笔 balance_after 必须等于「期初 + 累计流水」（对账断点检测）");
            if (StockBizTypeEnum.RETURN.getBizType().equals(log.getBizType())) {
                returnLogCount++;
            }
        }
        assertEquals(4, returnLogCount, "三轮部分退 + 一轮收尾 = 4 条 biz_type=3 流水");
        assertEquals(5, logs.size(), "流水总数 = 1 条扣减 + 4 条退回");
    }

    // =================================================================================
    // 辅助方法（与 P5 类同口径，避免各自造数据姿势不一致）
    // =================================================================================

    private String prepareSubmittedApproval(Long id, List<Long> approvers) {
        String inst = PROCESS_KEY_REQUISITION_APPROVE + ":1:p6-" + id + "-" + approvers.size();
        when(processInstanceApi.createProcessInstance(any(), any(BpmProcessInstanceCreateReqDTO.class)))
                .thenReturn(CommonResult.success(inst));
        approvalService.submitApproval(id, approvers);
        return inst;
    }

    private void approveRequisition(Long id, List<Long> approvers) {
        String inst = prepareSubmittedApproval(id, approvers);
        approvalService.handleApprovalResult(id, inst, BpmProcessInstanceStatusEnum.APPROVE.getStatus(), "通过");
    }

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

    private RequisitionItemDO onlyItem(Long requisitionId) {
        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(requisitionId);
        assertEquals(1, items.size(), "本用例的申领单只有一行明细");
        return items.get(0);
    }

    private RequisitionItemDO itemOf(Long requisitionId, Long materialId) {
        return requisitionItemMapper.selectListByRequisitionId(requisitionId).stream()
                .filter(item -> materialId.equals(item.getMaterialId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("明细应存在：materialId=" + materialId));
    }

    /**
     * 一致性断言：`available_qty == 期初 + SUM(stock_log.quantity)`，且等于期望值
     */
    private void assertBalanceConsistent(Long projectId, Long materialId, String opening, String expected) {
        MaterialStockDO stock = stockOf(projectId, materialId);
        BigDecimal logSum = stockLogMapper.selectSumQuantity(projectId, materialId);
        assertNotNull(logSum, "SUM(quantity) 不应为 null（SQL 已 COALESCE）");
        BigDecimal derived = new BigDecimal(opening).add(logSum);
        assertEquals(0, stock.getAvailableQty().compareTo(derived),
                String.format("期初 + 流水之和 与可用量不一致：期初=%s, SUM=%s, 推导期末=%s, 实际=%s",
                        opening, logSum, derived, stock.getAvailableQty()));
        assertQty(expected, stock.getAvailableQty());
        assertQty(expected, derived);
    }

    private void assertQty(String expected, BigDecimal actual) {
        assertQty(expected, actual, null);
    }

    private void assertQty(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, "数量不应为 null" + (message == null ? "" : "：" + message));
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                (message == null ? "" : message + "；") + "数量不匹配：期望 " + expected + "，实际 " + actual);
    }

}
