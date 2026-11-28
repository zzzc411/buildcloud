package com.buildcloud.material.service.requisition.strategy;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import com.buildcloud.material.service.requisition.mq.RequisitionDeductLocalTransactionService;
import com.buildcloud.material.service.requisition.mq.RequisitionDeductMessage;
import com.buildcloud.material.service.requisition.mq.RequisitionDeductMessageProducer;
import com.buildcloud.material.service.stock.StockPredeductService;
import com.buildcloud.material.service.stock.redis.StockPredeductResult;
import com.buildcloud.material.service.stock.redis.StockRollbackResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_SUBMIT_MQ_FAILED;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_ENOUGH;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_PREDEDUCT_BASELINE_MISSING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequisitionV2SubmitHandler} 的**纯业务单测**（H2；Redis / MQ 用内存 Stub）
 *
 * ========== 为什么 Redis 和 MQ 在这里用 Stub、而真 Redis 放在集成测试里 ==========
 *
 * 本类要验证的是「**决策逻辑**」：预扣失败该不该打 DB、多行部分失败要不要回滚、
 * 发消息失败要不要补偿、NEED_INIT 要不要从 DB 回填。这些是**分支正确性**，
 * 用 Stub 把每条分支精确激发出来，比连真 Redis 更能覆盖全部分支（真 Redis 下
 * "发消息抛异常"这种场景几乎无法构造）。
 *
 * 真 Redis + 真 RocketMQ 的**端到端账平**由
 * {@code RequisitionV2EndToEndIntegrationTest} 覆盖——那一个测"链路真的通"，
 * 这一个测"每条退路都走对了"。两者缺一不可，本类不承担"链路通"的证明责任。
 *
 * ========== Stub 的口径（必须与真 Lua 脚本语义一致，否则测试是自欺） ==========
 *
 * {@link StubPredeductService} 按 {@code stock-prededuct.lua} 的真实语义实现：
 * - key 不存在 → NEED_INIT(-1)；
 * - 同一 reqNo 已有明细 → IDEMPOTENT(2)，不重复扣；
 * - available < qty → NOT_ENOUGH(0)，不扣不记；
 * - 否则扣减 + 记明细 → SUCCESS(1)。
 * 这样"多行部分成功后回滚"的断言才有意义（回滚也按真脚本语义还原）。
 *
 * @author buildcloud
 */
@Import({RequisitionV2SubmitHandler.class, RequisitionDeductLocalTransactionService.class})
@Sql(scripts = "/sql/requisition-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class RequisitionV2SubmitHandlerTest extends BaseDbUnitTest {

    @Resource
    private RequisitionV2SubmitHandler handler;

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private RequisitionDeductLocalTransactionService localTransactionService;

    /**
     * 用 @MockBean 占位：让 Spring 容器有 `StockPredeductService` / `RequisitionDeductMessageProducer`
     * 两个 bean 可注入到 handler 里（否则 `@Resource` 注入失败、整个测试类起不来），
     * 然后在 `@BeforeEach` 里把 handler 的字段替换成本类的内存 Stub。
     *
     * 为什么不直接用 @MockBean 的 Mockito mock：本类要断言的是"Stub 里的可用量/明细怎么变"，
     * 而 Mockito 的 mock 没有状态，断言就得写成 verify(call sequence)，可读性差且容易漏掉
     * "回滚之后明细到底清没清"这类状态断言。Stub 有真实状态，断言就是"看最终账"。
     */
    @MockBean
    private StockPredeductService stockPredeductServiceBean;

    @MockBean
    private RequisitionDeductMessageProducer messageProducerBean;

    private StubPredeductService predeductService;

    private StubMessageProducer messageProducer;

    @BeforeEach
    public void setUpHandlers() throws Exception {
        predeductService = new StubPredeductService();
        messageProducer = new StubMessageProducer();
        inject(handler, "stockPredeductService", predeductService);
        inject(handler, "messageProducer", messageProducer);
        // localTransactionService / materialStockMapper 由容器注入，无需替换
    }

    // =================================================================================
    // 一、成功路径：预扣 → 发消息（单据靠 executeLocalTransaction 推进，不在 handler 里推进）
    // =================================================================================

    @Test
    public void testSubmit_success_predeductsAndSendsTransactionMessage() {
        insertStock(1L, 1L, "100");
        predeductService.init(1L, 1L, new BigDecimal("100"));
        // 模拟真实的 executeLocalTransaction：把单据推进到已提交(1)
        messageProducer.onSend = (message, reqNo) -> {
            // 模拟真实的 executeLocalTransaction：把单据推进到已提交(1)
            localTransactionService.markSubmitted(reqNo);
            return true;
        };
        RequisitionDO requisition = insertDraft("REQ-V2-OK", 1L);
        List<RequisitionItemDO> items = items(item(1L, "30"));

        handler.submit(requisition, items);

        // 断言 1：Redis 预扣生效
        assertEquals(0, new BigDecimal("70").compareTo(predeductService.queryAvailable(1L, 1L)),
                "Redis 可用量应扣到 70");
        // 断言 2：消息发了 1 条，且载荷是明细快照（消费者据此扣 DB，不反查库）
        assertEquals(1, messageProducer.sentMessages.size());
        RequisitionDeductMessage sent = messageProducer.sentMessages.get(0);
        assertEquals("REQ-V2-OK", sent.getReqNo());
        assertEquals(1, sent.getItems().size());
        assertEquals(0, new BigDecimal("30").compareTo(sent.getItems().get(0).getQuantity()));
        // 断言 3：事务参数是 reqNo（回查/本地事务都靠它定位单据）
        assertEquals("REQ-V2-OK", messageProducer.sentReqNos.get(0));
        // 断言 4（L3-10）：handler 不改单据状态，推进到已扣减(2) 是消费者的事
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(),
                requisitionMapper.selectById(requisition.getId()).getStatus(),
                "handler 只能让单据到已提交(1)，绝不能直接推到已扣减(2)");
        // 断言 5：**V2 不打 DB**（库存行一分未动，也没有流水）
        assertEquals(0, new BigDecimal("100").compareTo(stockOf(1L, 1L).getAvailableQty()),
                "V2 提交阶段不得改动 DB 库存（扣减由消费者完成）");
    }

    @Test
    public void testSubmit_multiItems_allPrededucted() {
        insertStock(1L, 2L, "50");
        insertStock(1L, 3L, "50");
        predeductService.init(1L, 2L, new BigDecimal("50"));
        predeductService.init(1L, 3L, new BigDecimal("50"));
        messageProducer.onSend = (message, reqNo) -> {
            // 模拟真实的 executeLocalTransaction：把单据推进到已提交(1)
            localTransactionService.markSubmitted(reqNo);
            return true;
        };
        RequisitionDO requisition = insertDraft("REQ-V2-MULTI", 1L);

        handler.submit(requisition, items(item(3L, "20"), item(2L, "10")));

        assertEquals(0, new BigDecimal("40").compareTo(predeductService.queryAvailable(1L, 2L)));
        assertEquals(0, new BigDecimal("30").compareTo(predeductService.queryAvailable(1L, 3L)));
        // 消息里的明细必须按 materialId 升序（与消费者的处理顺序一致）
        RequisitionDeductMessage sent = messageProducer.sentMessages.get(0);
        assertEquals(List.of(2L, 3L),
                sent.getItems().stream().map(RequisitionDeductMessage.Item::getMaterialId).toList(),
                "消息明细应按 materialId 升序");
    }

    // =================================================================================
    // 二、预扣失败：友好降级，不打 DB、不发消息
    // =================================================================================

    @Test
    public void testSubmit_notEnough_friendlyDegrade_noDbNoMessage() {
        insertStock(1L, 4L, "10");
        predeductService.init(1L, 4L, new BigDecimal("10"));
        RequisitionDO requisition = insertDraft("REQ-V2-ENOUGH", 1L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> handler.submit(requisition, items(item(4L, "99"))));

        assertEquals(STOCK_NOT_ENOUGH.getCode(), ex.getCode(), "应返回库存不足的业务错误码");
        // 断言 1（spec §6.1「失败 → 友好降级，不打 DB」）
        assertTrue(messageProducer.sentMessages.isEmpty(), "预扣失败不得发消息");
        assertEquals(0, new BigDecimal("10").compareTo(stockOf(1L, 4L).getAvailableQty()),
                "预扣失败不得改动 DB 库存");
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(),
                requisitionMapper.selectById(requisition.getId()).getStatus(),
                "预扣失败时单据应保持草稿（由调用方事务回滚）");
    }

    @Test
    public void testSubmit_multiItems_partialFailure_rollsBackEarlierRows() {
        // 物料 5 够（预扣成功）、物料 6 不够 → **必须回滚物料 5 的预扣**，否则账平不了
        insertStock(1L, 5L, "100");
        insertStock(1L, 6L, "10");
        predeductService.init(1L, 5L, new BigDecimal("100"));
        predeductService.init(1L, 6L, new BigDecimal("10"));
        RequisitionDO requisition = insertDraft("REQ-V2-PARTIAL", 1L);

        assertThrows(ServiceException.class,
                () -> handler.submit(requisition, items(item(5L, "40"), item(6L, "50"))));

        // 关键断言：物料 5 的预扣被回滚（回到 100），而不是留下 60
        assertEquals(0, new BigDecimal("100").compareTo(predeductService.queryAvailable(1L, 5L)),
                "第 2 行失败时，第 1 行的预扣必须被回滚");
        assertTrue(predeductService.detailsOf(1L, 5L).isEmpty(), "第 1 行的预扣明细也应被清除");
        assertTrue(messageProducer.sentMessages.isEmpty(), "整单失败不得发消息");
    }

    // =================================================================================
    // 三、事务消息发送失败：回滚预扣 + 单据回退草稿
    // =================================================================================

    @Test
    public void testSubmit_sendFailed_compensatesRedisAndRevertsDraft() {
        insertStock(1L, 7L, "100");
        predeductService.init(1L, 7L, new BigDecimal("100"));
        // 发送返回 false（half 消息没到 Broker / 本地事务 ROLLBACK）
        messageProducer.returnValue = false;
        RequisitionDO requisition = insertDraft("REQ-V2-SENDFAIL", 1L);
        // 模拟"本地事务已把单据推进到已提交(1)"（真实场景里可能已经成了一半）
        requisition.setStatus(RequisitionStatusEnum.SUBMITTED.getStatus());
        requisitionMapper.updateStatusCas(requisition.getId(), RequisitionStatusEnum.DRAFT.getStatus(),
                RequisitionStatusEnum.SUBMITTED.getStatus(), -1, null);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> handler.submit(requisition, items(item(7L, "30"))));

        assertEquals(REQUISITION_SUBMIT_MQ_FAILED.getCode(), ex.getCode(), "应返回消息发送失败的错误码");
        // 断言 1：Redis 预扣被回滚
        assertEquals(0, new BigDecimal("100").compareTo(predeductService.queryAvailable(1L, 7L)),
                "发消息失败后 Redis 预扣必须回滚（否则 Redis 永久少一块）");
        // 断言 2：单据退回草稿（用户可以重试）
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(),
                requisitionMapper.selectById(requisition.getId()).getStatus(), "单据应退回草稿");
    }

    @Test
    public void testSubmit_sendThrows_compensatesSame() {
        insertStock(1L, 8L, "100");
        predeductService.init(1L, 8L, new BigDecimal("100"));
        messageProducer.throwOnSend = new IllegalStateException("模拟 Namesrv 不可达");
        RequisitionDO requisition = insertDraft("REQ-V2-SENDEX", 1L);
        requisitionMapper.updateStatusCas(requisition.getId(), RequisitionStatusEnum.DRAFT.getStatus(),
                RequisitionStatusEnum.SUBMITTED.getStatus(), -1, null);

        assertThrows(ServiceException.class, () -> handler.submit(requisition, items(item(8L, "30"))));

        assertEquals(0, new BigDecimal("100").compareTo(predeductService.queryAvailable(1L, 8L)),
                "发送抛异常与返回 false 的补偿行为必须一致");
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(),
                requisitionMapper.selectById(requisition.getId()).getStatus());
    }

    // =================================================================================
    // 四、NEED_INIT：从 DB 回填基线后重试
    // =================================================================================

    @Test
    public void testSubmit_needInit_backfillsFromDbAndSucceeds() {
        insertStock(1L, 9L, "100");
        // **故意不初始化 Redis**：模拟"W5 之前入库的存量数据"/Redis 重启
        messageProducer.onSend = (message, reqNo) -> {
            // 模拟真实的 executeLocalTransaction：把单据推进到已提交(1)
            localTransactionService.markSubmitted(reqNo);
            return true;
        };
        RequisitionDO requisition = insertDraft("REQ-V2-NEEDINIT", 1L);

        handler.submit(requisition, items(item(9L, "30")));

        // 预扣成功后 Redis 应为 100 - 30 = 70（基线来自 DB 的 available_qty）
        assertEquals(0, new BigDecimal("70").compareTo(predeductService.queryAvailable(1L, 9L)),
                "NEED_INIT 时必须从 DB 回填基线再预扣");
        assertEquals(1, messageProducer.sentMessages.size(), "回填成功后应正常发消息");
    }

    @Test
    public void testSubmit_needInitAndNoStockRow_baselineMissingError() {
        // Redis 没 key、DB 也没有库存行 → 业务前置缺失（不是"库存不足"）
        predeductService.reset();
        RequisitionDO requisition = insertDraft("REQ-V2-NOSTOCK", 1L);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> handler.submit(requisition, items(item(99L, "1"))));

        assertEquals(STOCK_PREDEDUCT_BASELINE_MISSING.getCode(), ex.getCode(),
                "DB 无库存行时应报 1-102-080-003（而不是库存不足）");
        assertTrue(messageProducer.sentMessages.isEmpty());
    }

    // =================================================================================
    // 五、取消：回滚 Redis 预扣
    // =================================================================================

    @Test
    public void testCancel_rollsBackRedisPrededuct() {
        insertStock(1L, 10L, "100");
        predeductService.init(1L, 10L, new BigDecimal("100"));
        predeductService.tryPrededuct(1L, 10L, "REQ-V2-CANCEL", new BigDecimal("40"));
        assertEquals(0, new BigDecimal("60").compareTo(predeductService.queryAvailable(1L, 10L)));

        RequisitionDO requisition = insertDraft("REQ-V2-CANCEL", 1L);
        requisition.setStatus(RequisitionStatusEnum.SUBMITTED.getStatus());
        handler.cancel(requisition, items(item(10L, "40")));

        assertEquals(0, new BigDecimal("100").compareTo(predeductService.queryAvailable(1L, 10L)),
                "取消后 Redis 预扣必须完整还原");
        assertTrue(predeductService.detailsOf(1L, 10L).isEmpty(), "预扣明细应被清除");
    }

    // =================================================================================
    // 六、幂等：同一 reqNo 重复提交，Lua 返回 IDEMPOTENT 时不得当成失败
    // =================================================================================

    @Test
    public void testSubmit_idempotentPrededuct_notTreatedAsFailure() {
        insertStock(1L, 11L, "100");
        predeductService.init(1L, 11L, new BigDecimal("100"));
        // 先手工预扣一次（模拟上一轮"预扣成功、消息没发成"的重试场景）
        predeductService.tryPrededuct(1L, 11L, "REQ-V2-IDEM", new BigDecimal("20"));
        messageProducer.onSend = (message, reqNo) -> {
            // 模拟真实的 executeLocalTransaction：把单据推进到已提交(1)
            localTransactionService.markSubmitted(reqNo);
            return true;
        };
        RequisitionDO requisition = insertDraft("REQ-V2-IDEM", 1L);

        handler.submit(requisition, items(item(11L, "20")));

        // IDEMPOTENT 不是失败：不得回滚、不得重复扣
        assertEquals(0, new BigDecimal("80").compareTo(predeductService.queryAvailable(1L, 11L)),
                "IDEMPOTENT 应被当作成功，可用量保持 80（不得再扣成 60）");
        assertEquals(1, messageProducer.sentMessages.size(), "IDEMPOTENT 场景仍应发消息");
    }

    // =================================================================================
    // helpers
    // =================================================================================

    private void inject(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private RequisitionItemDO item(Long materialId, String qty) {
        return RequisitionItemDO.builder().materialId(materialId).quantity(new BigDecimal(qty)).build();
    }

    private List<RequisitionItemDO> items(RequisitionItemDO... items) {
        return new ArrayList<>(List.of(items));
    }

    private RequisitionDO insertDraft(String reqNo, Long projectId) {
        RequisitionDO requisition = RequisitionDO.builder()
                .reqNo(reqNo).projectId(projectId).applicantUserId(1L)
                .status(RequisitionStatusEnum.DRAFT.getStatus()).approvalStatus(-1)
                .build();
        requisitionMapper.insert(requisition);
        return requisition;
    }

    private void insertStock(Long projectId, Long materialId, String qty) {
        materialStockMapper.insert(MaterialStockDO.builder()
                .projectId(projectId).materialId(materialId)
                .availableQty(new BigDecimal(qty)).lockedQty(BigDecimal.ZERO).version(0)
                .build());
    }

    private MaterialStockDO stockOf(Long projectId, Long materialId) {
        return materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
    }

    // =================================================================================
    // Stub 实现（语义与真 Lua 脚本一致）
    // =================================================================================

    /**
     * 内存版 {@link StockPredeductService}：严格复刻 `stock-prededuct.lua` / `stock-prededuct-rollback.lua` 语义
     */
    static class StubPredeductService implements StockPredeductService {

        /** projectId:materialId → available */
        private final Map<String, BigDecimal> available = new HashMap<>();

        /** projectId:materialId → (reqNo → qty) */
        private final Map<String, Map<String, BigDecimal>> details = new HashMap<>();

        void reset() {
            available.clear();
            details.clear();
        }

        void init(Long projectId, Long materialId, BigDecimal qty) {
            initAvailable(projectId, materialId, qty, InitMode.RESET);
        }

        Map<String, BigDecimal> detailsOf(Long projectId, Long materialId) {
            return details.getOrDefault(key(projectId, materialId), Map.of());
        }

        @Override
        public void initAvailable(Long projectId, Long materialId, BigDecimal qty, InitMode mode) {
            String k = key(projectId, materialId);
            if (mode == InitMode.RESET) {
                available.put(k, qty);
                details.remove(k);
                return;
            }
            // ACCUMULATE：key 不存在时报错（与真脚本 -4 一致）
            if (!available.containsKey(k)) {
                throw new IllegalStateException("ACCUMULATE 需要 key 已存在");
            }
            available.put(k, available.get(k).add(qty));
        }

        @Override
        public StockPredeductResult tryPrededuct(Long projectId, Long materialId, String reqNo, BigDecimal qty) {
            String k = key(projectId, materialId);
            if (!available.containsKey(k)) {
                return StockPredeductResult.NEED_INIT;
            }
            Map<String, BigDecimal> d = details.computeIfAbsent(k, x -> new HashMap<>());
            if (d.containsKey(reqNo)) {
                return StockPredeductResult.IDEMPOTENT;
            }
            BigDecimal cur = available.get(k);
            if (cur.compareTo(qty) < 0) {
                return StockPredeductResult.NOT_ENOUGH;
            }
            available.put(k, cur.subtract(qty));
            d.put(reqNo, qty);
            return StockPredeductResult.SUCCESS;
        }

        @Override
        public StockRollbackResult rollback(String reqNo, Long projectId, Long materialId, BigDecimal qty) {
            String k = key(projectId, materialId);
            Map<String, BigDecimal> d = details.get(k);
            if (d == null || !d.containsKey(reqNo)) {
                return StockRollbackResult.NOT_FOUND;
            }
            BigDecimal recorded = d.get(reqNo);
            if (recorded.compareTo(qty) != 0) {
                return StockRollbackResult.QTY_MISMATCH;
            }
            available.put(k, available.get(k).add(qty));
            d.remove(reqNo);
            return StockRollbackResult.ROLLED_BACK;
        }

        @Override
        public BigDecimal queryAvailable(Long projectId, Long materialId) {
            return available.get(key(projectId, materialId));
        }

        @Override
        public BigDecimal queryPrededuct(String reqNo, Long projectId, Long materialId) {
            return detailsOf(projectId, materialId).get(reqNo);
        }

        @Override
        public Map<String, BigDecimal> queryPredeductDetails(Long projectId, Long materialId) {
            return new HashMap<>(detailsOf(projectId, materialId));
        }

        private String key(Long projectId, Long materialId) {
            return projectId + ":" + materialId;
        }

    }

    /**
     * 消息生产者的 Stub：记录发送次数/载荷，可配置返回值或抛异常
     */
    static class StubMessageProducer extends RequisitionDeductMessageProducer {

        final List<RequisitionDeductMessage> sentMessages = new ArrayList<>();

        final List<String> sentReqNos = new ArrayList<>();

        final AtomicInteger sendCount = new AtomicInteger();

        boolean returnValue = true;

        RuntimeException throwOnSend;

        java.util.function.BiFunction<RequisitionDeductMessage, String, Boolean> onSend;

        @Override
        public boolean send(RequisitionDeductMessage message, String reqNo) {
            sendCount.incrementAndGet();
            if (throwOnSend != null) {
                throw throwOnSend;
            }
            sentMessages.add(message);
            sentReqNos.add(reqNo);
            if (onSend != null) {
                onSend.apply(message, reqNo);
            }
            return returnValue;
        }

    }

    /** 编译期断言：Stub 必须仍是 {@link StockPredeductService}（防止接口改了 Stub 悄悄失配） */
    @SuppressWarnings("unused")
    private static final Class<?> SERVICE_TYPE = StockPredeductService.class;

    /** 未使用告警抑制：保留 assertInstanceOf 的静态导入以便将来扩展 */
    @SuppressWarnings("unused")
    private void typeCheck() {
        assertInstanceOf(StockPredeductService.class, predeductService);
    }

}
