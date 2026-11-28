package com.buildcloud.material.service.stock.reconcile;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.ReconcileLogDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionItemMapper;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.mysql.stock.ReconcileLogMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import com.buildcloud.material.enums.stock.ReconcileDiffTypeEnum;
import com.buildcloud.material.enums.stock.ReconcileStatusEnum;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import com.buildcloud.material.framework.job.config.ReconcileProperties;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StockReconcileServiceImpl} 的**事务级单测**（H2 真库 + 真事务，不起 Redis/MQ）
 *
 * ========== 覆盖点 ↔ 任务书验收标准的对应关系（W5-P4） ==========
 *
 * | 任务书要求 | 用例 |
 * | --- | --- |
 * | 造 3 类差异各 1 个 → 全部检出、计数正确 | {@link #testDetect_allThreeTypes_countCorrect()} |
 * | 修复幂等：同一批差异连跑 2 次 → 第二次 diff=0 | {@link #testFix_idempotent_secondRoundClean()} |
 * | 修复正确性：`Redis == DB == SUM(stock_log)` | {@link #testFix_allTypes_redisDbSumAligned()} |
 * | 人造差异①删 Redis key | {@link #testHumanMade_deletedRedisKey_detectedAndRebuilt()} |
 * | 人造差异②手动改 DB available | {@link #testHumanMade_manualDbChange_correctedFromDb()} |
 * | 人造差异③插入脏流水（DB 有、Redis 无） | {@link #testHumanMade_dirtyLog_removedAndRestored()} |
 * | D1 单据已取消 → 回滚 Redis 不补扣 | {@link #testD1_canceled_onlyRollbackRedis()} |
 * | D1 单据在途 → 补扣 | {@link #testD1_inFlight_replenishDeduction()} |
 * | 快照落库（三类各一行，status 正确，含修复前后值） | {@link #testSnapshot_threeRowsWithStatus()} |
 * | 修复总闸 fixEnabled=false（只检测不修） | {@link #testFixDisabled_detectOnly()} |
 * | D2 单据已扣减 → 不自动删流水（只告警） | {@link #testD2_deductedStatus_notAutoFixed()} |
 * | 分片：只处理本片，不碰别人 | {@link #testShard_onlyProcessesOwnShard()} |
 *
 * ========== 关键前置：库存必须"有账"（否则断言口径是错的） ==========
 *
 * 本项目的不变量是 **`bc_material_stock.available_qty == SUM(bc_stock_log.quantity)`**
 * （见 {@code StockServiceImpl#validateStockBalance} 与 spec §6.1）。所以造数据时**不能**直接
 * INSERT 一行 available=100 就完事——那样 SUM 是 0，账一开始就是空的。
 * 本类的每个维度都先 `insertInbound(初始量)` 写一条 biz_type=1 入库流水，再按场景调整。
 *
 * ========== Redis 为什么用替身（与 P3 同一口径） ==========
 *
 * H2 单测不该依赖真 Redis（CI 上可能没有），而本类要验证的是**判据与修复决策**，
 * 不是 Lua 的原子性。替身 {@link FakePredeductService} 严格按真 Lua 语义实现
 * （预扣成功扣减+记明细、回滚只删存在明细、RESET 清明细），并额外暴露"精确写值"的能力，
 * 用于构造人造差异（任务书要求的三种场景）。
 * 真 Redis 上的同一套断言由 {@code StockReconcileIntegrationTest} 覆盖（连 127.0.0.1:16379）。
 *
 * @author buildcloud
 */
@Import({StockReconcileServiceImpl.class, ReconcileDeductionTransaction.class,
        ReconcileProviderConfiguration.class})
@Sql(scripts = "/sql/reconcile-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class StockReconcileServiceTest extends BaseDbUnitTest {

    @Resource
    private StockReconcileService stockReconcileService;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private RequisitionItemMapper requisitionItemMapper;

    @Resource
    private ReconcileLogMapper reconcileLogMapper;

    /**
     * Redis 预扣替身（见类注释）。用 @MockBean 占位让容器有 bean，再在 @BeforeEach 里替换为有状态的替身
     * ——纯 Mockito mock 没有状态，断言就只能写 verify(调用序列)，读不出"修完之后账到底平没平"。
     */
    @MockBean
    private StockPredeductService stockPredeductServiceBean;

    private FakePredeductService prededuct;

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @BeforeEach
    public void setUpFake() throws Exception {
        prededuct = new FakePredeductService();
        inject(stockReconcileService, "stockPredeductService", prededuct);
        inject(stockReconcileService, "reconcileProperties", properties());
    }

    private static ReconcileProperties properties() {
        ReconcileProperties p = new ReconcileProperties();
        p.setAlertThreshold(0);
        p.setFixEnabled(true);
        p.setAlertUserIds("");
        return p;
    }

    // =================================================================================
    // 一、三类差异全部检出，计数正确
    // =================================================================================

    @Test
    public void testDetect_allThreeTypes_countCorrect() {
        // 维度 1（项目1/物料1）：D1 —— Redis 有预扣、DB 无扣减流水（单据在途）
        insertInbound(1L, 1L, "100");
        Long requisitionId = insertRequisition("REQ-D1", 1L, RequisitionStatusEnum.SUBMITTED);
        insertItem(requisitionId, 1L, "30");
        prededuct.init(1L, 1L, new BigDecimal("100"));
        prededuct.applyPrededuct(1L, 1L, "REQ-D1", new BigDecimal("30"));   // Redis=70，无 DB 流水

        // 维度 2（项目2/物料1）：D2 —— DB 有扣减流水、Redis 无明细
        insertInbound(2L, 1L, "100");
        applyDeduct(2L, 1L, "REQ-D2", "40");                                 // DB available=60，有 biz_type=2
        prededuct.init(2L, 1L, new BigDecimal("60"));                        // Redis 明细为空
        insertRequisition("REQ-D2", 2L, RequisitionStatusEnum.DRAFT);

        // 维度 3（项目3/物料1）：D3 —— 可用量不一致（无 D1/D2）
        insertInbound(3L, 1L, "80");
        prededuct.setAvailable(3L, 1L, new BigDecimal("50"));                 // Redis 少了 30

        // 维度 4（项目4/物料1）：干净 —— 不产生任何差异的对照
        insertInbound(4L, 1L, "20");
        prededuct.init(4L, 1L, new BigDecimal("20"));

        ReconcileResult result = stockReconcileService.reconcile();

        assertEquals(4, result.getScannedRows(), "应扫描 4 个库存维度");
        assertEquals(1, result.typeResult(ReconcileDiffTypeEnum.REDIS_WITHOUT_DB_LOG).getDiffCount(), "D1 应检出 1 条");
        assertEquals(1, result.typeResult(ReconcileDiffTypeEnum.DB_LOG_WITHOUT_REDIS).getDiffCount(), "D2 应检出 1 条");
        assertEquals(1, result.typeResult(ReconcileDiffTypeEnum.AVAILABLE_MISMATCH).getDiffCount(), "D3 应检出 1 条");
        assertEquals(3, result.totalDiffCount(), "三类合计应检出 3 条（干净维度不计数）");

        assertEquals("REQ-D1", result.typeResult(ReconcileDiffTypeEnum.REDIS_WITHOUT_DB_LOG)
                .getItems().get(0).getReqNo());
        assertEquals("REQ-D2", result.typeResult(ReconcileDiffTypeEnum.DB_LOG_WITHOUT_REDIS)
                .getItems().get(0).getReqNo());
        assertNull(result.typeResult(ReconcileDiffTypeEnum.AVAILABLE_MISMATCH).getItems().get(0).getReqNo(),
                "D3 是量级差异，不挂具体单据");
    }

    // =================================================================================
    // 二、修复正确性 + 幂等（核心）
    // =================================================================================

    @Test
    public void testFix_allTypes_redisDbSumAligned() {
        buildAllThreeTypes();

        ReconcileResult first = stockReconcileService.reconcile();

        assertEquals(3, first.totalDiffCount());
        assertEquals(3, first.totalFixedCount(), "三类各一条应全部修复成功（实际 " + first.summary() + "）");
        assertTrue(!first.hasUnfixed(), "第一轮不应有未修复项");

        // ===== D1：在途单据被补扣（DB 100 → 70，Redis 保持 70）=====
        assertQty("70", stockOf(1L, 1L).getAvailableQty(), "D1 在途单据应补扣 30");
        assertQty("70", prededuct.queryAvailable(1L, 1L), "Redis 应保持 70（与补扣后的 DB 对齐）");
        assertEquals(1, countLog(1L, 1L, "REQ-D1", StockBizTypeEnum.REQUISITION_OUT), "应补写一条 biz_type=2 流水");
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(),
                requisitionMapper.selectByReqNo("REQ-D1").getStatus(), "补扣后单据应推进到已扣减(2)");

        // ===== D2：脏流水被删、DB 回补（60 → 100），Redis 同轮跟上 =====
        assertQty("100", stockOf(2L, 1L).getAvailableQty(), "D2 应回补 40");
        assertEquals(0, countLog(2L, 1L, "REQ-D2", StockBizTypeEnum.REQUISITION_OUT), "脏扣减流水应被逻辑删除");
        // 不写 biz_type=4 冲正流水：删掉一笔负数流水（SUM +40）与 DB 回补（available +40）已经抵消，
        // 再写一笔正数冲正会让 SUM 比 available 多 40（历史实现正是这样，实测 SUM=130 vs available=100）。
        // 原始扣减行仍在表里（deleted=1），审计证据不丢，见 ReconcileDeductionTransaction 的演算注释。
        assertEquals(0, countLog(2L, 1L, "REQ-D2", StockBizTypeEnum.REVERSAL),
                "不得写冲正流水（否则 SUM 比 available 多一个回补量）");
        assertQty("100", prededuct.queryAvailable(2L, 1L), "D2 回补后 Redis 应在同一轮被对齐到 100");

        // ===== D3：Redis 被校正到 DB 值 =====
        assertQty("80", prededuct.queryAvailable(3L, 1L), "D3 应以 DB 为准把 Redis 校正成 80");
        assertQty("80", stockOf(3L, 1L).getAvailableQty(), "D3 不修改 DB（DB 是事实源）");

        // ===== 任务书硬指标：修复后 Redis == DB == SUM(stock_log) =====
        assertRedisEqualsDb(1L, 1L);
        assertRedisEqualsDb(2L, 1L);
        assertRedisEqualsDb(3L, 1L);
        assertReconciled(1L, 1L);
        assertReconciled(2L, 1L);
        assertReconciled(3L, 1L);
    }

    @Test
    public void testFix_idempotent_secondRoundClean() {
        buildAllThreeTypes();

        ReconcileResult first = stockReconcileService.reconcile();
        assertEquals(3, first.totalFixedCount());

        // 第二轮：同一批差异已经修完 → 一切对齐 → 检出 0
        ReconcileResult second = stockReconcileService.reconcile();
        assertEquals(0, second.totalDiffCount(),
                "修复幂等的判据：第二轮必须检出 0 条（实际 " + second.summary() + "）");
        assertEquals(0, second.totalFixedCount(), "第二轮没有差异可修");

        // 第三轮再跑一次也一样（不是"第二轮恰好清零"的偶然）
        assertEquals(0, stockReconcileService.reconcile().totalDiffCount(), "第三轮同样应为 0");

        // 快照也要体现"干净"
        assertEquals(ReconcileStatusEnum.CLEAN.getStatus(),
                second.typeResult(ReconcileDiffTypeEnum.REDIS_WITHOUT_DB_LOG).getStatus().getStatus());
        assertEquals(ReconcileStatusEnum.CLEAN.getStatus(),
                second.typeResult(ReconcileDiffTypeEnum.DB_LOG_WITHOUT_REDIS).getStatus().getStatus());
        assertEquals(ReconcileStatusEnum.CLEAN.getStatus(),
                second.typeResult(ReconcileDiffTypeEnum.AVAILABLE_MISMATCH).getStatus().getStatus());
    }

    // =================================================================================
    // 三、人造差异三场景（任务书逐条要求）
    // =================================================================================

    @Test
    public void testHumanMade_deletedRedisKey_detectedAndRebuilt() {
        // 场景①：删 Redis key（模拟 Redis 重启/被清/从没同步过）
        insertInbound(1L, 1L, "100");
        // 完全不 init → Redis 里没有 pm:stock:1:1

        ReconcileResult result = stockReconcileService.reconcile();

        ReconcileResult.TypeResult d3 = result.typeResult(ReconcileDiffTypeEnum.AVAILABLE_MISMATCH);
        assertEquals(1, d3.getDiffCount(), "Redis key 不存在必须被检出（不能当成可用量 0）");
        assertTrue(d3.getItems().get(0).getDescription().contains("key 不存在"),
                "描述要写明是缓存缺失而不是量漂移：" + d3.getItems().get(0).getDescription());
        assertEquals(1, d3.getFixedCount());
        assertQty("100", prededuct.queryAvailable(1L, 1L), "应以 DB 值重建 Redis 可用量");
        assertRedisEqualsDb(1L, 1L);

        assertEquals(0, stockReconcileService.reconcile().totalDiffCount(), "修复后应幂等");
    }

    @Test
    public void testHumanMade_manualDbChange_correctedFromDb() {
        // 场景②：手动改 DB available（DBA 直接 UPDATE，Redis 不知情）
        insertInbound(1L, 1L, "100");
        prededuct.init(1L, 1L, new BigDecimal("100"));
        // 模拟 DBA 把可用量改成 250（Redis 仍是 100）
        materialStockMapper.increaseAvailableQtyUnderLockForReconcile(1L, 1L, new BigDecimal("150"));

        ReconcileResult result = stockReconcileService.reconcile();

        assertEquals(1, result.typeResult(ReconcileDiffTypeEnum.AVAILABLE_MISMATCH).getDiffCount());
        assertEquals(1, result.typeResult(ReconcileDiffTypeEnum.AVAILABLE_MISMATCH).getFixedCount());
        assertQty("250", prededuct.queryAvailable(1L, 1L),
                "人工改库后应以 **DB 的 250** 校正 Redis（DB 是事实源，不能被旧的 100 覆盖回去）");
        assertRedisEqualsDb(1L, 1L);
        assertEquals(0, stockReconcileService.reconcile().totalDiffCount(), "修复后应幂等");
    }

    @Test
    public void testHumanMade_dirtyLog_removedAndRestored() {
        // 场景③：插入脏流水（DB 有 biz_type=2、Redis 明细里没有该 reqNo）
        insertInbound(1L, 1L, "100");
        applyDeduct(1L, 1L, "REQ-DIRTY", "30");        // DB available=70，有 biz_type=2
        prededuct.init(1L, 1L, new BigDecimal("70"));   // Redis = 70，明细为空
        insertRequisition("REQ-DIRTY", 1L, RequisitionStatusEnum.DRAFT); // 单据是草稿：说明这条流水多余

        ReconcileResult result = stockReconcileService.reconcile();

        ReconcileResult.TypeResult d2 = result.typeResult(ReconcileDiffTypeEnum.DB_LOG_WITHOUT_REDIS);
        assertEquals(1, d2.getDiffCount(), "脏流水必须被检出");
        assertEquals(1, d2.getFixedCount(), "草稿单据的脏流水应被自动删除并回补");
        assertQty("100", stockOf(1L, 1L).getAvailableQty(), "回补后 DB 应回到 100");
        assertEquals(0, countLog(1L, 1L, "REQ-DIRTY", StockBizTypeEnum.REQUISITION_OUT), "脏流水应被逻辑删除");
        // 见 testFix_allTypes 的同名断言：删负数流水 + 回补已互相抵消，不再写正数冲正
        assertEquals(0, countLog(1L, 1L, "REQ-DIRTY", StockBizTypeEnum.REVERSAL),
                "不得写冲正流水（否则 SUM 比 available 多一个回补量）");
        assertReconciled(1L, 1L);
        assertRedisEqualsDb(1L, 1L);

        // D2 的修复必须在**同一轮内**把 Redis 一起对齐（alignRedisAfterD2）：
        // 本维度已被排除出 D3 判定，若只回补 DB 不回写 Redis，就要等下一轮 D3 才收敛——
        // 那等于"一次修复制造一个新差异"，且第二轮必然再报一条，幂等性直接破产。
        assertEquals(0, stockReconcileService.reconcile().totalDiffCount(),
                "D2 修复后应直接幂等（不得留一条 D3 给下一轮）");
    }

    // =================================================================================
    // 四、D1 两种分支（任务书硬指标）
    // =================================================================================

    @Test
    public void testD1_canceled_onlyRollbackRedis() {
        // D1 分支一：单据已取消 → 只回滚 Redis，**不补扣、不写冲正流水**
        insertInbound(1L, 1L, "100");
        Long requisitionId = insertRequisition("REQ-D1-CANCELED", 1L, RequisitionStatusEnum.CANCELED);
        insertItem(requisitionId, 1L, "30");
        prededuct.init(1L, 1L, new BigDecimal("100"));
        prededuct.applyPrededuct(1L, 1L, "REQ-D1-CANCELED", new BigDecimal("30")); // Redis = 70

        ReconcileResult result = stockReconcileService.reconcile();

        assertEquals(1, result.typeResult(ReconcileDiffTypeEnum.REDIS_WITHOUT_DB_LOG).getDiffCount());
        assertEquals(1, result.typeResult(ReconcileDiffTypeEnum.REDIS_WITHOUT_DB_LOG).getFixedCount());

        // 关键断言 1：Redis 被还回去（70 → 100）
        assertQty("100", prededuct.queryAvailable(1L, 1L), "已取消单据的残留预扣应被回滚");
        assertNull(prededuct.queryPrededuct("REQ-D1-CANCELED", 1L, 1L), "预扣明细应被删除");

        // 关键断言 2：DB 一分未动（"回滚 Redis 不补扣"的核心）
        assertQty("100", stockOf(1L, 1L).getAvailableQty(), "已取消单据不得补扣 DB（DB 从没扣过）");
        assertEquals(0, countLog(1L, 1L, "REQ-D1-CANCELED", StockBizTypeEnum.REQUISITION_OUT),
                "不得写 biz_type=2 流水");
        assertEquals(0, countLog(1L, 1L, "REQ-D1-CANCELED", StockBizTypeEnum.REVERSAL),
                "不得写冲正流水（DB 没扣过，写冲正会让 SUM 凭空多一笔）");
        assertReconciled(1L, 1L);
        assertRedisEqualsDb(1L, 1L);

        assertEquals(0, stockReconcileService.reconcile().totalDiffCount(), "修复后应幂等");
    }

    @Test
    public void testD1_inFlight_replenishDeduction() {
        // D1 分支二：单据在途（已提交） → 补扣减
        insertInbound(1L, 1L, "100");
        Long requisitionId = insertRequisition("REQ-D1-INFLIGHT", 1L, RequisitionStatusEnum.SUBMITTED);
        insertItem(requisitionId, 1L, "30");
        prededuct.init(1L, 1L, new BigDecimal("100"));
        prededuct.applyPrededuct(1L, 1L, "REQ-D1-INFLIGHT", new BigDecimal("30")); // Redis = 70

        ReconcileResult result = stockReconcileService.reconcile();

        ReconcileResult.TypeResult d1 = result.typeResult(ReconcileDiffTypeEnum.REDIS_WITHOUT_DB_LOG);
        assertEquals(1, d1.getDiffCount());
        assertEquals(1, d1.getFixedCount());

        // 关键断言 1：DB 被补扣（100 → 70）
        assertQty("70", stockOf(1L, 1L).getAvailableQty(), "在途单据应补扣 30");
        assertEquals(1, countLog(1L, 1L, "REQ-D1-INFLIGHT", StockBizTypeEnum.REQUISITION_OUT),
                "应补写一条 biz_type=2 流水");
        StockLogDO deductLog = stockLogMapper.selectLatestByBizNoAndType(1L, 1L, "REQ-D1-INFLIGHT",
                StockBizTypeEnum.REQUISITION_OUT.getBizType());
        assertNotNull(deductLog);
        assertEquals(-1, deductLog.getQuantity().signum(), "扣减流水的 quantity 必须为负（DDL 符号约定）");
        assertQty("70", deductLog.getBalanceAfter(), "balance_after 必须是补扣后的余额");

        // 关键断言 2：单据状态推进到已扣减(2)（否则消费者重投会再扣一次）
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(),
                requisitionMapper.selectByReqNo("REQ-D1-INFLIGHT").getStatus());

        // 关键断言 3：Redis 不变（预扣仍然有效，两边现在一致）
        assertQty("70", prededuct.queryAvailable(1L, 1L));
        assertRedisEqualsDb(1L, 1L);
        assertReconciled(1L, 1L);

        assertEquals(0, stockReconcileService.reconcile().totalDiffCount(), "补扣后应幂等");
    }

    @Test
    public void testD1_draft_noAutoFix() {
        // 草稿单据上的预扣来源不明 → 不自动修复，只告警（见实现注释的取舍说明）
        insertInbound(1L, 1L, "100");
        insertRequisition("REQ-D1-DRAFT", 1L, RequisitionStatusEnum.DRAFT);
        prededuct.init(1L, 1L, new BigDecimal("100"));
        prededuct.applyPrededuct(1L, 1L, "REQ-D1-DRAFT", new BigDecimal("10")); // Redis = 90

        ReconcileResult result = stockReconcileService.reconcile();

        ReconcileResult.TypeResult d1 = result.typeResult(ReconcileDiffTypeEnum.REDIS_WITHOUT_DB_LOG);
        assertEquals(1, d1.getDiffCount(), "草稿单据上的预扣仍应被检出（不能静默忽略）");
        assertEquals(0, d1.getFixedCount(), "但不得自动修复");
        assertEquals(ReconcileStatusEnum.UNFIXED.getStatus(), d1.getStatus().getStatus(),
                "结论必须是「有差异未修复」→ 触发告警");
        assertTrue(result.hasUnfixed(), "Job 会据此把本轮记为失败");
        assertQty("90", prededuct.queryAvailable(1L, 1L), "两侧都不应被改动");
        assertQty("100", stockOf(1L, 1L).getAvailableQty());
    }

    // =================================================================================
    // 五、D2 的克制
    // =================================================================================

    @Test
    public void testD2_deductedStatus_notAutoFixed() {
        insertInbound(1L, 1L, "100");
        applyDeduct(1L, 1L, "REQ-D2-LIVE", "30");       // DB available=70
        prededuct.init(1L, 1L, new BigDecimal("70"));
        // 单据已扣减：说明这笔扣减是业务需要的，Redis 明细缺失是另一件事（需人工重建）
        insertRequisition("REQ-D2-LIVE", 1L, RequisitionStatusEnum.DEDUCTED);

        ReconcileResult result = stockReconcileService.reconcile();

        ReconcileResult.TypeResult d2 = result.typeResult(ReconcileDiffTypeEnum.DB_LOG_WITHOUT_REDIS);
        assertEquals(1, d2.getDiffCount());
        assertEquals(0, d2.getFixedCount(), "已扣减单据的流水不得被自动删除");
        assertEquals(1, countLog(1L, 1L, "REQ-D2-LIVE", StockBizTypeEnum.REQUISITION_OUT), "流水必须原样保留");
        assertQty("70", stockOf(1L, 1L).getAvailableQty(), "DB 不得被回补（回补会让库存虚高）");
    }

    // =================================================================================
    // 六、快照与配置、分片
    // =================================================================================

    @Test
    public void testSnapshot_threeRowsWithStatus() {
        buildAllThreeTypes();

        ReconcileResult result = stockReconcileService.reconcile();

        List<ReconcileLogDO> rows = reconcileLogMapper.selectListByBatchNo(result.getBatchNo());
        assertEquals(3, rows.size(), "每类差异应落一行快照（D1/D2/D3）");
        assertEquals(3, rows.stream().map(ReconcileLogDO::getCheckType).distinct().count(),
                "三行的 check_type 应互不相同");
        for (ReconcileLogDO row : rows) {
            assertEquals(1, row.getDiffCount(), "每类各 1 条差异");
            assertEquals(1, row.getFixedCount(), "每类都已修复");
            assertEquals(ReconcileStatusEnum.ALL_FIXED.getStatus(), row.getStatus(),
                    "全部修复后 status 应为 2（已全部修复）");
            assertNotNull(row.getDetailJson(), "detail_json 必须落库");
            assertTrue(row.getDetailJson().contains("\"before\"") && row.getDetailJson().contains("\"after\""),
                    "差异明细必须含修复前后值（审计要求）：" + row.getDetailJson());
        }
    }

    @Test
    public void testSnapshot_cleanRoundAllRowsClean() {
        insertInbound(1L, 1L, "20");
        prededuct.init(1L, 1L, new BigDecimal("20"));

        ReconcileResult result = stockReconcileService.reconcile();

        List<ReconcileLogDO> rows = reconcileLogMapper.selectListByBatchNo(result.getBatchNo());
        assertEquals(3, rows.size(), "干净轮次也要落三行（对应三类各 0 差异），便于趋势查询");
        for (ReconcileLogDO row : rows) {
            assertEquals(0, row.getDiffCount());
            assertEquals(ReconcileStatusEnum.CLEAN.getStatus(), row.getStatus());
        }
    }

    @Test
    public void testFixDisabled_detectOnly() throws Exception {
        buildAllThreeTypes();
        ReconcileProperties disabled = properties();
        disabled.setFixEnabled(false);
        inject(stockReconcileService, "reconcileProperties", disabled);

        ReconcileResult result = stockReconcileService.reconcile();

        assertEquals(3, result.totalDiffCount(), "关闭修复后仍必须检出全部差异");
        assertEquals(0, result.totalFixedCount(), "关闭修复后不得执行任何修复");
        assertQty("100", stockOf(1L, 1L).getAvailableQty(), "D1 维度 DB 不应被补扣");
        assertQty("60", stockOf(2L, 1L).getAvailableQty(), "D2 维度 DB 不应被回补");
        assertQty("50", prededuct.queryAvailable(3L, 1L), "D3 维度 Redis 不应被校正");
        assertTrue(result.hasUnfixed());
    }

    @Test
    public void testShard_onlyProcessesOwnShard() {
        // 分片：floorMod(projectId, 2) —— 项目 1 归 **1 号片**、项目 2 归 0 号片。
        // 注意别按"奇数→0 号片"的直觉写：`Math.floorMod(1, 2) == 1`，所以序号 1 才是项目 1 的归属片。
        insertInbound(1L, 1L, "100");
        prededuct.setAvailable(1L, 1L, new BigDecimal("80"));   // D3
        insertInbound(2L, 1L, "100");
        prededuct.setAvailable(2L, 1L, new BigDecimal("80"));   // D3

        ReconcileResult shard0 = stockReconcileService.reconcile(0, 2);
        assertEquals(1, shard0.getScannedRows(), "0 号片只应扫到项目 2");
        assertQty("100", prededuct.queryAvailable(2L, 1L), "0 号片应校正项目 2 的 Redis");
        assertQty("80", prededuct.queryAvailable(1L, 1L), "0 号片不得触碰项目 1");

        ReconcileResult shard1 = stockReconcileService.reconcile(1, 2);
        assertEquals(1, shard1.getScannedRows(), "1 号片只应扫到项目 1");
        assertQty("100", prededuct.queryAvailable(1L, 1L), "1 号片应校正项目 1 的 Redis");
    }

    // =================================================================================
    // helpers
    // =================================================================================

    /**
     * 构造三类差异各一条（含一个干净维度做对照）
     */
    private void buildAllThreeTypes() {
        // D1：在途单据，Redis 有预扣、DB 无流水
        insertInbound(1L, 1L, "100");
        Long requisitionId = insertRequisition("REQ-D1", 1L, RequisitionStatusEnum.SUBMITTED);
        insertItem(requisitionId, 1L, "30");
        prededuct.init(1L, 1L, new BigDecimal("100"));
        prededuct.applyPrededuct(1L, 1L, "REQ-D1", new BigDecimal("30"));

        // D2：脏流水，DB 有 biz_type=2、Redis 无明细；单据是草稿（不该扣）
        insertInbound(2L, 1L, "100");
        applyDeduct(2L, 1L, "REQ-D2", "40");            // DB available=60
        prededuct.init(2L, 1L, new BigDecimal("60"));
        insertRequisition("REQ-D2", 2L, RequisitionStatusEnum.DRAFT);

        // D3：量不一致，无 D1/D2
        insertInbound(3L, 1L, "80");
        prededuct.setAvailable(3L, 1L, new BigDecimal("50"));
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 造一个有账的库存维度：INSERT 库存行 + 写一条 biz_type=1 入库流水
     *
     * 必须成对做——只 INSERT 库存行会让 `available_qty != SUM(quantity)`，
     * 那样的维度本身就是"账不平"，会干扰对账断言（见类注释）。
     *
     * @param projectId  项目
     * @param materialId 物料
     * @param qty        入库量
     */
    private void insertInbound(Long projectId, Long materialId, String qty) {
        BigDecimal quantity = new BigDecimal(qty);
        materialStockMapper.insert(MaterialStockDO.builder()
                .projectId(projectId).materialId(materialId)
                .availableQty(quantity).lockedQty(ZERO).version(1)
                .build());
        stockLogMapper.insert(StockLogDO.builder()
                .projectId(projectId).materialId(materialId)
                .bizType(StockBizTypeEnum.INBOUND.getBizType())
                .bizNo("IN-" + projectId + "-" + materialId)
                .quantity(quantity).balanceAfter(quantity)
                .remark("测试入库")
                .build());
    }

    /**
     * 模拟一次已生效的申领扣减：DB 条件扣减 + 写 biz_type=2 流水（**不动 Redis**）
     *
     * 用于构造"DB 有扣减、Redis 无预扣"的 D2 场景；也用于造出 D1 场景里"单据已扣过"的对照组。
     *
     * @param projectId  项目
     * @param materialId 物料
     * @param reqNo      单号
     * @param qty        扣减量（正数）
     */
    private void applyDeduct(Long projectId, Long materialId, String reqNo, String qty) {
        BigDecimal quantity = new BigDecimal(qty);
        materialStockMapper.reduceAvailableQtyByBusinessKey(projectId, materialId, quantity);
        MaterialStockDO after = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        stockLogMapper.insert(StockLogDO.builder()
                .projectId(projectId).materialId(materialId)
                .bizType(StockBizTypeEnum.REQUISITION_OUT.getBizType())
                .bizNo(reqNo).quantity(quantity.negate()).balanceAfter(after.getAvailableQty())
                .remark("测试扣减：" + reqNo)
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

    private void insertItem(Long requisitionId, Long materialId, String qty) {
        requisitionItemMapper.insert(RequisitionItemDO.builder()
                .requisitionId(requisitionId).materialId(materialId)
                .quantity(new BigDecimal(qty)).returnedQty(ZERO)
                .build());
    }

    private MaterialStockDO stockOf(Long projectId, Long materialId) {
        return materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
    }

    private int countLog(Long projectId, Long materialId, String reqNo, StockBizTypeEnum bizType) {
        Long count = stockLogMapper.selectCountByBizNoAndType(projectId, materialId, reqNo, bizType.getBizType());
        return count == null ? 0 : count.intValue();
    }

    private void assertRedisEqualsDb(Long projectId, Long materialId) {
        BigDecimal db = stockOf(projectId, materialId).getAvailableQty();
        BigDecimal redis = prededuct.queryAvailable(projectId, materialId);
        assertNotNull(redis, "Redis 可用量不应为 null（本用例已初始化/重建）");
        assertEquals(0, db.compareTo(redis),
                String.format("Redis 可用量必须等于 DB 可用量：DB=%s Redis=%s", db, redis));
    }

    private void assertReconciled(Long projectId, Long materialId) {
        BigDecimal sum = stockLogMapper.selectSumQuantity(projectId, materialId);
        BigDecimal db = stockOf(projectId, materialId).getAvailableQty();
        assertEquals(0, db.compareTo(sum),
                String.format("SUM(stock_log.quantity) 必须等于 available_qty：SUM=%s available=%s", sum, db));
    }

    private void assertQty(String expected, BigDecimal actual) {
        assertQty(expected, actual, null);
    }

    private void assertQty(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message == null ? "数量不应为 null" : message);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                (message == null ? "" : message + " ") + "期望 " + expected + " 实际 " + actual);
    }

    // =================================================================================
    // Redis 替身（严格复刻真 Lua 语义 + 构造人造差异的写入口）
    // =================================================================================

    /**
     * 内存版 {@link StockPredeductService}
     *
     * 语义与 `scripts/lua/stock-prededuct.lua` / `stock-prededuct-rollback.lua` / `stock-init.lua` 一致：
     * - init RESET：写值 + 清明细；ACCUMULATE：必须在已有 key 上累加（否则抛，同脚本 -4）；
     * - prededuct：无 key → NEED_INIT；同 reqNo → IDEMPOTENT；不够 → NOT_ENOUGH；否则扣减 + 记明细；
     * - rollback：无明细 → NOT_FOUND（无副作用）；数量不符 → 抛（同真实现的 QTY_MISMATCH 抛错契约）；
     *   正常 → 还原 + 删明细。
     *
     * 额外提供 {@link #applyPrededuct} / {@link #setAvailable} 两个**构造用**入口，
     * 用于精确制造人造差异（不经过业务校验，正是模拟"脏数据"所需）。
     */
    static class FakePredeductService implements StockPredeductService {

        private final Map<String, BigDecimal> available = new HashMap<>();

        private final Map<String, Map<String, BigDecimal>> details = new HashMap<>();

        void init(Long projectId, Long materialId, BigDecimal qty) {
            initAvailable(projectId, materialId, qty, InitMode.RESET);
        }

        /**
         * 精确写入可用量（构造 D3 用；不改明细）
         */
        void setAvailable(Long projectId, Long materialId, BigDecimal qty) {
            available.put(key(projectId, materialId), qty);
        }

        /**
         * 模拟"预扣确实发生过"（扣可用量 + 记明细），用于构造 D1 场景
         */
        void applyPrededuct(Long projectId, Long materialId, String reqNo, BigDecimal qty) {
            String k = key(projectId, materialId);
            available.put(k, available.getOrDefault(k, ZERO).subtract(qty));
            details.computeIfAbsent(k, x -> new HashMap<>()).put(reqNo, qty);
        }

        @Override
        public void initAvailable(Long projectId, Long materialId, BigDecimal qty, InitMode mode) {
            String k = key(projectId, materialId);
            if (mode == InitMode.RESET) {
                available.put(k, qty);
                details.remove(k);
                return;
            }
            if (!available.containsKey(k)) {
                throw new IllegalStateException("ACCUMULATE 需要 key 已存在（对应 Lua 的 -4）");
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
            if (available.get(k).compareTo(qty) < 0) {
                return StockPredeductResult.NOT_ENOUGH;
            }
            available.put(k, available.get(k).subtract(qty));
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
            if (d.get(reqNo).compareTo(qty) != 0) {
                // 真实现抛业务异常（见 StockPredeductService#rollback 的契约）；替身同样抛，
                // 保证"对账遇到数量不一致时的行为"被判到
                throw new IllegalStateException("预扣数量不一致：明细=" + d.get(reqNo) + " 传入=" + qty);
            }
            available.put(k, available.getOrDefault(k, ZERO).add(qty));
            d.remove(reqNo);
            return StockRollbackResult.ROLLED_BACK;
        }

        @Override
        public BigDecimal queryAvailable(Long projectId, Long materialId) {
            return available.get(key(projectId, materialId));
        }

        @Override
        public BigDecimal queryPrededuct(String reqNo, Long projectId, Long materialId) {
            Map<String, BigDecimal> d = details.get(key(projectId, materialId));
            return d == null ? null : d.get(reqNo);
        }

        @Override
        public Map<String, BigDecimal> queryPredeductDetails(Long projectId, Long materialId) {
            Map<String, BigDecimal> d = details.get(key(projectId, materialId));
            return d == null ? new HashMap<>() : new HashMap<>(d);
        }

        private String key(Long projectId, Long materialId) {
            return projectId + ":" + materialId;
        }

    }

}
