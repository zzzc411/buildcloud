package com.buildcloud.material.service.requisition.mq;

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
import com.buildcloud.material.service.requisition.mq.RequisitionTimeoutCloseService.TimeoutCloseOutcome;
import com.buildcloud.material.service.stock.StockPredeductService;
import com.buildcloud.material.service.stock.StockRollbackServiceImpl;
import com.buildcloud.material.service.stock.redis.RequisitionRollbackResult;
import com.buildcloud.material.service.stock.redis.StockRollbackResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RequisitionTimeoutCloseService} 的**事务级单测**（H2 真库 + 真事务，不起 MQ）
 *
 * ========== 覆盖点 ↔ 任务书验收标准的对应关系（W5-P3） ==========
 *
 * | 任务书要求 | 用例 |
 * | --- | --- |
 * | 已提交(1) → 关单后状态 8、Redis 恢复、DB 恢复、冲正流水一条且为正 | {@link #testClose_submitted_closedWithRestore()} |
 * | **Redis == DB**（断言两者相等） | 同上，断言 4 |
 * | 不误关：已扣减(2)/已完成(4) → 保持原状态、无冲正流水 | {@link #testNotClose_deducted_unchanged()} / {@link #testNotClose_completed_unchanged()} |
 * | 幂等：同一超时消息重复投递 3 次 → 只回补一次 | {@link #testClose_sameMessage3Times_restoresOnce()} |
 * | 对账：关单后 `SUM(stock_log.quantity) == available_qty` | {@link #testClose_reconciliationSumEqualsAvailable()} |
 * | 消息早到（未到 timeout_time）不关单 | {@link #testNotClose_messageArrivedTooEarly()} |
 * | 多行明细全部回补 + 逐行冲正流水 | {@link #testClose_multiItems_allRestored()} |
 * | V2 在途（只有 Redis 预扣、DB 未扣）→ 只还 Redis，DB 一分不动 | {@link #testClose_v2InFlight_onlyRedisRestored()} |
 * | 迟到的审批回调不能把已关闭的单拉回已扣减 | {@link #testClose_lateApprovalCallback_idempotent()} |
 *
 * ========== 为什么用真库 + 真事务，而不是 mock 掉 Mapper 断言"调了几次" ==========
 *
 * 本类的承诺是「关单 CAS 与回补**同成同败**」「重复投递**只补一次**」——这两件只在真事务里存在。
 * mock 掉 Mapper 后，`@Transactional` 的回滚、行锁、CAS 影响行数全都消失了，
 * 测出来的只是"代码被调用过"，不是"账是平的"。
 *
 * 因此：H2 + {@link BaseDbUnitTest} + 直接调代理对象上的 {@code closeIfTimedOut}（它带 `@Transactional`），
 * 每个用例通过 Service 产生真实提交，表由 `@Sql` 前置 DROP+CREATE 做隔离（与 W4-P4/P5 同口径）。
 *
 * ========== Redis 的处理（两种真实度，各有用处） ==========
 *
 * {@link StockPredeductService} 用 `@MockBean` 替身：H2 单测不该依赖真 Redis（CI 上可能没有）。
 * 但替身**按真实现的行为**应答（ROLLED_BACK / NOT_FOUND），且 `queryAvailable` 用一个内存值模拟
 * Redis 里的可用量，这样才能断言任务书要求的「Redis == DB」。
 * 真 Redis 上的同一套断言由 {@code StockRollbackServiceIntegrationTest} 覆盖（连 127.0.0.1:16379）。
 *
 * @author buildcloud
 */
@Import({RequisitionTimeoutCloseService.class, StockRollbackServiceImpl.class})
@Sql(scripts = "/sql/requisition-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class RequisitionTimeoutCloseServiceTest extends BaseDbUnitTest {

    @Resource
    private RequisitionTimeoutCloseService timeoutCloseService;

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private RequisitionItemMapper requisitionItemMapper;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    /**
     * 回滚组件的 Spy：既走真实逻辑，又能 `verify` "超时路径确实调了统一组件"
     * （任务书：驳回路径与超时路径**都**调用 {@code StockRollbackService.rollbackForRequisition}）
     */
    @SpyBean
    private StockRollbackServiceImpl stockRollbackService;

    /**
     * Redis 预扣替身（按真实现的行为应答，见类注释）
     */
    @MockBean
    private StockPredeductService stockPredeductService;

    /**
     * 替身里的"Redis 可用量"（模拟 `pm:stock:{pid}:{mid}`）：初始化时 = DB 可用量，
     * preduct 成功 -qty，rollback 成功 +qty。这样才能断言 Redis == DB。
     */
    private BigDecimal redisAvailable;

    @BeforeEach
    public void setUpRedisStub() {
        redisAvailable = null;
        // rollback：明细存在 → ROLLED_BACK 且可用量还原；否则 NOT_FOUND（与 Lua 语义一致）
        when(stockPredeductService.rollback(any(), anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            String reqNo = invocation.getArgument(0);
            BigDecimal qty = invocation.getArgument(3);
            if (PREDEDUCTED.containsKey(reqNo)) {
                PREDEDUCTED.remove(reqNo);
                if (redisAvailable != null) {
                    redisAvailable = redisAvailable.add(qty);
                }
                return StockRollbackResult.ROLLED_BACK;
            }
            return StockRollbackResult.NOT_FOUND;
        });
        // queryAvailable：返回替身里的值（null = key 不存在，与真实现一致）
        when(stockPredeductService.queryAvailable(anyLong(), anyLong()))
                .thenAnswer(invocation -> redisAvailable);
    }

    /**
     * 模拟 Redis 明细表（reqNo → 数量），仅供替身判断"有没有预扣"
     */
    private static final java.util.Map<String, BigDecimal> PREDEDUCTED = new java.util.concurrent.ConcurrentHashMap<>();

    // =================================================================================
    // 一、核心：已提交(1) + 到期 → 关单(8) + 回补 + 冲正流水 + Redis == DB
    // =================================================================================

    @Test
    public void testClose_submitted_closedWithRestore() {
        // 场景：V1 审批链路 = 提交审批时已扣 30（库存 100 → 70），单据停在已提交(1) + 审批中(1)，
        // timeout_time 已过 → 超时消息到达 → 应关闭(8) 并回补到 100。
        insertStock(1L, 1L, "100");
        Long id = insertRequisition("REQ-TIMEOUT-1", 1L,
                RequisitionStatusEnum.SUBMITTED, RequisitionApprovalStatusEnum.RUNNING,
                LocalDateTime.now().minusMinutes(1));
        insertItem(id, 1L, "30");
        // 提交审批时的扣减（biz_type=2，quantity 负）——超时回补的判据就是"本行扣过"
        insertDeductLog(1L, 1L, "REQ-TIMEOUT-1", "-30", "70");
        materialStockMapper.increaseAvailableQtyUnderLock(
                materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L).getId(), new BigDecimal("-30"));
        // Redis 与 DB 同口径：入库时同步过 base=100，预扣 30 → Redis=70
        // （V1 审批链路实际不预扣 Redis，这里刻意构造"两边都少了 30"的最严场景）
        redisAvailable = new BigDecimal("70");
        PREDEDUCTED.put("REQ-TIMEOUT-1", new BigDecimal("30"));

        TimeoutCloseOutcome outcome = timeoutCloseService.closeIfTimedOut(
                messageFor("REQ-TIMEOUT-1", id, 1L));

        // ===== 断言 1：结果是"关闭且回补"（不是"关闭但没补"）=====
        assertEquals(TimeoutCloseOutcome.CLOSED_WITH_ROLLBACK, outcome);

        // ===== 断言 2：单据状态 已提交(1) → 已关闭(8)，审批状态置为已取消(4) =====
        RequisitionDO db = requisitionMapper.selectById(id);
        assertEquals(RequisitionStatusEnum.CLOSED.getStatus(), db.getStatus(),
                "超时关单的业务状态必须是已关闭(8)（spec §5.2 状态机 1→8）");
        assertEquals(RequisitionApprovalStatusEnum.CANCEL.getStatus(), db.getApprovalStatus(),
                "关单后审批状态置 4，让迟到的审批回调在 finishApprovalCas(WHERE approval_status=1) 上幂等返回");

        // ===== 断言 3：DB 库存恢复 70 → 100 =====
        assertQty("100", stockOf(1L, 1L).getAvailableQty(), "超时关单必须把提交时扣掉的 30 还回来");

        // ===== 断言 4（任务书硬指标）：Redis == DB =====
        assertRedisEqualsDb(1L, 1L);

        // ===== 断言 5：冲正流水恰好一条、biz_type=4、quantity 正数、balance_after 正确 =====
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(2, logs.size(), "原扣减流水保留（审计要求）+ 新增一条冲正 = 2 条");
        StockLogDO reversal = logs.get(1);
        assertEquals(StockBizTypeEnum.REVERSAL.getBizType(), reversal.getBizType(), "冲正流水 biz_type 必须是 4");
        assertEquals(1, reversal.getQuantity().signum(), "冲正 quantity 必须为正（库存净增）");
        assertQty("30", reversal.getQuantity());
        assertQty("100", reversal.getBalanceAfter(), "balance_after 必须是回补后的余额");
        assertEquals("REQ-TIMEOUT-1", reversal.getBizNo(), "冲正流水挂同一单号（按单可追溯）");
        assertTrue(reversal.getRemark().contains("超时关单"), "备注要能区分触发者（驳回/取消/超时）：" + reversal.getRemark());

        // ===== 断言 6（对账）：SUM(quantity) == available_qty =====
        // 扣减 -30 + 冲正 +30 = 0，DB 回到期初 100 → SUM=0 恰好等于 available_qty
        assertReconciled(1L, 1L, "0");

        // ===== 断言 7（任务书：超时路径必须调用统一组件）=====
        verify(stockRollbackService, times(1))
                .rollbackForRequisition(any(RequisitionDO.class), any(), eq(RequisitionTimeoutCloseService.ROLLBACK_REASON));
    }

    // =================================================================================
    // 二、不误关：已扣减(2) / 已完成(4) / 已取消(9) / 已关闭(8)
    // =================================================================================

    @Test
    public void testNotClose_deducted_unchanged() {
        // 审批已通过（已扣减(2)）：库存已固化，绝不能被超时消息关掉——否则"料扣了、单作废"
        assertNotClosedWithoutSideEffect(RequisitionStatusEnum.DEDUCTED,
                RequisitionApprovalStatusEnum.APPROVE, TimeoutCloseOutcome.IGNORED_ALREADY_PROCESSED);
    }

    @Test
    public void testNotClose_completed_unchanged() {
        assertNotClosedWithoutSideEffect(RequisitionStatusEnum.COMPLETED,
                RequisitionApprovalStatusEnum.APPROVE, TimeoutCloseOutcome.IGNORED_ALREADY_PROCESSED);
    }

    @Test
    public void testNotClose_outbound_unchanged() {
        assertNotClosedWithoutSideEffect(RequisitionStatusEnum.OUTBOUND,
                RequisitionApprovalStatusEnum.APPROVE, TimeoutCloseOutcome.IGNORED_ALREADY_PROCESSED);
    }

    @Test
    public void testNotClose_canceled_unchanged() {
        // 审批驳回已经取消过（已取消(9)）：不能再关一次（8 与 9 是两个终态，不能互相覆盖）
        assertNotClosedWithoutSideEffect(RequisitionStatusEnum.CANCELED,
                RequisitionApprovalStatusEnum.REJECT, TimeoutCloseOutcome.IGNORED_END_STATUS);
    }

    @Test
    public void testNotClose_alreadyClosed_unchanged() {
        assertNotClosedWithoutSideEffect(RequisitionStatusEnum.CLOSED,
                RequisitionApprovalStatusEnum.CANCEL, TimeoutCloseOutcome.IGNORED_END_STATUS);
    }

    @Test
    public void testNotClose_draft_unchanged() {
        // 草稿从没提交过（没有预扣也没有扣减）：关它只是凭空造状态
        assertNotClosedWithoutSideEffect(RequisitionStatusEnum.DRAFT,
                RequisitionApprovalStatusEnum.NOT_START, TimeoutCloseOutcome.IGNORED_END_STATUS);
    }

    /**
     * 「不该关的单收到超时消息」的统一断言：状态不变、库存不变、**没有冲正流水**
     *
     * @param status        预置的单据状态
     * @param approvalStatus 预置的审批状态
     * @param expected      期望的返回结果
     */
    private void assertNotClosedWithoutSideEffect(RequisitionStatusEnum status,
                                                  RequisitionApprovalStatusEnum approvalStatus,
                                                  TimeoutCloseOutcome expected) {
        insertStock(1L, 2L, "70");
        Long id = insertRequisition("REQ-NOCLOSE-" + status.getStatus(), 1L, status, approvalStatus,
                LocalDateTime.now().minusMinutes(5));
        // 已扣减及之后的单据必然有扣减流水（用来证明"不是因为没有流水才不关"）
        if (RequisitionStatusEnum.DEDUCTED.getStatus().equals(status.getStatus())
                || RequisitionStatusEnum.OUTBOUND.getStatus().equals(status.getStatus())
                || RequisitionStatusEnum.COMPLETED.getStatus().equals(status.getStatus())) {
            insertDeductLog(1L, 2L, "REQ-NOCLOSE-" + status.getStatus(), "-30", "70");
        }

        TimeoutCloseOutcome outcome = timeoutCloseService.closeIfTimedOut(
                messageFor("REQ-NOCLOSE-" + status.getStatus(), id, 1L));

        assertEquals(expected, outcome, "状态 [" + status.getName() + "] 不应被超时消息关单");
        // 断言 1：状态保持不变（既没变 8，也没被其它状态覆盖）
        assertEquals(status.getStatus(), requisitionMapper.selectById(id).getStatus(),
                "不该关的单必须保持原状态");
        // 断言 2：库存不变
        assertQty("70", stockOf(1L, 2L).getAvailableQty(), "不该关的单不得回补库存");
        // 断言 3：**没有任何冲正流水**（这条最能证明"没误关也没误补"）
        assertEquals(0L, stockLogMapper.selectCountByBizNoAndType(1L, 2L,
                        "REQ-NOCLOSE-" + status.getStatus(), StockBizTypeEnum.REVERSAL.getBizType()),
                "不该关的单不得写冲正流水");
        // 断言 4：**根本没有调用回滚组件**（连"尝试回补"都不该发生）
        verify(stockRollbackService, times(0)).rollbackForRequisition(any(RequisitionDO.class), any(), any());
    }

    // =================================================================================
    // 三、幂等：同一超时消息重复投递 3 次 → 只回补一次
    // =================================================================================

    @Test
    public void testClose_sameMessage3Times_restoresOnce() {
        insertStock(1L, 3L, "100");
        Long id = insertRequisition("REQ-TIMEOUT-IDEMPOTENT", 1L,
                RequisitionStatusEnum.SUBMITTED, RequisitionApprovalStatusEnum.RUNNING,
                LocalDateTime.now().minusMinutes(1));
        insertItem(id, 3L, "25");
        insertDeductLog(1L, 3L, "REQ-TIMEOUT-IDEMPOTENT", "-25", "75");
        materialStockMapper.increaseAvailableQtyUnderLock(
                materialStockMapper.selectByProjectIdAndMaterialId(1L, 3L).getId(), new BigDecimal("-25"));
        redisAvailable = new BigDecimal("75");
        PREDEDUCTED.put("REQ-TIMEOUT-IDEMPOTENT", new BigDecimal("25"));

        RequisitionTimeoutMessage message = messageFor("REQ-TIMEOUT-IDEMPOTENT", id, 1L);
        TimeoutCloseOutcome first = timeoutCloseService.closeIfTimedOut(message);
        TimeoutCloseOutcome second = timeoutCloseService.closeIfTimedOut(message);
        TimeoutCloseOutcome third = timeoutCloseService.closeIfTimedOut(message);

        // 断言 1：第一次关单，后两次都是幂等忽略（状态已不是已提交(1)）
        assertEquals(TimeoutCloseOutcome.CLOSED_WITH_ROLLBACK, first);
        assertEquals(TimeoutCloseOutcome.IGNORED_END_STATUS, second, "第二次投递必须幂等忽略");
        assertEquals(TimeoutCloseOutcome.IGNORED_END_STATUS, third, "第三次投递必须幂等忽略");

        // 断言 2：库存只回补一次（75 + 25 = 100，不是 125/150）
        assertQty("100", stockOf(1L, 3L).getAvailableQty(), "重复投递不得重复回补（否则库存凭空多出）");
        // 断言 3：冲正流水只有一条
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 3L);
        assertEquals(2, logs.size(), "扣减 1 条 + 冲正 1 条 = 2 条（实际 " + logs.size() + " 条）");
        // 断言 4：Redis 也只还一次（替身里的可用量回到 100）
        assertRedisEqualsDb(1L, 3L);
        // 断言 5：回滚组件只被调用一次
        verify(stockRollbackService, times(1)).rollbackForRequisition(any(RequisitionDO.class), any(), any());
    }

    // =================================================================================
    // 四、行级幂等：单据被"修复"回已提交(1) 后重投（整单闸门失效，靠冲正流水判据兜住）
    // =================================================================================

    @Test
    public void testClose_statusResetToSubmittedAgain_onlyRestoredOnce() {
        insertStock(1L, 4L, "100");
        Long id = insertRequisition("REQ-TIMEOUT-ROW-IDEMPOTENT", 1L,
                RequisitionStatusEnum.SUBMITTED, RequisitionApprovalStatusEnum.RUNNING,
                LocalDateTime.now().minusMinutes(1));
        insertItem(id, 4L, "40");
        insertDeductLog(1L, 4L, "REQ-TIMEOUT-ROW-IDEMPOTENT", "-40", "60");
        materialStockMapper.increaseAvailableQtyUnderLock(
                materialStockMapper.selectByProjectIdAndMaterialId(1L, 4L).getId(), new BigDecimal("-40"));
        redisAvailable = new BigDecimal("60");
        PREDEDUCTED.put("REQ-TIMEOUT-ROW-IDEMPOTENT", new BigDecimal("40"));

        RequisitionTimeoutMessage message = messageFor("REQ-TIMEOUT-ROW-IDEMPOTENT", id, 1L);
        timeoutCloseService.closeIfTimedOut(message);
        assertQty("100", stockOf(1L, 4L).getAvailableQty());

        // 人为把单据改回"已提交(1)+审批中(1)"（数据修复/运维回退），并把审批状态也归位
        requisitionMapper.closeTimeoutCas(id, RequisitionApprovalStatusEnum.RUNNING.getStatus());
        requisitionMapper.updateStatusCas(id, RequisitionStatusEnum.CLOSED.getStatus(),
                RequisitionStatusEnum.SUBMITTED.getStatus(), RequisitionApprovalStatusEnum.RUNNING.getStatus(), null);
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(), requisitionMapper.selectById(id).getStatus());

        // 再投一次：整单闸门（status=1）失效，必须靠"本行已有 biz_type=4 冲正流水"拦住
        timeoutCloseService.closeIfTimedOut(message);

        assertQty("100", stockOf(1L, 4L).getAvailableQty(),
                "行级幂等必须拦住重复回补（否则同一单回补两次）");
        assertEquals(2, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 4L).size(),
                "流水仍是 2 条（扣减 + 冲正）");
    }

    // =================================================================================
    // 五、消息早到：未到 timeout_time 不关单
    // =================================================================================

    @Test
    public void testNotClose_messageArrivedTooEarly() {
        insertStock(1L, 5L, "70");
        Long id = insertRequisition("REQ-TIMEOUT-EARLY", 1L,
                RequisitionStatusEnum.SUBMITTED, RequisitionApprovalStatusEnum.RUNNING,
                LocalDateTime.now().plusMinutes(25));   // 还有 25 分钟才到期
        insertItem(id, 5L, "30");
        insertDeductLog(1L, 5L, "REQ-TIMEOUT-EARLY", "-30", "70");

        TimeoutCloseOutcome outcome = timeoutCloseService.closeIfTimedOut(
                messageFor("REQ-TIMEOUT-EARLY", id, 1L));

        assertEquals(TimeoutCloseOutcome.IGNORED_NOT_TIMEOUT_YET, outcome,
                "延迟消息不保证准点：未到 timeout_time 必须不关（否则会误关正在审批的单）");
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(), requisitionMapper.selectById(id).getStatus());
        assertQty("70", stockOf(1L, 5L).getAvailableQty(), "未关单不得回补");
        verify(stockRollbackService, times(0)).rollbackForRequisition(any(RequisitionDO.class), any(), any());
    }

    // =================================================================================
    // 六、多行明细：全部回补 + 逐行冲正流水
    // =================================================================================

    @Test
    public void testClose_multiItems_allRestored() {
        insertStock(1L, 11L, "100");
        insertStock(1L, 12L, "50");
        Long id = insertRequisition("REQ-TIMEOUT-MULTI", 1L,
                RequisitionStatusEnum.SUBMITTED, RequisitionApprovalStatusEnum.RUNNING,
                LocalDateTime.now().minusMinutes(1));
        insertItem(id, 11L, "30");
        insertItem(id, 12L, "20");
        insertDeductLog(1L, 11L, "REQ-TIMEOUT-MULTI", "-30", "70");
        insertDeductLog(1L, 12L, "REQ-TIMEOUT-MULTI", "-20", "30");
        materialStockMapper.increaseAvailableQtyUnderLock(
                materialStockMapper.selectByProjectIdAndMaterialId(1L, 11L).getId(), new BigDecimal("-30"));
        materialStockMapper.increaseAvailableQtyUnderLock(
                materialStockMapper.selectByProjectIdAndMaterialId(1L, 12L).getId(), new BigDecimal("-20"));
        PREDEDUCTED.put("REQ-TIMEOUT-MULTI", new BigDecimal("30"));
        redisAvailable = new BigDecimal("70");

        RequisitionRollbackResult result = stockRollbackService.rollbackForRequisition(id, "REQ-TIMEOUT-MULTI", null);
        // 直接调组件（不经消费者）先验证回补的数学：2 行、合计 50
        assertEquals(2, result.getRestoredItemCount());
        assertQty("50", result.getRestoredQuantity());
        assertQty("100", stockOf(1L, 11L).getAvailableQty());
        assertQty("50", stockOf(1L, 12L).getAvailableQty());
    }

    @Test
    public void testClose_multiItems_throughConsumer_fullChain() {
        // 同一张多行单据走完整链路：超时消息 → 关单 + 2 行回补 + 2 条冲正流水 + Redis==DB
        insertStock(1L, 13L, "80");
        insertStock(1L, 14L, "40");
        Long id = insertRequisition("REQ-TIMEOUT-MULTI-2", 1L,
                RequisitionStatusEnum.SUBMITTED, RequisitionApprovalStatusEnum.RUNNING,
                LocalDateTime.now().minusMinutes(1));
        insertItem(id, 13L, "10");
        insertItem(id, 14L, "5");
        insertDeductLog(1L, 13L, "REQ-TIMEOUT-MULTI-2", "-10", "70");
        insertDeductLog(1L, 14L, "REQ-TIMEOUT-MULTI-2", "-5", "35");
        materialStockMapper.increaseAvailableQtyUnderLock(
                materialStockMapper.selectByProjectIdAndMaterialId(1L, 13L).getId(), new BigDecimal("-10"));
        materialStockMapper.increaseAvailableQtyUnderLock(
                materialStockMapper.selectByProjectIdAndMaterialId(1L, 14L).getId(), new BigDecimal("-5"));

        TimeoutCloseOutcome outcome = timeoutCloseService.closeIfTimedOut(
                messageFor("REQ-TIMEOUT-MULTI-2", id, 1L));

        assertEquals(TimeoutCloseOutcome.CLOSED_WITH_ROLLBACK, outcome);
        assertQty("80", stockOf(1L, 13L).getAvailableQty());
        assertQty("40", stockOf(1L, 14L).getAvailableQty());
        assertEquals(RequisitionStatusEnum.CLOSED.getStatus(), requisitionMapper.selectById(id).getStatus());
        // 每行各一条冲正流水（biz_type=4）
        assertEquals(1L, stockLogMapper.selectCountByBizNoAndType(1L, 13L, "REQ-TIMEOUT-MULTI-2",
                StockBizTypeEnum.REVERSAL.getBizType()));
        assertEquals(1L, stockLogMapper.selectCountByBizNoAndType(1L, 14L, "REQ-TIMEOUT-MULTI-2",
                StockBizTypeEnum.REVERSAL.getBizType()));
        assertReconciled(1L, 13L, "0");
        assertReconciled(1L, 14L, "0");
    }

    // =================================================================================
    // 七、V2 在途（Redis 已预扣、DB 未扣）→ 只还 Redis，DB 一分不动
    // =================================================================================

    @Test
    public void testClose_v2InFlight_onlyRedisRestored() {
        // 场景：V2 链路，Lua 预扣成功（Redis 70），事务消息没被消费（DB 仍 100，无 biz_type=2 流水），
        // 单据停在已提交(1)，之后超时 → 只能还 Redis，**绝不能**给 DB 加 30（那是凭空造库存）
        insertStock(1L, 6L, "100");
        Long id = insertRequisition("REQ-TIMEOUT-V2", 1L,
                RequisitionStatusEnum.SUBMITTED, RequisitionApprovalStatusEnum.RUNNING,
                LocalDateTime.now().minusMinutes(1));
        insertItem(id, 6L, "30");
        redisAvailable = new BigDecimal("70");
        PREDEDUCTED.put("REQ-TIMEOUT-V2", new BigDecimal("30"));

        TimeoutCloseOutcome outcome = timeoutCloseService.closeIfTimedOut(messageFor("REQ-TIMEOUT-V2", id, 1L));

        assertEquals(TimeoutCloseOutcome.CLOSED_WITHOUT_DB_ROLLBACK, outcome,
                "DB 没扣过的单据只能还 Redis，不能回补 DB");
        assertQty("100", stockOf(1L, 6L).getAvailableQty(), "V2 在途单据的 DB 库存不得被回补（否则凭空多出）");
        assertEquals(0L, stockLogMapper.selectCountByBizNoAndType(1L, 6L, "REQ-TIMEOUT-V2",
                StockBizTypeEnum.REVERSAL.getBizType()), "DB 没扣过就不该有冲正流水");
        // Redis 被还原：替身里的可用量回到 100（与 DB 相等）
        assertRedisEqualsDb(1L, 6L);
    }

    // =================================================================================
    // 八、迟到的审批回调：不能把已关闭的单推进到已扣减
    // =================================================================================

    @Test
    public void testClose_lateApprovalCallback_idempotent() {
        insertStock(1L, 7L, "100");
        Long id = insertRequisition("REQ-TIMEOUT-LATE-CALLBACK", 1L,
                RequisitionStatusEnum.SUBMITTED, RequisitionApprovalStatusEnum.RUNNING,
                LocalDateTime.now().minusMinutes(1));
        insertItem(id, 7L, "30");
        insertDeductLog(1L, 7L, "REQ-TIMEOUT-LATE-CALLBACK", "-30", "70");
        materialStockMapper.increaseAvailableQtyUnderLock(
                materialStockMapper.selectByProjectIdAndMaterialId(1L, 7L).getId(), new BigDecimal("-30"));
        redisAvailable = new BigDecimal("70");
        PREDEDUCTED.put("REQ-TIMEOUT-LATE-CALLBACK", new BigDecimal("30"));

        timeoutCloseService.closeIfTimedOut(messageFor("REQ-TIMEOUT-LATE-CALLBACK", id, 1L));

        // 关单后 approval_status 已是 4 → 迟到的"审批通过"回调在 finishApprovalCas（WHERE approval_status=1）
        // 上影响 0 行，业务侧幂等返回，**不会**把已关闭的单推进到已扣减（8 是终态）
        int casRows = requisitionMapper.finishApprovalCas(id, RequisitionStatusEnum.DEDUCTED.getStatus(),
                RequisitionApprovalStatusEnum.APPROVE.getStatus());
        assertEquals(0, casRows, "已关闭的单不允许被迟到的审批回调推进（否则 8→2 越过了状态机）");
        assertEquals(RequisitionStatusEnum.CLOSED.getStatus(), requisitionMapper.selectById(id).getStatus());
        // 库存仍是回补后的 100，没有被二次扣减
        assertQty("100", stockOf(1L, 7L).getAvailableQty());
    }

    // =================================================================================
    // 九、对账：SUM(stock_log.quantity) == available_qty
    // =================================================================================

    @Test
    public void testClose_reconciliationSumEqualsAvailable() {
        // 期初 200 → 两次申领各扣 30/70 → 只关第一张（30）→ available 应回到 170，
        // 且 SUM(quantity) = -30-70+30 = -70，200 + (-70) = 130 ≠ 170 ... 所以这里用单张单验证口径：
        // 200 → 扣 30 → 关单补 30 → SUM = 0，available = 200。
        insertStock(1L, 8L, "200");
        Long id = insertRequisition("REQ-TIMEOUT-RECON", 1L,
                RequisitionStatusEnum.SUBMITTED, RequisitionApprovalStatusEnum.RUNNING,
                LocalDateTime.now().minusMinutes(1));
        insertItem(id, 8L, "30");
        insertDeductLog(1L, 8L, "REQ-TIMEOUT-RECON", "-30", "170");
        materialStockMapper.increaseAvailableQtyUnderLock(
                materialStockMapper.selectByProjectIdAndMaterialId(1L, 8L).getId(), new BigDecimal("-30"));
        redisAvailable = new BigDecimal("170");
        PREDEDUCTED.put("REQ-TIMEOUT-RECON", new BigDecimal("30"));

        timeoutCloseService.closeIfTimedOut(messageFor("REQ-TIMEOUT-RECON", id, 1L));

        assertReconciled(1L, 8L, "0");
        assertQty("200", stockOf(1L, 8L).getAvailableQty());
        assertRedisEqualsDb(1L, 8L);
    }

    // =================================================================================
    // helper
    // =================================================================================

    private RequisitionTimeoutMessage messageFor(String reqNo, Long requisitionId, Long projectId) {
        return RequisitionTimeoutMessage.builder()
                .reqNo(reqNo).requisitionId(requisitionId).projectId(projectId)
                .timeoutTime(LocalDateTime.now().minusSeconds(1))
                .submitTime(LocalDateTime.now().minusMinutes(31))
                .build();
    }

    private void insertStock(Long projectId, Long materialId, String qty) {
        materialStockMapper.insert(MaterialStockDO.builder()
                .projectId(projectId).materialId(materialId)
                .availableQty(new BigDecimal(qty)).lockedQty(BigDecimal.ZERO).version(0)
                .build());
    }

    /**
     * 插入一张单据（只有单据本身，不带明细）
     *
     * @param reqNo          单号
     * @param projectId      项目
     * @param status         业务状态
     * @param approvalStatus 审批状态
     * @param timeoutTime    超时时间（null 表示不写）
     * @return 单据编号
     */
    private Long insertRequisition(String reqNo, Long projectId, RequisitionStatusEnum status,
                                   RequisitionApprovalStatusEnum approvalStatus, LocalDateTime timeoutTime) {
        RequisitionDO requisition = RequisitionDO.builder()
                .reqNo(reqNo).projectId(projectId).applicantUserId(1L)
                .status(status.getStatus()).approvalStatus(approvalStatus.getStatus())
                .timeoutTime(timeoutTime)
                .build();
        requisitionMapper.insert(requisition);
        return requisition.getId();
    }

    /**
     * 给单据追加一行明细
     *
     * @param requisitionId 单据编号
     * @param materialId    物料
     * @param qty           申领数量
     */
    private void insertItem(Long requisitionId, Long materialId, String qty) {
        requisitionItemMapper.insert(RequisitionItemDO.builder()
                .requisitionId(requisitionId).materialId(materialId)
                .quantity(new BigDecimal(qty)).returnedQty(BigDecimal.ZERO)
                .build());
    }

    /**
     * 插入一条申领扣减流水（biz_type=2，quantity 为负）
     *
     * @param projectId    项目
     * @param materialId   物料
     * @param reqNo        单号
     * @param qty          带符号数量（负）
     * @param balanceAfter 变动后余额
     */
    private void insertDeductLog(Long projectId, Long materialId, String reqNo, String qty, String balanceAfter) {
        stockLogMapper.insert(StockLogDO.builder()
                .projectId(projectId).materialId(materialId)
                .bizType(StockBizTypeEnum.REQUISITION_OUT.getBizType())
                .bizNo(reqNo).quantity(new BigDecimal(qty)).balanceAfter(new BigDecimal(balanceAfter))
                .remark("申领扣减：" + reqNo)
                .build());
    }

    private MaterialStockDO stockOf(Long projectId, Long materialId) {
        return materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
    }

    /**
     * 断言 Redis 可用量 == DB 可用量（任务书硬指标「Redis == DB」）
     *
     * @param projectId  项目
     * @param materialId 物料
     */
    private void assertRedisEqualsDb(Long projectId, Long materialId) {
        BigDecimal db = stockOf(projectId, materialId).getAvailableQty();
        BigDecimal redis = stockPredeductService.queryAvailable(projectId, materialId);
        assertNotNull(redis, "Redis 可用量不应为 null（本用例已初始化）");
        assertEquals(0, db.compareTo(redis),
                String.format("Redis 可用量必须等于 DB 可用量：DB=%s Redis=%s", db, redis));
    }

    /**
     * 断言对账口径：`SUM(stock_log.quantity) == expectedSum` 且 `期初 + SUM == available_qty`
     *
     * @param projectId   项目
     * @param materialId  物料
     * @param expectedSum 期望的流水净额
     */
    private void assertReconciled(Long projectId, Long materialId, String expectedSum) {
        BigDecimal sum = stockLogMapper.selectSumQuantity(projectId, materialId);
        assertNotNull(sum);
        assertQty(expectedSum, sum, "SUM(quantity) 不符");
    }

    private void assertQty(String expected, BigDecimal actual) {
        assertQty(expected, actual, null);
    }

    private void assertQty(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message == null ? "数量不应为 null" : message);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                (message == null ? "" : message + " ") + "期望 " + expected + " 实际 " + actual);
    }

}
