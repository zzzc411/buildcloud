package com.buildcloud.material.service.requisition.mq;

import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequisitionDeductTransactionService} 的**事务级单测**（H2，真事务，不起 MQ）
 *
 * ========== 为什么这类用例必须跑真事务 ==========
 *
 * 本类的核心承诺是「**整单原子**：一行失败、整单回滚」与「**幂等**：重复投递只生效一次」。
 * 用 mock 掉 Mapper 断言"调了几次"是测不出原子性的——原子性只在真事务里存在。
 * 所以：H2 + `BaseDbUnitTest` + 直接调代理对象上的 `deduct`（它带 `@Transactional`）。
 *
 * ========== 覆盖的路径（对应任务书第⑥条的单测清单） ==========
 *
 * | 任务书要求 | 对应用例 |
 * | --- | --- |
 * | 幂等消费：同一消息重复投递 3 次只生效一次 | {@link #testDeduct_sameMessageDelivered3Times_onlyDeductOnce()} |
 * | 本地事务失败：无脏数据 | {@link #testDeduct_casMiss_rollsBackEverything()}（状态 CAS 未命中 = 整单回滚） |
 * | 消费失败重试（模拟 DB 扣减失败） | {@link #testDeduct_dbNotEnough_throwsForRetry()}（抛异常 = MQ 重投的前提） |
 * | **DB 兜底防线**：Redis 说够但 DB 不够 → 0 行 → 报错不超卖 | {@link #testDeduct_driftRedisSaysEnoughDbDoesNot_noOversell()} |
 * | 流水口径：biz_type=2、quantity 负、balance_after 正确 | {@link #testDeduct_success_writesLogWithNegativeQuantityAndBalance()} |
 * | 多行部分失败整单回滚（第 2 行失败，第 1 行也回滚） | {@link #testDeduct_secondItemFails_firstItemRolledBack()} |
 *
 * @author buildcloud
 */
@Import({RequisitionDeductTransactionService.class, RequisitionDeductLocalTransactionService.class})
@Sql(scripts = "/sql/requisition-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class RequisitionDeductTransactionServiceTest extends BaseDbUnitTest {

    @Resource
    private RequisitionDeductTransactionService transactionService;

    @Resource
    private RequisitionDeductLocalTransactionService localTransactionService;

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    /**
     * 本类只测事务服务，不需要 MQ 的 bean。
     *
     * 为什么不做成内部类 + @TestConfiguration：`BaseDbUnitTest.Application` 是固定的
     * `@Import` 列表，测试类上再 `@Import` 本类的 `@TestConfiguration` 会与它叠加，
     * 这里留一个空配置类占位，明确"本类没有额外 bean"，比隐式"什么都没加"更可读。
     */
    @TestConfiguration
    static class NoopConfiguration {

        @Bean
        public Object noopMarker() {
            return new Object();
        }

    }

    // =================================================================================
    // 一、幂等：同一消息重复投递 3 次
    // =================================================================================

    @Test
    public void testDeduct_sameMessageDelivered3Times_onlyDeductOnce() {
        // 期初 100；单据是已提交(1)；消息申领 30
        insertStock(1L, 1L, "100");
        Long requisitionId = insertRequisition("REQ-IDEMPOTENT-1", 1L, RequisitionStatusEnum.SUBMITTED);
        RequisitionDeductMessage message = message("REQ-IDEMPOTENT-1", requisitionId, 1L, item(1L, "30"));

        // 第 1 次：真扣
        transactionService.deduct(message);
        assertQty("70", stockOf(1L, 1L).getAvailableQty(), "第一次消费应扣 30");
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());

        // 第 2、3 次：重复投递（模拟 MQ 至少一次语义 / 消费者 ACK 丢失）
        transactionService.deduct(message);
        transactionService.deduct(message);

        // 断言 1：库存只扣一次
        assertQty("70", stockOf(1L, 1L).getAvailableQty(), "重复投递不得重复扣减");
        // 断言 2：流水只有一条
        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(1, logs.size(), "重复投递只应产生 1 条流水（实际 " + logs.size() + " 条）");
        // 断言 3：单据状态是已扣减(2)
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(),
                requisitionMapper.selectById(requisitionId).getStatus(), "单据应推进到已扣减(2)");
        // 断言 4：账平 —— 期初 100 + Σ流水(-30) == DB available(70)
        assertEquals(0, new BigDecimal("-30").compareTo(
                stockLogMapper.selectSumQuantity(1L, 1L)), "SUM(quantity) 应为 -30（申领扣减记负数）");
        assertQty("70", new BigDecimal("100").add(stockLogMapper.selectSumQuantity(1L, 1L)),
                "期初 100 + 流水净额 必须等于当期可用量 70");
    }

    // =================================================================================
    // 二、成功路径的流水口径
    // =================================================================================

    @Test
    public void testDeduct_success_writesLogWithNegativeQuantityAndBalance() {
        insertStock(1L, 2L, "50");
        Long requisitionId = insertRequisition("REQ-LOG-1", 1L, RequisitionStatusEnum.SUBMITTED);

        transactionService.deduct(message("REQ-LOG-1", requisitionId, 1L, item(2L, "12.5")));

        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 2L);
        assertEquals(1, logs.size());
        StockLogDO log = logs.get(0);
        assertEquals(StockBizTypeEnum.REQUISITION_OUT.getBizType(), log.getBizType(), "biz_type 必须是 2 申领扣减");
        assertEquals("REQ-LOG-1", log.getBizNo(), "biz_no 必须是申领单号（对账追溯键）");
        assertQty("-12.5", log.getQuantity(), "申领扣减的 quantity 必须记负数");
        assertQty("37.5", log.getBalanceAfter(), "balance_after 必须是扣减后的余额");
        // 断言库里的 available_qty == 流水 balance_after（L3-10 同源思路：不只断言"能查到"）
        assertQty("37.5", stockOf(1L, 2L).getAvailableQty(), "流水 balance_after 必须等于库存可用量");
    }

    // =================================================================================
    // 三、DB 兜底防线：Redis 说够、DB 不够（人为制造漂移）
    // =================================================================================

    @Test
    public void testDeduct_driftRedisSaysEnoughDbDoesNot_noOversell() {
        // 人为制造漂移的姿势：**消息说申领 80，但 DB 只有 50**。
        // 真实场景里这等价于"Redis 里 available=100（预扣成功），而 DB 被人为改成了 50"。
        // 我们不需要真的去操纵 Redis——消费者只看 DB，DB 不够就必须拦住，这正是第二道防线的语义。
        insertStock(1L, 3L, "50");
        Long requisitionId = insertRequisition("REQ-DRIFT-1", 1L, RequisitionStatusEnum.SUBMITTED);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transactionService.deduct(message("REQ-DRIFT-1", requisitionId, 1L, item(3L, "80"))),
                "DB 可用量不足时条件更新应影响 0 行并抛错（触发 MQ 重试/死信）");
        assertTrue(ex.getMessage().contains("漂移") || ex.getMessage().contains("影响 0 行"),
                "异常信息必须说明「DB 条件扣减影响 0 行 / 漂移」，实际：" + ex.getMessage());

        // 断言 1（**不超卖**）：库存一分未扣，且不是负数
        assertQty("50", stockOf(1L, 3L).getAvailableQty(), "扣减失败后库存必须原封不动（不得出现负数）");
        // 断言 2：没有产生任何流水（否则就是"钱没扣、流水记了"）
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 3L).isEmpty(),
                "扣减失败不得写流水");
        // 断言 3：单据没有推进（仍是已提交(1)，等待重试）
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(),
                requisitionMapper.selectById(requisitionId).getStatus(),
                "扣减失败后单据必须停在已提交(1)，由 MQ 重试");
    }

    // =================================================================================
    // 四、消费失败重试：模拟 DB 扣减失败后重投成功
    // =================================================================================

    @Test
    public void testDeduct_dbFailureThenRetrySucceeds_equalsExactlyOnce() {
        // 第一轮：DB 只有 50，消息要 80 → 失败（相当于 MQ 第一次投递失败）
        insertStock(1L, 4L, "50");
        Long requisitionId = insertRequisition("REQ-RETRY-1", 1L, RequisitionStatusEnum.SUBMITTED);
        RequisitionDeductMessage message = message("REQ-RETRY-1", requisitionId, 1L, item(4L, "80"));
        assertThrows(IllegalStateException.class, () -> transactionService.deduct(message));

        // 模拟"运维修复了漂移"（补入库 40 → DB 现在 90）
        materialStockMapper.updateAvailableQtyIncrWithVersion(stockOf(1L, 4L).getId(), new BigDecimal("40"),
                stockOf(1L, 4L).getVersion());

        // 第二轮：MQ 重投 → 成功
        transactionService.deduct(message);

        assertQty("10", stockOf(1L, 4L).getAvailableQty(), "重投成功后应扣掉 80（90 - 80 = 10）");
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 4L).size(),
                "只应产生 1 条流水（失败那一轮没有写流水）");
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(),
                requisitionMapper.selectById(requisitionId).getStatus());
    }

    // =================================================================================
    // 五、本地事务失败/状态 CAS 未命中：整单回滚，无脏数据
    // =================================================================================

    @Test
    public void testDeduct_casMiss_rollsBackEverything() {
        // 构造一个"消费者处理期间单据状态被别人改走"的场景：单据状态不是已提交(1)，
        // 而是**已取消(9)**（P3 的驳回/超时关单会这样）。此时扣减必须整单回滚。
        insertStock(1L, 5L, "100");
        Long requisitionId = insertRequisition("REQ-CAS-1", 1L, RequisitionStatusEnum.CANCELED);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transactionService.deduct(message("REQ-CAS-1", requisitionId, 1L, item(5L, "30"))),
                "单据不在已提交态时扣减必须失败（不能给已取消的单扣库存）");
        assertTrue(ex.getMessage().contains("状态已变化"), "异常信息应说明状态变化：" + ex.getMessage());

        // 断言 1：**无脏数据**——扣减被回滚，库存原封不动
        assertQty("100", stockOf(1L, 5L).getAvailableQty(),
                "状态 CAS 未命中时，本事务里已执行的扣减必须一起回滚（否则就是重复扣/扣了废单）");
        // 断言 2：没有流水
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 5L).isEmpty(),
                "整单回滚后不得留下流水");
    }

    // =================================================================================
    // 六、多行部分失败：整单回滚
    // =================================================================================

    @Test
    public void testDeduct_secondItemFails_firstItemRolledBack() {
        // 物料 6 够、物料 7 不够。materialId 升序处理：先 6（成功）后 7（失败）
        insertStock(1L, 6L, "100");
        insertStock(1L, 7L, "5");
        Long requisitionId = insertRequisition("REQ-MULTI-1", 1L, RequisitionStatusEnum.SUBMITTED);

        RequisitionDeductMessage message = RequisitionDeductMessage.builder()
                .reqNo("REQ-MULTI-1").requisitionId(requisitionId).projectId(1L)
                .items(List.of(item(7L, "50"), item(6L, "30")))
                .build();

        assertThrows(IllegalStateException.class, () -> transactionService.deduct(message));

        // 断言：物料 6 的成功扣减被回滚（这是"整单原子"的硬证据）
        assertQty("100", stockOf(1L, 6L).getAvailableQty(), "第 2 行失败时第 1 行的扣减必须回滚");
        assertQty("5", stockOf(1L, 7L).getAvailableQty(), "失败的物料不得扣减");
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 6L).isEmpty(), "第 1 行不得留下流水");
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(),
                requisitionMapper.selectById(requisitionId).getStatus(), "单据仍是已提交(1)");
    }

    // =================================================================================
    // 七、畸形消息：数量非正 → 抛错（不让 0/-1 写进流水）
    // =================================================================================

    @Test
    public void testDeduct_illegalQuantity_rejected() {
        insertStock(1L, 8L, "100");
        Long requisitionId = insertRequisition("REQ-BAD-QTY", 1L, RequisitionStatusEnum.SUBMITTED);

        assertThrows(IllegalArgumentException.class,
                () -> transactionService.deduct(message("REQ-BAD-QTY", requisitionId, 1L, item(8L, "0"))),
                "数量为 0 属于畸形消息，必须拒绝而不是写一条 quantity=0 的流水");
        assertQty("100", stockOf(1L, 8L).getAvailableQty());
    }

    // =================================================================================
    // 八、行级幂等：单据被"修复"回 1 后消息重投（整单闸门失效，靠行级判据兜住）
    // =================================================================================

    @Test
    public void testDeduct_rowLevelIdempotent_whenStatusResetToSubmitted() {
        insertStock(1L, 9L, "100");
        Long requisitionId = insertRequisition("REQ-ROW-IDEMPOTENT", 1L, RequisitionStatusEnum.SUBMITTED);
        RequisitionDeductMessage message = message("REQ-ROW-IDEMPOTENT", requisitionId, 1L, item(9L, "40"));

        transactionService.deduct(message);
        assertQty("60", stockOf(1L, 9L).getAvailableQty());

        // 人为把单据状态改回已提交(1)——模拟"数据修复/人工回退"制造出的重复投递窗口
        requisitionMapper.updateStatusCas(requisitionId, RequisitionStatusEnum.DEDUCTED.getStatus(),
                RequisitionStatusEnum.SUBMITTED.getStatus(), -1, null);

        // 此时整单闸门失效（状态是 1），必须靠行级判据（本单本物料已有 biz_type=2 流水）拦住
        transactionService.deduct(message);

        assertQty("60", stockOf(1L, 9L).getAvailableQty(),
                "行级幂等必须拦住重复扣减（否则同一单会扣两次）");
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 9L).size(),
                "流水仍应是 1 条");
    }

    // =================================================================================
    // helper
    // =================================================================================

    private RequisitionDeductMessage message(String reqNo, Long requisitionId, Long projectId,
                                             RequisitionDeductMessage.Item item) {
        return RequisitionDeductMessage.builder()
                .reqNo(reqNo).requisitionId(requisitionId).projectId(projectId)
                .items(List.of(item)).build();
    }

    private RequisitionDeductMessage.Item item(Long materialId, String qty) {
        return RequisitionDeductMessage.Item.builder()
                .materialId(materialId).quantity(new BigDecimal(qty)).build();
    }

    private void insertStock(Long projectId, Long materialId, String qty) {
        materialStockMapper.insert(MaterialStockDO.builder()
                .projectId(projectId).materialId(materialId)
                .availableQty(new BigDecimal(qty)).lockedQty(BigDecimal.ZERO).version(0)
                .build());
    }

    private Long insertRequisition(String reqNo, Long projectId, RequisitionStatusEnum status) {
        RequisitionDO requisition = RequisitionDO.builder()
                .reqNo(reqNo).projectId(projectId).applicantUserId(1L)
                .status(status.getStatus()).approvalStatus(-1)
                .build();
        requisitionMapper.insert(requisition);
        return requisition.getId();
    }

    private MaterialStockDO stockOf(Long projectId, Long materialId) {
        return materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
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
