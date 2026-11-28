package com.buildcloud.material.service.requisition;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionPageReqVO;
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
import com.buildcloud.material.enums.requisition.RequisitionModeEnum;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import com.buildcloud.material.service.requisition.mq.RequisitionDeductLocalTransactionService;
import com.buildcloud.material.service.requisition.mq.RequisitionDeductMessageProducer;
import com.buildcloud.material.service.requisition.strategy.RequisitionSubmitHandlerFactory;
import com.buildcloud.material.service.requisition.strategy.RequisitionV1SubmitHandler;
import com.buildcloud.material.service.requisition.strategy.RequisitionV2SubmitHandler;
import com.buildcloud.material.service.stock.StockPredeductService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_CANNOT_CANCEL;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_ITEM_EMPTY;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_ITEM_MATERIAL_DUPLICATE;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_MODE_NOT_IMPLEMENTED;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_STATUS_INVALID;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_ENOUGH;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequisitionServiceImpl} 的单元测试类（W4-P4：申领 V1 同步链路）
 *
 * 风格照 {@link com.buildcloud.material.service.stock.StockServiceImplTest}：
 * 继承 {@link BaseDbUnitTest}（H2 内存库 + MyBatis），@Import 被测 Service 与其依赖；
 * 四张表见 src/test/resources/sql/requisition-test-tables.sql（每次测试方法前置 DROP+CREATE）。
 *
 * ========== 覆盖点与 plan-w4-material-v1 Phase 4 验收标准的对应关系 ==========
 *
 * | 验收标准 | 用例 |
 * | --- | --- |
 * | 申领→扣减→流水一致 | testSubmit_success_deductStockAndWriteNegativeLog / testSubmit_multiItems_allDeducted |
 * | 库存不足事务回滚无脏数据 | testSubmit_stockNotEnough_rollbackEverything |
 * | 状态机非法流转被拒 | testSubmitStatusMachine_* / testCanTransfer_table |
 * | V1/V2 开关可切换（V2 未实现） | testSubmitV2Mode_throwsNotImplemented / testFactoryModeSwitch |
 * | 并发不超卖（V1 行锁串行化） | testSubmitConcurrent_noOversell_underRowLock |
 * | 取消回补 + 冲正流水 | testCancel_* |
 *
 * ========== 为什么不给测试类加 @Transactional（关键，别"优化"） ==========
 *
 * 1. **并发用例会失效**：测试方法上的 `@Transactional` 会把整个用例包在一个事务里，
 *    其他线程（`Executors` 起的那 10 个）根本看不到主线程未提交的数据（H2/MySQL 的
 *    默认隔离级别下都如此），并发用例会变成「10 个线程都查不到库存 → 全失败」，假绿或假红；
 * 2. **看不到行锁**：`SELECT ... FOR UPDATE` 的锁是**事务级**的，若外层再套一个测试事务，
 *    所有内层 Service 事务都变成「加入外层事务」（REQUIRED 传播），行锁直到测试方法结束才释放，
 *    多个用例串行化，测不出真实的并发行为；
 * 3. **回滚断言失真**：库存不足用例要断言「事务回滚后 stock 与流水都没变」。
 *    如果外层有测试事务，Service 的 `@Transactional` 提交点不存在（只是 savepoint），
 *    断言看到的可能仍是"事务内的中间态"，而不是用户能观察到的终态。
 *
 * 每个用例自己用 Service（内部 `@Transactional`）产生真实提交，表由 `@Sql` 前置重建做隔离。
 *
 * @author buildcloud
 */
@Import({RequisitionServiceImpl.class,
        RequisitionV1SubmitHandler.class,
        RequisitionV2SubmitHandler.class,
        RequisitionSubmitHandlerFactory.class})
@Sql(scripts = "/sql/requisition-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class RequisitionServiceImplTest extends BaseDbUnitTest {

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

    @Resource
    private RequisitionSubmitHandlerFactory submitHandlerFactory;

    /**
     * W5-P2 起 {@code RequisitionV2SubmitHandler} 依赖 {@code StockPredeductService}（Redis 预扣），
     * 而本类的 `@Import` 列表里包含它（V1/V2 两实现必须常驻，工厂才能分发）。
     *
     * 本类测的是 V1 同步链路与**开关分发**，不涉及 Redis，故用 `@MockBean` 占位。
     * 注意：与开关相关的用例（如「切到 V2 后行为不同」）若将来需要 Redis，
     * 应当换成 {@code RequisitionV2SubmitHandlerTest} 里的内存 Stub 口径，而不是放开这个 mock。
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
     * 每个用例前重置模式为默认 V1
     *
     * 为什么需要：{@link #testSubmitV2Mode_throwsNotImplemented} 会用
     * `TestPropertySource`/`SpringApplicationBuilder` 之外的办法改配置（见该用例），
     * 而 Spring 的 Environment 在同一个 application context 里是**共享**的。
     * 这里做一次显式复位，保证用例之间不串模式（顺序无关）。
     */
    @BeforeEach
    public void resetModeToDefault() {
        System.setProperty(RequisitionSubmitHandlerFactory.CONFIG_KEY, RequisitionModeEnum.V1.getMode());
    }

    // ========== 一、建单（草稿） ==========

    @Test
    public void testCreate_draftWithItems_noStockTouched() {
        // 预置库存 100
        insertStock(1L, 1L, "100.0000");

        // 建单：项目 1，物料 1 申领 10
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));

        // 断言 1：单据为草稿、有单号、审批未开始
        RequisitionDO requisition = requisitionMapper.selectById(id);
        assertNotNull(requisition);
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(), requisition.getStatus());
        assertNotNull(requisition.getReqNo(), "草稿态也应有单号（便于口头指代）");
        assertTrue(requisition.getReqNo().startsWith("REQ-"), "单号应以 REQ- 开头：" + requisition.getReqNo());
        assertEquals(RequisitionApprovalStatusEnum.NOT_START.getStatus(), requisition.getApprovalStatus());
        assertNull(requisition.getTimeoutTime(), "草稿态不应有超时时间（提交时才写）");

        // 断言 2：明细落库
        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(id);
        assertEquals(1, items.size());
        assertEquals(1L, items.get(0).getMaterialId());
        assertQty("10", items.get(0).getQuantity());

        // 断言 3（核心）：建单**不动库存**、不产生流水
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).isEmpty(),
                "草稿态不应产生任何流水");
    }

    @Test
    public void testCreate_multiItems_allPersisted() {
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L,
                item(1L, "10.0000"), item(2L, "20.5000"), item(3L, "0.0001")));

        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(id);
        assertEquals(3, items.size());
        assertQty("10", items.get(0).getQuantity());
        assertQty("20.5", items.get(1).getQuantity());
        assertQty("0.0001", items.get(2).getQuantity());
    }

    @Test
    public void testCreate_itemsEmpty_throws() {
        RequisitionSaveReqVO reqVO = buildCreateReqVO(1L);
        reqVO.setItems(new ArrayList<>());

        assertServiceException(() -> requisitionService.createRequisition(reqVO), REQUISITION_ITEM_EMPTY);
    }

    @Test
    public void testCreate_duplicateMaterial_throws() {
        // 同一物料两行会被 V1 的逐行加锁扣减搞出语义不清的两次校验，故建单即拒
        RequisitionSaveReqVO reqVO = buildCreateReqVO(1L, item(1L, "10.0000"), item(1L, "5.0000"));

        assertServiceException(() -> requisitionService.createRequisition(reqVO),
                REQUISITION_ITEM_MATERIAL_DUPLICATE, 1L);
    }

    // ========== 二、提交：V1 扣减成功（核心正例） ==========

    @Test
    public void testSubmit_success_deductStockAndWriteNegativeLog() {
        // 预置：库存 100，建单申领 30
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));

        // 提交
        RequisitionDO submitted = requisitionService.submitRequisition(id);

        // ===== 断言 1：单据状态 = 已扣减(2)、reqNo / timeoutTime 已写、审批未开始 =====
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(), submitted.getStatus(),
                "V1 提交后状态应为已扣减(2)（中间态已提交(1) 只存在于事务内）");
        RequisitionDO dbRequisition = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(), dbRequisition.getStatus(), "落库状态应为 2");
        assertNotNull(dbRequisition.getReqNo());
        assertNotNull(dbRequisition.getTimeoutTime(), "提交时必须写 timeout_time（W5 延迟消息据此关单）");
        assertTrue(dbRequisition.getTimeoutTime().isAfter(java.time.LocalDateTime.now()),
                "超时时间应在未来（+30 分钟），实际：" + dbRequisition.getTimeoutTime());
        assertEquals(RequisitionApprovalStatusEnum.NOT_START.getStatus(), dbRequisition.getApprovalStatus());

        // ===== 断言 2：库存从 100 扣到 70 =====
        assertQty("70", stockOf(1L, 1L).getAvailableQty());

        // ===== 断言 3：流水正确（biz_type=2、quantity 负数、balance_after=扣减后余额、biz_no=req_no）=====
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(1, logs.size(), "一次提交、一行明细 → 一条流水");
        StockLogDO log = logs.get(0);
        assertEquals(StockBizTypeEnum.REQUISITION_OUT.getBizType(), log.getBizType(), "biz_type 应为 2 申领扣减");
        assertEquals(-1, log.getQuantity().signum(), "扣减流水 quantity 必须为负数，实际：" + log.getQuantity());
        assertQty("-30", log.getQuantity());
        assertQty("70", log.getBalanceAfter());
        assertEquals(dbRequisition.getReqNo(), log.getBizNo(), "biz_no 必须等于申领单号（可追溯/幂等键）");

        // ===== 断言 4：一致性 available_qty == 期初 100 + SUM(stock_log.quantity) =====
        assertBalanceConsistent(1L, 1L, "100", "70");
    }

    @Test
    public void testSubmit_multiItems_allDeducted_independently() {
        // 两行明细、两个物料：各自的库存行独立扣减，流水各一条
        insertStock(1L, 1L, "100.0000");
        insertStock(1L, 2L, "50.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L,
                item(1L, "30.0000"), item(2L, "20.0000")));

        requisitionService.submitRequisition(id);

        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        assertQty("30", stockOf(1L, 2L).getAvailableQty());
        assertBalanceConsistent(1L, 1L, "100", "70");
        assertBalanceConsistent(1L, 2L, "50", "30");

        // 两条流水、biz_no 都是本单号
        RequisitionDO req = requisitionMapper.selectById(id);
        List<StockLogDO> logs1 = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        List<StockLogDO> logs2 = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 2L);
        assertEquals(1, logs1.size());
        assertEquals(1, logs2.size());
        assertEquals(req.getReqNo(), logs1.get(0).getBizNo());
        assertEquals(req.getReqNo(), logs2.get(0).getBizNo());
        assertQty("-30", logs1.get(0).getQuantity());
        assertQty("-20", logs2.get(0).getQuantity());
    }

    @Test
    public void testSubmit_decimalPrecisionPreserved() {
        // 吨/立方类物料需要 4 位小数，验证不被静默截断
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "12.3456")));

        requisitionService.submitRequisition(id);

        assertQty("87.6544", stockOf(1L, 1L).getAvailableQty());
        assertBalanceConsistent(1L, 1L, "100", "87.6544");
        assertQty("-12.3456",
                stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).get(0).getQuantity());
    }

    @Test
    public void testSubmit_exactAvailable_allowedToZero() {
        // 边界：申领量 == 可用量（100 - 100 = 0）必须允许（不能写成 available > qty）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "100.0000")));

        requisitionService.submitRequisition(id);

        assertQty("0", stockOf(1L, 1L).getAvailableQty());
        assertBalanceConsistent(1L, 1L, "100", "0");
    }

    // ========== 三、提交：库存不足 → 整单回滚，不留脏数据 ==========

    @Test
    public void testSubmit_stockNotEnough_rollbackEverything() {
        // 预置：库存 10，建单申领 50
        insertStock(1L, 1L, "10.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "50.0000")));

        // 提交必须失败，错误码带上可用量与需求量
        assertServiceException(() -> requisitionService.submitRequisition(id),
                STOCK_NOT_ENOUGH, "10.0000", "50.0000");

        // ===== 核心断言：整体回滚，什么都没变 =====
        // 1. 库存仍是 10
        assertQty("10", stockOf(1L, 1L).getAvailableQty());
        // 2. 没有产生任何流水（连扣减流水都没有）
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).isEmpty(),
                "失败提交不应产生流水");
        // 3. 单据**仍为草稿(0)**（状态推进也被回滚，不是停在 1 或 2）
        RequisitionDO requisition = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(), requisition.getStatus(),
                "库存不足整单回滚，单据必须回到草稿态");
        // 4. timeout_time 不应被写（它在同一条 CAS 里，被一起回滚）
        assertNull(requisition.getTimeoutTime(), "回滚后超时时间不应留下");
        // 5. 明细仍在（建单时落库的，不属于本次事务）
        assertEquals(1, requisitionItemMapper.selectListByRequisitionId(id).size());
        // 6. 一致性未破坏
        assertBalanceConsistent(1L, 1L, "10", "10");
    }

    @Test
    public void testSubmit_multiItems_secondLineInsufficient_rollbackFirstLineToo() {
        // 关键场景：第一行够、第二行不够 → 第一行已扣的库存也必须回滚
        // （这是「逐条扣减 + 整单原子」最容易写错的地方：只回滚第二行，第一行就白扣了）
        insertStock(1L, 1L, "100.0000");
        insertStock(1L, 2L, "5.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L,
                item(1L, "30.0000"),   // 够（100 >= 30），会先被扣掉
                item(2L, "20.0000"))); // 不够（5 < 20），抛错

        assertServiceException(() -> requisitionService.submitRequisition(id),
                STOCK_NOT_ENOUGH, "5.0000", "20.0000");

        // 断言：两行库存都没动（第一行的扣减被回滚）
        assertQty("100", stockOf(1L, 1L).getAvailableQty(), "第一行的扣减必须随整单回滚");
        assertQty("5", stockOf(1L, 2L).getAvailableQty());
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).isEmpty(),
                "第一行也不应留下流水");
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 2L).isEmpty());
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(), requisitionMapper.selectById(id).getStatus());
        assertBalanceConsistent(1L, 1L, "100", "100");
        assertBalanceConsistent(1L, 2L, "5", "5");
    }

    @Test
    public void testSubmit_stockRowNotExists_throwsStockNotExists() {
        // 从未入库的物料：没有库存行（区别于"有行但不够"）
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(9L, "1.0000")));

        assertServiceException(() -> requisitionService.submitRequisition(id), STOCK_NOT_EXISTS);

        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(), requisitionMapper.selectById(id).getStatus());
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 9L).isEmpty());
    }

    // ========== 四、状态机：非法流转被拒 ==========

    @Test
    public void testSubmitStatusMachine_draftDirectlyToDeductedForbidden() {
        // 任务书明确要求的用例：草稿 直接 → 已扣减(2) 必须被拒（不能跳过"已提交"）
        assertFalse(RequisitionStatusEnum.canTransfer(
                RequisitionStatusEnum.DRAFT.getStatus(),
                RequisitionStatusEnum.DEDUCTED.getStatus()),
                "草稿不能直接流转到已扣减（必须先经已提交，V1 里这两步在同一事务内但状态机仍逐步校验）");
    }

    @Test
    public void testSubmitStatusMachine_cannotResubmitDeducted() {
        // 已扣减的单据再次提交必须被拒（防重复扣库存）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));
        requisitionService.submitRequisition(id);

        assertServiceException(() -> requisitionService.submitRequisition(id),
                REQUISITION_STATUS_INVALID, RequisitionStatusEnum.DEDUCTED.getName(),
                RequisitionStatusEnum.SUBMITTED.getName());

        // 断言：库存没有被扣第二次
        assertQty("90", stockOf(1L, 1L).getAvailableQty());
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());
    }

    @Test
    public void testSubmit_notExists_throws() {
        assertServiceException(() -> requisitionService.submitRequisition(999999L), REQUISITION_NOT_EXISTS);
    }

    @Test
    public void testCanTransfer_fullStateGraph() {
        // 状态机全图断言（单点实现的 canTransfer 是唯一裁决处，这里把每条边钉死）
        // ===== 合法边 =====
        assertTransferAllowed(0, 1); // 草稿 → 已提交
        assertTransferAllowed(0, 9); // 草稿 → 已取消（作废草稿）
        assertTransferAllowed(1, 2); // 已提交 → 已扣减（V1 同步扣减 / V2 MQ 扣减）
        assertTransferAllowed(1, 9); // 已提交 → 已取消（驳回/撤回）
        assertTransferAllowed(1, 8); // 已提交 → 已关闭（超时延迟消息）
        assertTransferAllowed(2, 3); // 已扣减 → 已出库（P5）
        assertTransferAllowed(2, 9); // 已扣减 → 已取消（撤销申领，回补+冲正）
        assertTransferAllowed(3, 4); // 已出库 → 已完成（核销，P5）
        assertTransferAllowed(3, 5); // 已出库 → 部分回退（退料，P5）
        assertTransferAllowed(5, 4); // 部分回退 → 已完成

        // ===== 非法边（反向 / 跳步 / 终态出边）=====
        assertTransferForbidden(1, 0); // 已提交不能退回草稿（库存已动，退回会让状态与事实脱钩）
        assertTransferForbidden(2, 0); // 已扣减不能退回草稿
        assertTransferForbidden(2, 1); // 已扣减不能退回已提交
        assertTransferForbidden(0, 2); // 草稿不能跳到已扣减（任务书点名的不合法流转）
        assertTransferForbidden(0, 3); // 草稿不能跳到已出库
        assertTransferForbidden(1, 3); // 已提交不能跳过已扣减直接出库
        assertTransferForbidden(4, 9); // 已完成是终态
        assertTransferForbidden(8, 1); // 已关闭是终态
        assertTransferForbidden(9, 1); // 已取消是终态
        // 自环一律非法
        assertTransferForbidden(0, 0);
        assertTransferForbidden(2, 2);
        // null 安全
        assertFalse(RequisitionStatusEnum.canTransfer(null, 1));
        assertFalse(RequisitionStatusEnum.canTransfer(0, null));
    }

    @Test
    public void testCanCancel_onlySubmittedOrDeducted() {
        assertTrue(RequisitionStatusEnum.canCancel(RequisitionStatusEnum.SUBMITTED.getStatus()));
        assertTrue(RequisitionStatusEnum.canCancel(RequisitionStatusEnum.DEDUCTED.getStatus()));
        // 其余状态一律不可取消
        assertFalse(RequisitionStatusEnum.canCancel(RequisitionStatusEnum.DRAFT.getStatus()));
        assertFalse(RequisitionStatusEnum.canCancel(RequisitionStatusEnum.OUTBOUND.getStatus()));
        assertFalse(RequisitionStatusEnum.canCancel(RequisitionStatusEnum.COMPLETED.getStatus()));
        assertFalse(RequisitionStatusEnum.canCancel(RequisitionStatusEnum.CANCELED.getStatus()));
        assertFalse(RequisitionStatusEnum.canCancel(RequisitionStatusEnum.CLOSED.getStatus()));
    }

    @Test
    public void testIsEndStatus() {
        assertTrue(RequisitionStatusEnum.isEndStatus(RequisitionStatusEnum.COMPLETED.getStatus()));
        assertTrue(RequisitionStatusEnum.isEndStatus(RequisitionStatusEnum.CANCELED.getStatus()));
        assertTrue(RequisitionStatusEnum.isEndStatus(RequisitionStatusEnum.CLOSED.getStatus()));
        assertFalse(RequisitionStatusEnum.isEndStatus(RequisitionStatusEnum.DEDUCTED.getStatus()));
        assertFalse(RequisitionStatusEnum.isEndStatus(RequisitionStatusEnum.DRAFT.getStatus()));
    }

    // ========== 五、取消：回补库存 + 冲正流水 ==========

    @Test
    public void testCancel_deducted_restoreStockAndWriteReversalLog() {
        // 库存 100 → 申领 30（扣到 70）→ 取消（回补到 100）
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        requisitionService.submitRequisition(id);
        assertQty("70", stockOf(1L, 1L).getAvailableQty());

        RequisitionDO canceled = requisitionService.cancelRequisition(id);

        // 断言 1：状态 → 已取消(9)、审批状态 → 已取消(4)
        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), canceled.getStatus());
        RequisitionDO dbRequisition = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), dbRequisition.getStatus());
        assertEquals(RequisitionApprovalStatusEnum.CANCEL.getStatus(), dbRequisition.getApprovalStatus());

        // 断言 2：库存回补到 100
        assertQty("100", stockOf(1L, 1L).getAvailableQty());

        // 断言 3：两条流水，扣减(-30) + 冲正(+30)，净额为 0
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(2, logs.size(), "取消应追加一条冲正流水（原始的扣减流水不删除，审计要求）");
        StockLogDO deductLog = logs.get(0);
        StockLogDO reversalLog = logs.get(1);
        assertEquals(StockBizTypeEnum.REQUISITION_OUT.getBizType(), deductLog.getBizType());
        assertEquals(-1, deductLog.getQuantity().signum());
        assertEquals(StockBizTypeEnum.REVERSAL.getBizType(), reversalLog.getBizType(), "冲正流水 biz_type 应为 4");
        assertEquals(1, reversalLog.getQuantity().signum(), "冲正流水 quantity 必须为正");
        assertQty("30", reversalLog.getQuantity());
        assertQty("100", reversalLog.getBalanceAfter());
        assertEquals(dbRequisition.getReqNo(), reversalLog.getBizNo(), "冲正流水也挂同一单号（便于按单追溯）");

        // 断言 4：SUM(quantity) 归零（该单的净库存影响为 0）
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
        assertBalanceConsistent(1L, 1L, "100", "100");
    }

    @Test
    public void testCancel_draft_forbidden() {
        // 草稿没动过库存，不允许"取消"（要作废草稿走删除，不在本 Phase 范围）
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));

        assertServiceException(() -> requisitionService.cancelRequisition(id),
                REQUISITION_CANNOT_CANCEL, RequisitionStatusEnum.DRAFT.getName());
    }

    @Test
    public void testCancel_returnedEntityReflectsDbState_notStale() {
        // ===== 本用例是联调实证缺陷的回归测试（W4-P4 踩到） =====
        //
        // 现象：经网关调 POST /cancel，HTTP 响应体里 approvalStatus 仍是 -1（未开始），
        //       而直接查库是 4（已取消）。
        // 根因：状态推进走的是 CAS SQL（直接改库），Service 里内存中的 DO 只回填了 status，
        //       忘了 approvalStatus —— 于是「库里对、响应体错」，前端按返回值渲染会显示"审批未开始"。
        // 为什么单测原本没抓到：原用例断言的是 `requisitionMapper.selectById(id)`（重新查库），
        //       天然是正确值；只有**返回值本身**才暴露问题。
        // → 本用例只断言 Service 的返回值（关键处不查库），锁住"返回值必须与库值一致"。
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));

        // submit 的返回值同样要检：status / timeoutTime / reqNo 都必须已回填
        RequisitionDO submitResult = requisitionService.submitRequisition(id);
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(), submitResult.getStatus(),
                "submit 返回值 status 必须已回填为已扣减(2)，不能是入参时的草稿(0)");
        assertNotNull(submitResult.getTimeoutTime(), "submit 返回值必须带 timeoutTime");
        assertNotNull(submitResult.getReqNo(), "submit 返回值必须带 reqNo");

        RequisitionDO cancelResult = requisitionService.cancelRequisition(id);
        // 断言 1：返回值 status
        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), cancelResult.getStatus(),
                "cancel 返回值 status 必须已回填为已取消(9)");
        // 断言 2（本用例的核心）：返回值 approvalStatus 必须同步为已取消(4)
        assertEquals(RequisitionApprovalStatusEnum.CANCEL.getStatus(), cancelResult.getApprovalStatus(),
                "cancel 返回值 approvalStatus 必须已回填为已取消(4)——"
                        + "CAS 只改库不改内存，漏回填会导致响应体是旧值（联调实证缺陷）");

        // 断言 3：返回值与库值逐字段一致（防止将来再漏回填别的字段）
        RequisitionDO dbRequisition = requisitionMapper.selectById(id);
        assertEquals(dbRequisition.getStatus(), cancelResult.getStatus());
        assertEquals(dbRequisition.getApprovalStatus(), cancelResult.getApprovalStatus());
        assertEquals(dbRequisition.getReqNo(), cancelResult.getReqNo());
        // timeoutTime 同样用容差比（DB 精度 < Java 纳秒精度，见 testSubmit_timeoutTimeIsThirtyMinutesAhead）
        assertNotNull(cancelResult.getTimeoutTime());
        assertNotNull(dbRequisition.getTimeoutTime());
        assertTrue(java.time.Duration.between(dbRequisition.getTimeoutTime(),
                        cancelResult.getTimeoutTime()).abs().toMillis() < 1,
                "返回值与库值的 timeoutTime 应一致（容差 1 毫秒）");
    }

    @Test
    public void testSubmit_timeoutTimeIsThirtyMinutesAhead() {
        // timeout_time = 提交时刻 + 30 分钟（spec §6.1 第 ⑥ 步），W5 延迟消息据此关单。
        // 允许 ±5 秒误差（CI 机器上事务耗时不可控），但必须落在 30 分钟附近而非别的值。
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));

        LocalDateTime before = LocalDateTime.now();
        RequisitionDO result = requisitionService.submitRequisition(id);
        LocalDateTime expectedMin = before.plusMinutes(RequisitionServiceImpl.TIMEOUT_MINUTES).minusSeconds(5);
        LocalDateTime expectedMax = LocalDateTime.now()
                .plusMinutes(RequisitionServiceImpl.TIMEOUT_MINUTES).plusSeconds(5);

        assertTrue(!result.getTimeoutTime().isBefore(expectedMin),
                "timeoutTime 应≈提交时间+30 分钟，实际：" + result.getTimeoutTime());
        assertTrue(!result.getTimeoutTime().isAfter(expectedMax),
                "timeoutTime 应≈提交时间+30 分钟，实际：" + result.getTimeoutTime());

        // ===== 与库值比较时**必须按容差**比，不能用 assertEquals（W4-P4 实证踩到） =====
        // 现象：expect 2026-11-28T00:41:37.358167，actual ...358167200 —— 差 200 纳秒。
        // 根因：Java 的 LocalDateTime 是纳秒精度，而 `datetime`/`timestamp` 列在 MySQL 5.6+
        //       默认是**秒**精度、显式声明 (3)/(6) 才是毫秒/微秒；H2 的 timestamp 默认也是微秒级。
        //       于是「内存值 → 写库 → 读回」会丢掉低位精度，assertEquals 直接红。
        // 影响面：任何「写时间字段再断言返回值 == 库值」的地方都要用容差（或只比到秒/微秒）。
        //       这条不是缺陷——业务上 30 分钟的超时判定差 200 纳秒毫无意义。
        LocalDateTime dbTimeoutTime = requisitionMapper.selectById(id).getTimeoutTime();
        assertNotNull(dbTimeoutTime);
        long driftNanos = Math.abs(java.time.Duration.between(dbTimeoutTime, result.getTimeoutTime()).toNanos());
        assertTrue(driftNanos < 1_000_000L,
                String.format("返回值与库值的 timeoutTime 偏差应小于 1 毫秒（实际偏差 %d 纳秒；"
                        + "库值=%s 返回值=%s）", driftNanos, dbTimeoutTime, result.getTimeoutTime()));
    }

    @Test
    public void testCancel_multiItems_allRestored() {
        insertStock(1L, 1L, "100.0000");
        insertStock(1L, 2L, "50.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L,
                item(1L, "30.0000"), item(2L, "20.0000")));
        requisitionService.submitRequisition(id);
        assertQty("70", stockOf(1L, 1L).getAvailableQty());
        assertQty("30", stockOf(1L, 2L).getAvailableQty());

        requisitionService.cancelRequisition(id);

        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        assertQty("50", stockOf(1L, 2L).getAvailableQty());
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 1L));
        assertQty("0", stockLogMapper.selectSumQuantity(1L, 2L));
    }

    @Test
    public void testCancel_canceled_forbidden_noDoubleRestore() {
        // 防重复回补：已取消的单再取消必须被拒，且库存不得被加两次
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "30.0000")));
        requisitionService.submitRequisition(id);
        requisitionService.cancelRequisition(id);
        assertQty("100", stockOf(1L, 1L).getAvailableQty());

        assertServiceException(() -> requisitionService.cancelRequisition(id),
                REQUISITION_CANNOT_CANCEL, RequisitionStatusEnum.CANCELED.getName());

        // 断言：库存仍是 100（没有被第二次回补成 130）
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
        assertEquals(2, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size(),
                "重复取消不应产生第三条流水");
    }

    // ========== 六、并发：V1 行锁串行化，不超卖（核心用例） ==========

    @Test
    public void testSubmitConcurrent_noOversell_underRowLock() throws Exception {
        // ===== 场景（任务书指定）=====
        // 库存 50，10 个线程各申领 10（每单一行明细），并发同时提交。
        // 期望：恰好 5 单成功、5 单失败（STOCK_NOT_ENOUGH），最终库存 = 0，无超卖。
        //
        // ===== 为什么 V1 行锁下这个断言必然成立 =====
        // 每个提交事务都在 `SELECT ... FOR UPDATE` 上排队：后到的事务阻塞在锁上，
        // 等前一个提交后**重新读到最新的 available_qty**（READ COMMITTED / REPEATABLE READ 下
        // 当前读都返回最新已提交值），于是第 6 个事务读到的是 0，校验失败并整体回滚。
        // 这就是 spec §6.1 说的"行锁串行化"——正确性完美，吞吐被锁排队限制。
        //
        // ===== 测试方法**不加** @Transactional 的原因见类注释 =====
        final int stockInit = 50;
        final int threadCount = 10;
        final int qtyPerOrder = 10;
        insertStock(1L, 1L, new BigDecimal(stockInit));

        // 预建 10 张草稿单（建单不涉及竞争，串行做，避免把建单耗时混进并发窗口）
        List<Long> requisitionIds = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            requisitionIds.add(requisitionService.createRequisition(
                    buildCreateReqVO(1L, item(1L, String.valueOf(qtyPerOrder)))));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger notEnoughCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        AtomicReference<ServiceException> unexpectedServiceEx = new AtomicReference<>();

        try {
            for (Long requisitionId : requisitionIds) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        requisitionService.submitRequisition(requisitionId);
                        successCount.incrementAndGet();
                    } catch (ServiceException e) {
                        // 允许（且期望）因库存不足而失败——这是 V1 的正确行为，不是缺陷
                        if (STOCK_NOT_ENOUGH.getCode().equals(e.getCode())) {
                            notEnoughCount.incrementAndGet();
                        } else {
                            unexpectedServiceEx.compareAndSet(null, e);
                        }
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(120, TimeUnit.SECONDS), "并发提交超时（120 秒）");
        } finally {
            pool.shutdownNow();
        }

        // ===== 断言 0：无意外异常 =====
        assertNull(unexpected.get(), "并发提交不应抛非预期异常：" + unexpected.get());
        assertNull(unexpectedServiceEx.get(),
                "失败只允许是 STOCK_NOT_ENOUGH，实际：" + unexpectedServiceEx.get());

        // ===== 断言 1（核心）：5 成功 / 5 失败（任务书要求的具体数字）=====
        assertEquals(stockInit / qtyPerOrder, successCount.get(),
                "库存 50 / 每单 10 → 应恰好 5 单成功，实际成功 " + successCount.get());
        assertEquals(threadCount - stockInit / qtyPerOrder, notEnoughCount.get(),
                "其余 5 单应因库存不足失败，实际 " + notEnoughCount.get());

        // ===== 断言 2（核心）：最终 stock = 0，无超卖（绝不能是负数）=====
        MaterialStockDO stock = stockOf(1L, 1L);
        assertQty("0", stock.getAvailableQty());
        assertTrue(stock.getAvailableQty().signum() >= 0,
                "库存绝不允许为负（超卖），实际：" + stock.getAvailableQty());

        // ===== 断言 3：流水只为成功的单产生，且净额 = -50 =====
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(successCount.get(), logs.size(), "每条成功提交恰好一条流水（失败的回滚不留痕）");
        assertQty("-50", stockLogMapper.selectSumQuantity(1L, 1L));
        assertBalanceConsistent(1L, 1L, "50", "0");

        // ===== 断言 4：所有成功的单状态=2、失败的单状态仍=0 =====
        long deductedCount = requisitionIds.stream()
                .map(requisitionMapper::selectById)
                .filter(r -> RequisitionStatusEnum.DEDUCTED.getStatus().equals(r.getStatus()))
                .count();
        long draftCount = requisitionIds.stream()
                .map(requisitionMapper::selectById)
                .filter(r -> RequisitionStatusEnum.DRAFT.getStatus().equals(r.getStatus()))
                .count();
        assertEquals(successCount.get(), deductedCount, "成功单的状态应为已扣减(2)");
        assertEquals(notEnoughCount.get(), draftCount, "失败单应回滚到草稿(0)，不能停在中间态");

        // ===== 断言 5：单号唯一（并发建单没有撞号）=====
        long distinctReqNoCount = requisitionIds.stream()
                .map(requisitionMapper::selectById)
                .map(RequisitionDO::getReqNo)
                .distinct()
                .count();
        assertEquals(threadCount, distinctReqNoCount, "10 张单的单号必须互不相同");
    }

    @Test
    public void testSubmitConcurrent_sameRequisition_onlyOneDeduct() throws Exception {
        // 另一类并发：**同一张单**被 5 个线程同时提交（双击/网关重试/脚本重投）
        // 期望：只有 1 个成功，其余被状态 CAS 拦下；库存只扣一次。
        //
        // 这是「库存行锁」拦不住的场景——行锁只保证每次扣减原子，不保证"同一单只扣一次"，
        // 因此必须靠单据状态推进的 CAS（RequisitionMapper#updateStatusCas）。
        final int stockInit = 100;
        final int qty = 10;
        final int threadCount = 5;
        insertStock(1L, 1L, new BigDecimal(stockInit));
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, String.valueOf(qty))));

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        requisitionService.submitRequisition(id);
                        successCount.incrementAndGet();
                    } catch (ServiceException e) {
                        rejectedCount.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(120, TimeUnit.SECONDS), "并发重复提交超时");
        } finally {
            pool.shutdownNow();
        }

        assertNull(unexpected.get(), "不应有非预期异常：" + unexpected.get());
        assertEquals(1, successCount.get(), "同一张单并发提交只能成功一次");
        assertEquals(threadCount - 1, rejectedCount.get(), "其余必须被状态 CAS 拦下");

        // 核心：库存只扣一次（100 - 10 = 90），流水只有一条
        assertQty("90", stockOf(1L, 1L).getAvailableQty());
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());
        assertBalanceConsistent(1L, 1L, "100", "90");
    }

    // ========== 七、V1/V2 开关 ==========

    @Test
    public void testSubmitV2Mode_delegatesToV2AndKeepsDraftWhenStockMissing() {
        // ========== W5-P2 后的口径变化（本条用例本来断言的是「V2 未实现」） ==========
        //
        // P4 时代 V2 是占位实现（抛 REQUISITION_MODE_NOT_IMPLEMENTED），本用例钉住的是
        // 「切到 V2 必须明确失败、绝不静默回退 V1」。
        // **W5-P2 已把 V2 实现为真链路**（Redis 预扣 + RockMQ 事务消息），因此"必须失败"的前提消失了；
        // 但它的**真正目的**仍是必须守住的：切到 V2 之后，走的必须是 V2 那条链路，不能偷偷跑 V1。
        //
        // 这里用一个"DB 有库存、但 Redis 侧没有基线且本项目没有库存行"的场景来区分两条链路：
        // - 若真的走了 V2：V2 处理器会先问 Redis（本类用 @MockBean 替身，未配置即返回 null/NOT_ENOUGH），
        //   在预扣阶段就失败，**绝不碰 DB 库存**；
        // - 若悄悄回退 V1：V1 会用行锁直接扣 DB，库存会变成 90、并写一条流水。
        // 所以"库存仍是 100 且没有流水"就是"没有回退 V1"的硬证据。
        //
        // V2 自己的完整行为（预扣成功→消息→消费→账平）由 RequisitionV2SubmitHandlerTest
        // 与端到端集成测试覆盖，不在本类重复。
        insertStock(1L, 1L, "100.0000");
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));

        System.setProperty(RequisitionSubmitHandlerFactory.CONFIG_KEY, RequisitionModeEnum.V2.getMode());
        try {
            // 本类的 StockPredeductService 是未配置的 mock：tryPrededuct 返回 null，
            // 与"NEED_INIT/NOT_ENOUGH"一样会让 V2 处理器在预扣阶段抛出或降级
            try {
                requisitionService.submitRequisition(id);
            } catch (RuntimeException ignored) {
                // V2 链路的失败形态可以是业务异常（库存不足）或 NPE（mock 返回 null），
                // 本条用例只关心"有没有偷偷跑 V1"，不关心 V2 自己怎么失败。
            }

            // 核心断言：V2 链路**不碰 DB 库存**（V1 链路会扣成 90）
            assertQty("100", stockOf(1L, 1L).getAvailableQty(),
                    "切到 V2 后不得回退 V1 同步扣减（DB 库存必须仍是 100）");
            assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).isEmpty(),
                    "切到 V2 后不得产生同步扣减流水");
            // 单据整体回滚：仍是草稿（V2 的"已提交(1)"依赖 Redis + 本地事务都成功）
            assertEquals(RequisitionStatusEnum.DRAFT.getStatus(),
                    requisitionMapper.selectById(id).getStatus(),
                    "V2 提交失败时整单回滚，单据应回到草稿");
        } finally {
            System.clearProperty(RequisitionSubmitHandlerFactory.CONFIG_KEY);
        }
    }

    @Test
    public void testFactoryModeSwitch() {
        // 工厂按配置分发：V1 → V1 实现；V2 → V2 实现；大小写不敏感
        assertEquals(RequisitionModeEnum.V1, submitHandlerFactory.getActiveMode(),
                "默认模式应为 V1");
        assertTrue(submitHandlerFactory.getHandler() instanceof RequisitionV1SubmitHandler);

        System.setProperty(RequisitionSubmitHandlerFactory.CONFIG_KEY, "v2");
        try {
            assertEquals(RequisitionModeEnum.V2, submitHandlerFactory.getActiveMode(),
                    "配置值应大小写不敏感");
            assertTrue(submitHandlerFactory.getHandler() instanceof RequisitionV2SubmitHandler);
        } finally {
            System.setProperty(RequisitionSubmitHandlerFactory.CONFIG_KEY, RequisitionModeEnum.V1.getMode());
        }
    }

    @Test
    public void testFactoryIllegalMode_throwsNotImplemented() {
        // 配置拼错（如 V3）→ 可读业务错误码，带上非法值；不静默落到默认值
        System.setProperty(RequisitionSubmitHandlerFactory.CONFIG_KEY, "V3");
        try {
            assertServiceException(() -> submitHandlerFactory.getHandler(),
                    REQUISITION_MODE_NOT_IMPLEMENTED, "V3");
        } finally {
            System.setProperty(RequisitionSubmitHandlerFactory.CONFIG_KEY, RequisitionModeEnum.V1.getMode());
        }
    }

    @Test
    public void testParseMode() {
        assertEquals(RequisitionModeEnum.V1, RequisitionModeEnum.parse("V1"));
        assertEquals(RequisitionModeEnum.V1, RequisitionModeEnum.parse(" v1 "));
        assertEquals(RequisitionModeEnum.V2, RequisitionModeEnum.parse("V2"));
        assertNull(RequisitionModeEnum.parse("V9"));
        assertNull(RequisitionModeEnum.parse(null));
        assertFalse(RequisitionModeEnum.V1.getDesc().isEmpty());
    }

    // ========== 八、查询 ==========

    @Test
    public void testGetRequisition_andItems() {
        Long id = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));

        RequisitionDO requisition = requisitionService.getRequisition(id);
        assertNotNull(requisition);
        assertEquals(1L, requisition.getProjectId());
        assertEquals(1, requisitionService.getRequisitionItemList(id).size());

        // 不存在的 id → null（与基座口径一致）
        assertNull(requisitionService.getRequisition(999999L));
    }

    @Test
    public void testGetRequisitionPage_filters() {
        // 项目 1 两张、项目 2 一张
        insertStock(1L, 1L, "100.0000");
        insertStock(2L, 1L, "100.0000");
        Long id1 = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "10.0000")));
        requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "20.0000")));
        requisitionService.createRequisition(buildCreateReqVO(2L, item(1L, "30.0000")));
        requisitionService.submitRequisition(id1);

        // 按项目筛
        RequisitionPageReqVO reqVO = new RequisitionPageReqVO();
        reqVO.setProjectId(1L);
        PageResult<RequisitionDO> page = requisitionService.getRequisitionPage(reqVO);
        assertEquals(2, page.getTotal());

        // 按状态筛：项目 1 里只有 1 张是已扣减
        reqVO.setStatus(RequisitionStatusEnum.DEDUCTED.getStatus());
        assertEquals(1, requisitionService.getRequisitionPage(reqVO).getTotal());

        // 按单号精确筛
        RequisitionPageReqVO byNo = new RequisitionPageReqVO();
        byNo.setReqNo(requisitionMapper.selectById(id1).getReqNo());
        assertEquals(1, requisitionService.getRequisitionPage(byNo).getTotal());

        // 无筛选 → 全部 3 张
        assertEquals(3, requisitionService.getRequisitionPage(new RequisitionPageReqVO()).getTotal());
    }

    @Test
    public void testGetRequisitionPage_orderByIdDesc() {
        Long first = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "1.0000")));
        Long second = requisitionService.createRequisition(buildCreateReqVO(1L, item(1L, "2.0000")));

        PageResult<RequisitionDO> page = requisitionService.getRequisitionPage(new RequisitionPageReqVO());
        assertEquals(second, page.getList().get(0).getId(), "最新创建的应在最前");
        assertEquals(first, page.getList().get(1).getId());
    }

    // ========== 辅助方法 ==========

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

    /**
     * 构造一行申领明细
     */
    private RequisitionSaveReqVO.Item item(Long materialId, String quantity) {
        RequisitionSaveReqVO.Item item = new RequisitionSaveReqVO.Item();
        item.setMaterialId(materialId);
        item.setQuantity(new BigDecimal(quantity));
        return item;
    }

    /**
     * 预置库存行（直接走 mapper，不产生任何流水——流水只应由业务链路产生）
     *
     * 为什么不调 `StockService#inbound`：那会额外写一条 biz_type=1 的入库流水，
     * 干扰「流水条数 == 成功提交次数」与「SUM(quantity) == -50」这类精确断言。
     * 直接插库存行 = 模拟「历史入库已发生过，当前可用量是 50」这一初始条件。
     */
    private void insertStock(Long projectId, Long materialId, String availableQty) {
        insertStock(projectId, materialId, new BigDecimal(availableQty));
    }

    private void insertStock(Long projectId, Long materialId, BigDecimal availableQty) {
        materialStockMapper.insert(MaterialStockDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .availableQty(availableQty)
                .lockedQty(BigDecimal.ZERO)
                .version(0)
                .build());
    }

    /**
     * 查库存行（不存在时抛断言失败，避免 NPE 掩盖"库存行本该存在"）
     */
    private MaterialStockDO stockOf(Long projectId, Long materialId) {
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        assertNotNull(stock, String.format("库存行应存在：projectId=%s materialId=%s", projectId, materialId));
        return stock;
    }

    /**
     * 一致性断言：`available_qty == 期初库存 + SUM(stock_log.quantity)`
     *
     * ========== 为什么这里比 P3 的断言多一个「期初库存」项（重要，别照抄成 P3 那个） ==========
     *
     * P3（{@code StockServiceImplTest#assertBalanceConsistent}）断言的是
     * `available_qty == SUM(quantity)`，因为它的用例全部经 `StockService#inbound` 造数据——
     * 生产链路上「库存的每一次变化都必须写一条流水」，所以期初为 0 时两者恒等，
     * `StockServiceImpl#validateStockBalance` 也按这个口径做对账。
     *
     * 但本测试类必须**直接插库存行**造期初（理由见 {@link #insertStock}）：若用 inbound 造 100，
     * 会多出一条 biz_type=1 的流水，让「流水条数 == 成功提交次数」「SUM(quantity) == -50」
     * 这类精确断言全部失真。于是 SUM(quantity) 只包含本用例产生的申领/冲正流水，
     * 与 available_qty 之间必然差一个期初量。
     *
     * → 因此断言写成 `available == opening + SUM`，这仍然是**同一个不变量**的完整形式
     *   （期初 + Σ变动 = 期末，spec §6.1 第 ⑦ 步的原始表述），只是把期初显式化。
     *   若将来有人把这个 helper 改成 `available == SUM`，本类 9 个用例会立刻红——
     *   那时请先确认是不是有人把期初造数改回了 inbound（未必是缺陷，但断言要跟着改）。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param opening    期初库存（本用例预置的量）
     * @param expected   期望的期末可用量
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
     *
     * H2 的 decimal(18,4) 读回后 scale 可能与期望字面量不同（如 150.5000 vs 150.5），
     * 用 compareTo 才是业务意义上的相等。
     */
    private void assertQty(String expected, BigDecimal actual) {
        assertQty(expected, actual, null);
    }

    private void assertQty(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, "数量不应为 null" + (message == null ? "" : "：" + message));
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                (message == null ? "" : message + "；") + "数量不匹配：期望 " + expected + "，实际 " + actual);
    }

    private void assertTransferAllowed(Integer from, Integer to) {
        assertTrue(RequisitionStatusEnum.canTransfer(from, to),
                String.format("应允许流转 %s → %s", from, to));
    }

    private void assertTransferForbidden(Integer from, Integer to) {
        assertFalse(RequisitionStatusEnum.canTransfer(from, to),
                String.format("应禁止流转 %s → %s", from, to));
    }

}
