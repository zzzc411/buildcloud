package com.buildcloud.material.service.stock;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.material.controller.admin.stock.vo.StockInboundReqVO;
import com.buildcloud.material.controller.admin.stock.vo.StockLogPageReqVO;
import com.buildcloud.material.controller.admin.stock.vo.StockPageReqVO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_INBOUND_QUANTITY_INVALID;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_EXISTS;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_UPDATE_CONFLICT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StockServiceImpl} 的单元测试类
 *
 * 风格照 {@link com.buildcloud.project.service.project.ProjectMilestoneServiceImplTest}：
 * 继承 {@link BaseDbUnitTest}（H2 内存库 + MyBatis），@Import 被测 Service；
 * 库存/流水两表见 src/test/resources/sql/stock-test-tables.sql（每次测试方法前置 DROP+CREATE）。
 *
 * 覆盖点（对应 plan-w4-material-v1 Phase 3 的验收标准）：
 * 1. **首次入库新建 stock + 流水，余额一致**；
 * 2. **二次入库累加，balance_after 与 available_qty 一致**；
 * 3. **乐观锁冲突重试**——两种路径都覆盖：
 *    - 重试后成功（testInbound_casConflictBetweenReadAndWrite_retrySucceeds：
 *      用真实行锁 + 未提交事务把「读」与「写」之间的窗口撑开，逼出一次真实 CAS 冲突）；
 *    - 重试耗尽后失败（testInbound_retryExhausted_throwsConflict：逻辑删除的行占着唯一键，
 *      每次插入撞键、每次 CAS 又因 deleted=0 失败，三次后必须报错而不是静默成功）；
 * 4. **SUM(stock_log.quantity) == stock.available_qty** 的贯穿式一致性断言；
 * 5. 真实并发：多线程同时入库，**总账不丢不重**（成功条数 × 单次数量 == 可用量 == 流水之和）。
 *
 * @author buildcloud
 */
@Import(StockServiceImpl.class)
@Sql(scripts = "/sql/stock-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class StockServiceImplTest extends BaseDbUnitTest {

    @Resource
    private StockServiceImpl stockService;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    /**
     * 用于在测试里手工控制事务边界（构造「未提交的并发写」场景）
     */
    @Resource
    private PlatformTransactionManager transactionManager;

    // ========== 一、首次入库 ==========

    @Test
    public void testInbound_firstTime_createStockAndLog() {
        // 准备参数：项目 1 物料 1 入库 100
        StockInboundReqVO reqVO = buildInboundReqVO(1L, 1L, "100.0000", "首批钢筋进场");

        // 调用
        StockLogDO stockLog = stockService.inbound(reqVO);

        // 断言 1：库存行被创建，available_qty=100、locked_qty=0、version=0
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L);
        assertNotNull(stock, "首次入库应创建库存行");
        assertQty("100", stock.getAvailableQty());
        assertQty("0", stock.getLockedQty());
        assertEquals(0, stock.getVersion(), "首次入库后 version 应为 0（插入初值，未经 CAS 累加）");

        // 断言 2：流水正确（biz_type=1、quantity 正数、balance_after=更新后可用量）
        assertEquals(StockBizTypeEnum.INBOUND.getBizType(), stockLog.getBizType());
        assertEquals(1L, stockLog.getProjectId());
        assertEquals(1L, stockLog.getMaterialId());
        assertQty("100", stockLog.getQuantity());
        assertQty("100", stockLog.getBalanceAfter());
        assertEquals("首批钢筋进场", stockLog.getRemark(), "流水备注记本次入库备注（审计口径）");
        assertNotNull(stockLog.getBizNo(), "入库单号不应为空");
        assertTrue(stockLog.getBizNo().startsWith("IN-"), "入库单号应以 IN- 开头：" + stockLog.getBizNo());

        // 断言 3：一致性 —— available_qty == SUM(stock_log.quantity)
        assertBalanceConsistent(1L, 1L, "100");
    }

    @Test
    public void testInbound_firstTime_decimalPrecisionPreserved() {
        // 精度用例：吨/立方这类物料需要 4 位小数（decimal(18,4)），验证不被静默截断
        stockService.inbound(buildInboundReqVO(1L, 1L, "12.3456", null));

        assertQty("12.3456", materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L).getAvailableQty());
        assertBalanceConsistent(1L, 1L, "12.3456");
    }

    @Test
    public void testInbound_quantityInvalid_negative() {
        // 负数入库必须被拒：减少库存要走申领扣减/退回/冲正各自链路（biz_type 2/3/4）
        StockInboundReqVO reqVO = buildInboundReqVO(1L, 1L, "-5.0000", null);

        assertServiceException(() -> stockService.inbound(reqVO), STOCK_INBOUND_QUANTITY_INVALID, "-5.0000");
        // 被拒后不得产生任何数据
        assertNull(materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L));
        assertTrue(stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).isEmpty());
    }

    @Test
    public void testInbound_quantityInvalid_zero() {
        StockInboundReqVO reqVO = buildInboundReqVO(1L, 1L, "0.0000", null);

        assertServiceException(() -> stockService.inbound(reqVO), STOCK_INBOUND_QUANTITY_INVALID, "0.0000");
        assertNull(materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L));
    }

    // ========== 二、二次入库累加 ==========

    @Test
    public void testInbound_accumulateTwice_balanceAfterMatchesAvailableQty() {
        // 第一次 100
        StockLogDO first = stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", "首批"));
        // 第二次 50.5
        StockLogDO second = stockService.inbound(buildInboundReqVO(1L, 1L, "50.5000", "补货"));

        // 断言 1：库存累加（若是覆盖会得到 50.5）
        assertQty("150.5", materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L).getAvailableQty());

        // 断言 2：两条流水，balance_after 递增且与当时可用量吻合
        assertQty("100", first.getBalanceAfter());
        assertQty("150.5", second.getBalanceAfter());
        assertEquals(2, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());

        // 断言 3：一致性 —— available_qty == SUM(quantity) = 100 + 50.5
        assertBalanceConsistent(1L, 1L, "150.5");
    }

    @Test
    public void testInbound_versionIncrementsByExactlyOne() {
        // 固定「每次成功入库 version 恰好 +1」的口径。
        // 若将来有人给基座注册了 OptimisticLockerInnerInterceptor 而这里又保留显式 CAS SQL，
        // version 会变成 +2 —— 本用例会立刻暴露该双自增。
        stockService.inbound(buildInboundReqVO(1L, 1L, "10.0000", null));
        assertEquals(0, materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L).getVersion(),
                "首插 version=0");

        stockService.inbound(buildInboundReqVO(1L, 1L, "10.0000", null));
        assertEquals(1, materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L).getVersion(),
                "第二次入库（第一次 CAS）后 version=1");

        stockService.inbound(buildInboundReqVO(1L, 1L, "10.0000", null));
        assertEquals(2, materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L).getVersion(),
                "第三次入库后 version=2（每次成功 CAS 恰好 +1）");
    }

    @Test
    public void testInbound_differentMaterialsAreIsolated() {
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        stockService.inbound(buildInboundReqVO(1L, 2L, "30.0000", null));

        assertQty("100", materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L).getAvailableQty());
        assertQty("30", materialStockMapper.selectByProjectIdAndMaterialId(1L, 2L).getAvailableQty());
        assertBalanceConsistent(1L, 1L, "100");
        assertBalanceConsistent(1L, 2L, "30");
    }

    @Test
    public void testInbound_sameMaterialDifferentProjects_isolated() {
        // 同物料不同项目：库存按项目隔离（数据权限与成本归集的前提）
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        stockService.inbound(buildInboundReqVO(2L, 1L, "77.0000", null));

        assertQty("100", materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L).getAvailableQty());
        assertQty("77", materialStockMapper.selectByProjectIdAndMaterialId(2L, 1L).getAvailableQty());
    }

    // ========== 三、乐观锁冲突（重试成功 / 重试耗尽） ==========

    @Test
    public void testInbound_casConflictBetweenReadAndWrite_retrySucceeds() throws Exception {
        // 本用例构造**一次真实发生的**乐观锁冲突，并验证重试后成功且总量正确。
        //
        // 时序设计（用真实行锁 + 未提交事务撑开「读」与「写」的窗口）：
        //   T0  库里已有库存行：available=100, version=0（主线程先入 100）
        //   T1  并发线程 A 开事务，CAS +10（version 0→1），**不提交**，睡 400ms 持锁
        //   T2  主线程调用 inbound(+5)：SELECT 读到的仍是已提交快照（available=100, version=0），
        //       随后它的 UPDATE ... WHERE version=0 被 A 的行锁挡住
        //   T3  A 提交 → 主线程 UPDATE 恢复执行，version 已是 1 ≠ 0 → 影响行数 0 → **冲突**
        //   T4  Service 重试：重新读（available=110, version=1）→ CAS 成功 → 115
        //
        // 断言关键：最终必须是 115（不是 105 = 丢失 A 的 10，也不是 110 = 丢掉自己的 5），
        // 即「冲突后重试不丢量」。
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));

        TransactionTemplate txA = new TransactionTemplate(transactionManager);
        CountDownLatch aHasUpdated = new CountDownLatch(1);
        AtomicReference<Throwable> aFailure = new AtomicReference<>();
        Thread concurrentWriter = new Thread(() -> {
            try {
                txA.executeWithoutResult(status -> {
                    MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L);
                    int rows = materialStockMapper.updateAvailableQtyIncrWithVersion(
                            stock.getId(), new BigDecimal("10.0000"), stock.getVersion());
                    assertEquals(1, rows, "并发写入者的 CAS 应成功");
                    // 事务未提交：此刻持行锁，且外部（READ COMMITTED）只能读到 version=0 的旧快照
                    aHasUpdated.countDown();
                    sleepQuietly(400);
                    // 对方也要落流水，否则余额与流水不一致（真实入库是同一事务）
                    stockLogMapper.insert(StockLogDO.builder()
                            .projectId(1L).materialId(1L)
                            .bizType(StockBizTypeEnum.INBOUND.getBizType())
                            .bizNo("IN-CONCURRENT-WRITER")
                            .quantity(new BigDecimal("10.0000"))
                            .balanceAfter(new BigDecimal("110.0000"))
                            .build());
                });
            } catch (Throwable t) {
                aFailure.compareAndSet(null, t);
            }
        }, "concurrent-writer");
        concurrentWriter.start();
        assertTrue(aHasUpdated.await(5, TimeUnit.SECONDS), "并发写入者应完成首次 CAS");

        // 主线程此刻入库 +5：会读到旧 version、被行锁挡住、解锁后 CAS 失败并重试
        StockLogDO log = stockService.inbound(buildInboundReqVO(1L, 1L, "5.0000", "冲突重试"));
        concurrentWriter.join(10_000);
        assertNull(aFailure.get(), "并发写入线程不应失败：" + aFailure.get());

        // 断言 1：冲突重试后总量正确（100 + 10 + 5），没有任何丢失
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L);
        assertQty("115", stock.getAvailableQty());
        assertQty("115", log.getBalanceAfter());

        // 断言 2：version 恰好推进两次（A 一次 + 自己重试成功后一次），证明真的走了「读-冲突-再读-成功」
        assertEquals(2, stock.getVersion(), "version 应为 2：并发写入者 +1、自己重试成功后 +1");

        // 断言 3：三条流水，余额与流水之和一致
        assertEquals(3, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size());
        assertBalanceConsistent(1L, 1L, "115");
    }

    @Test
    public void testInbound_retryExhausted_throwsConflictInsteadOfSilentSuccess() {
        // 重试**耗尽**路径必须报错，不能静默「成功」（不伪造成功）。
        //
        // 构造：库存行被逻辑删除。此后每次 inbound 都会：
        //   1) SELECT 查不到行（逻辑删除过滤）→ 走「新建」路径；
        //   2) INSERT 撞 uk_project_material（唯一键不含 deleted，被删行仍占位）
        //      → DuplicateKeyException → 本轮失败，继续下一轮；
        //   3) 三轮后抛 STOCK_UPDATE_CONFLICT。
        // 这同时覆盖了「并发首插撞唯一键」所依赖的那条异常分支。
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L);
        materialStockMapper.deleteById(stock.getId());
        assertNull(materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L), "逻辑删除后应查不到");

        assertServiceException(() -> stockService.inbound(buildInboundReqVO(1L, 1L, "5.0000", null)),
                STOCK_UPDATE_CONFLICT, StockServiceImpl.MAX_UPDATE_RETRY_TIMES);

        // 失败不得留下半截数据：流水仍只有删除前那一条
        assertEquals(1, stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L).size(),
                "入库失败不应产生新流水");
    }

    // ========== 四、真实并发（端到端证据） ==========

    @Test
    public void testInbound_concurrentCas_totalQuantityCorrectNoLoss() throws Exception {
        // 目标：验证纯 CAS 竞争下的总账正确性（不丢不重）。
        // 为了让所有线程都落在「更新已有行」这条路径上（而不是混入「首插撞唯一键」），
        // 这里先用 mapper 直接预置一行 0 基线库存——不用 Service 的 inbound，
        // 因为那会额外产生一条流水，干扰后面「流水条数 == 成功次数」的断言。
        materialStockMapper.insert(MaterialStockDO.builder()
                .projectId(1L).materialId(1L)
                .availableQty(BigDecimal.ZERO).lockedQty(BigDecimal.ZERO).version(0)
                .build());

        int threads = 4;
        BigDecimal perThread = new BigDecimal("10.0000");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicReference<ServiceException> conflictEx = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        stockService.inbound(buildInboundReqVO(1L, 1L, "10.0000", null));
                        successCount.incrementAndGet();
                    } catch (ServiceException e) {
                        // 允许因重试耗尽而快速失败（设计如此），但不得是别的业务错误
                        conflictEx.compareAndSet(null, e);
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(60, TimeUnit.SECONDS), "并发入库超时");
        } finally {
            pool.shutdownNow();
        }

        // 断言 0：不出现非预期异常
        assertNull(unexpected.get(), "并发入库不应抛非预期异常：" + unexpected.get());
        // 断言 1：若有个别失败，必须是「库存更新冲突」这一设计内的快速失败
        if (conflictEx.get() != null) {
            assertEquals(STOCK_UPDATE_CONFLICT.getCode(), conflictEx.get().getCode(),
                    "并发失败只允许是 STOCK_UPDATE_CONFLICT");
        }
        assertTrue(successCount.get() > 0, "至少有一次入库成功");

        // 断言 2（核心）：总账不丢不重 —— 可用量 == 成功次数 × 单次数量 == 流水之和
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L);
        BigDecimal expected = perThread.multiply(new BigDecimal(successCount.get()));
        assertQty(expected.toPlainString(), stock.getAvailableQty());

        List<StockLogDO> logs = stockLogMapper.selectListByProjectIdAndMaterialId(1L, 1L);
        assertEquals(successCount.get(), logs.size(), "每条成功入库必须恰好产生一条流水");
        BigDecimal logSum = stockLogMapper.selectSumQuantity(1L, 1L);
        assertNotNull(logSum);
        assertEquals(0, expected.compareTo(logSum), "流水之和应等于成功入库总量");
        assertEquals(0, stock.getAvailableQty().compareTo(logSum),
                "available_qty 必须等于 SUM(quantity)（无丢失更新）");

        // 断言 3：version 与成功次数吻合（每次成功 CAS 恰好 +1）——并发下也没有多推进/少推进
        assertEquals(successCount.get(), stock.getVersion(),
                "version 应等于成功 CAS 次数（不多不少，证明没有双自增或漏推进）");
    }

    @Test
    public void testInbound_concurrentFirstInsert_singleStockRow() throws Exception {
        // 并发首插：多个线程同时发现「没有库存行」，只能有一行落库（uk 兜底 + 撞键后转入更新）。
        // 这是「同项目同物料库存行唯一」这条验收标准的并发证据。
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        stockService.inbound(buildInboundReqVO(9L, 9L, "10.0000", null));
                        successCount.incrementAndGet();
                    } catch (ServiceException e) {
                        // 重试耗尽属设计内的快速失败
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(60, TimeUnit.SECONDS), "并发首插超时");
        } finally {
            pool.shutdownNow();
        }

        assertNull(unexpected.get(), "并发首插不应抛非预期异常：" + unexpected.get());
        assertTrue(successCount.get() > 0, "至少有一次入库成功");

        // 断言 1：无论并发多少次，库存行只有一行（Agent R-8 / DDL uk 的业务保证）
        StockPageReqVO pageReqVO = new StockPageReqVO();
        pageReqVO.setProjectId(9L);
        pageReqVO.setMaterialId(9L);
        assertEquals(1, stockService.getStockPage(pageReqVO).getTotal(),
                "同项目同物料必须只有一行库存");

        // 断言 2：总量正确（不因并发首插丢量或重复计数）
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(9L, 9L);
        BigDecimal expected = new BigDecimal("10.0000").multiply(new BigDecimal(successCount.get()));
        assertQty(expected.toPlainString(), stock.getAvailableQty());
        assertBalanceConsistent(9L, 9L, expected.toPlainString());
    }

    // ========== 五、查询 ==========

    @Test
    public void testGetStockPage_byProject() {
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        stockService.inbound(buildInboundReqVO(1L, 2L, "200.0000", null));
        stockService.inbound(buildInboundReqVO(2L, 1L, "300.0000", null));

        StockPageReqVO reqVO = new StockPageReqVO();
        reqVO.setProjectId(1L);

        PageResult<MaterialStockDO> page = stockService.getStockPage(reqVO);
        assertEquals(2, page.getTotal(), "只应返回项目 1 的两行库存");
        assertEquals(2, page.getList().size());
    }

    @Test
    public void testGetStockPage_byMaterialAcrossProjects() {
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        stockService.inbound(buildInboundReqVO(2L, 1L, "300.0000", null));
        stockService.inbound(buildInboundReqVO(1L, 2L, "200.0000", null));

        StockPageReqVO reqVO = new StockPageReqVO();
        reqVO.setMaterialId(1L);

        assertEquals(2, stockService.getStockPage(reqVO).getTotal(), "物料 1 在项目 1/2 各一行");
    }

    @Test
    public void testGetStockPage_bothFilters() {
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        stockService.inbound(buildInboundReqVO(1L, 2L, "200.0000", null));

        StockPageReqVO reqVO = new StockPageReqVO();
        reqVO.setProjectId(1L);
        reqVO.setMaterialId(2L);

        PageResult<MaterialStockDO> page = stockService.getStockPage(reqVO);
        assertEquals(1, page.getTotal());
        assertQty("200", page.getList().get(0).getAvailableQty());
    }

    @Test
    public void testGetStock_successAndNotFound() {
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L);

        MaterialStockDO found = stockService.getStock(stock.getId());
        assertQty("100", found.getAvailableQty());
        assertEquals(1L, found.getProjectId());

        // 不存在的 id → 返回 null（与基座 getXxx 口径一致）
        assertNull(stockService.getStock(999999L));
    }

    @Test
    public void testGetStockLogPage_byProjectAndMaterial_andBizType() {
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        stockService.inbound(buildInboundReqVO(1L, 1L, "50.0000", null));
        stockService.inbound(buildInboundReqVO(1L, 2L, "30.0000", null));

        // 按 projectId + materialId
        StockLogPageReqVO reqVO = new StockLogPageReqVO();
        reqVO.setProjectId(1L);
        reqVO.setMaterialId(1L);
        assertEquals(2, stockService.getStockLogPage(reqVO).getTotal());

        // 再按 bizType 过滤（当前全是入库=1，应仍是 2 条）
        reqVO.setBizType(StockBizTypeEnum.INBOUND.getBizType());
        assertEquals(2, stockService.getStockLogPage(reqVO).getTotal());

        // 换成申领扣减（2）→ 0 条（本 Phase 不产生该类型流水）
        reqVO.setBizType(StockBizTypeEnum.REQUISITION_OUT.getBizType());
        assertEquals(0, stockService.getStockLogPage(reqVO).getTotal());
    }

    @Test
    public void testGetStockLogPage_orderedByCreateTimeDesc() {
        StockLogDO first = stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        StockLogDO second = stockService.inbound(buildInboundReqVO(1L, 1L, "50.0000", null));

        StockLogPageReqVO reqVO = new StockLogPageReqVO();
        reqVO.setProjectId(1L);
        reqVO.setMaterialId(1L);

        PageResult<StockLogDO> page = stockService.getStockLogPage(reqVO);
        assertEquals(2, page.getTotal());
        assertEquals(second.getId(), page.getList().get(0).getId(), "最新流水应在最前");
        assertEquals(first.getId(), page.getList().get(1).getId());
    }

    @Test
    public void testGetStockLogPage_byBizNo() {
        StockLogDO log = stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        StockLogDO other = stockService.inbound(buildInboundReqVO(1L, 1L, "50.0000", null));
        // 两条流水的入库单号必须不同（否则按单号追溯会串单）
        assertNotEquals(log.getBizNo(), other.getBizNo(), "同一项目物料的入库单号不应重复");

        StockLogPageReqVO reqVO = new StockLogPageReqVO();
        reqVO.setProjectId(1L);
        reqVO.setBizNo(log.getBizNo());

        PageResult<StockLogDO> page = stockService.getStockLogPage(reqVO);
        assertEquals(1, page.getTotal(), "按入库单号应精确定位到一条流水");
        assertEquals(log.getId(), page.getList().get(0).getId());
    }

    @Test
    public void testGetStockLogPage_byCreateTimeRange() {
        // 覆盖任务书 ② 「按 projectId + materialId + bizType + 时间范围筛」里的**时间范围**维度。
        // 这是 idx_project_create(project_id, create_time) 的主路径，也是运维排查/对账最常用的查询。
        //
        // 做法：直接以 mapper 插入流水并显式指定 create_time，
        // 不依赖「inbound 连续调用恰好跨时间边界」——那种写法在快机器上会因同毫秒写入而随机红。
        LocalDateTime base = LocalDateTime.of(2026, 12, 1, 10, 0, 0);
        insertLogAt(1L, 1L, new BigDecimal("10.0000"), base.minusDays(2));  // 范围外（更早）
        insertLogAt(1L, 1L, new BigDecimal("20.0000"), base);               // 范围内（边界：起点）
        insertLogAt(1L, 1L, new BigDecimal("30.0000"), base.plusHours(1));  // 范围内
        insertLogAt(1L, 1L, new BigDecimal("40.0000"), base.plusDays(3));   // 范围外（更晚）

        StockLogPageReqVO reqVO = new StockLogPageReqVO();
        reqVO.setProjectId(1L);
        reqVO.setMaterialId(1L);
        // 闭区间 [base, base + 1 天)：应命中 20 与 30
        reqVO.setCreateTime(new LocalDateTime[]{base, base.plusDays(1).minusSeconds(1)});

        PageResult<StockLogDO> page = stockService.getStockLogPage(reqVO);
        assertEquals(2, page.getTotal(), "时间范围应只命中区间内的两条流水");
        assertEquals(2, page.getList().size());
        // 倒序：较晚的那条在前
        assertQty("30", page.getList().get(0).getQuantity());
        assertQty("20", page.getList().get(1).getQuantity());

        // 只传下限（开区间到未来）→ 命中 base 及之后全部 3 条
        reqVO.setCreateTime(new LocalDateTime[]{base, null});
        assertEquals(3, stockService.getStockLogPage(reqVO).getTotal(),
                "只传下限时应命中该时间点及之后的所有流水");

        // 只传上限 → 命中 base 之前 1 条
        reqVO.setCreateTime(new LocalDateTime[]{null, base.minusDays(1)});
        assertEquals(1, stockService.getStockLogPage(reqVO).getTotal(),
                "只传上限时应命中该时间点之前的流水");
    }

    @Test
    public void testGetStockLogPage_projectIdRequiredForShardRouting() {
        // 不带 projectId 的流水查询：本 Phase 允许（运维跨项目排查），但要在注释与 DTO 里
        // 明确「强烈建议传」——projectId 是 W6 的分片键，不带它分片后将全片广播（spec §6.3）。
        // 本用例锁住「当前行为 = 允许且返回全部」这一事实，将来若改为强制校验，
        // 会在此处暴露，提醒同步更新 DTO 注释与前端。
        insertLogAt(1L, 1L, new BigDecimal("10.0000"), LocalDateTime.of(2026, 12, 1, 10, 0));
        insertLogAt(2L, 1L, new BigDecimal("20.0000"), LocalDateTime.of(2026, 12, 2, 10, 0));

        StockLogPageReqVO reqVO = new StockLogPageReqVO();
        // 故意不设 projectId
        assertEquals(2, stockService.getStockLogPage(reqVO).getTotal(),
                "不带 projectId 时当前返回跨项目全部流水（W6 分片前应改为强制校验）");
    }

    // ========== 六、一致性校验 ==========

    @Test
    public void testValidateStockBalance_success() {
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        stockService.inbound(buildInboundReqVO(1L, 1L, "23.5000", null));

        assertQty("123.5", stockService.validateStockBalance(1L, 1L).getAvailableQty());
    }

    @Test
    public void testValidateStockBalance_stockNotExists() {
        assertServiceException(() -> stockService.validateStockBalance(999L, 999L), STOCK_NOT_EXISTS);
    }

    @Test
    public void testValidateStockBalance_detectsInconsistency() {
        // 人为制造不一致（绕过 Service 直接改库）：流水 100、库存改成 120 → 校验必须抛异常。
        // 这条用例确保「一致性断言」本身不是摆设（否则断言永远绿、失去意义）。
        stockService.inbound(buildInboundReqVO(1L, 1L, "100.0000", null));
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(1L, 1L);
        MaterialStockDO tampered = new MaterialStockDO();
        tampered.setId(stock.getId());
        tampered.setAvailableQty(new BigDecimal("120.0000"));
        materialStockMapper.updateById(tampered);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stockService.validateStockBalance(1L, 1L));
        assertTrue(ex.getMessage().contains("库存与流水不一致"), "异常信息应说明不一致：" + ex.getMessage());
    }

    // ========== 辅助方法 ==========

    /**
     * 构造入库请求
     */
    private StockInboundReqVO buildInboundReqVO(Long projectId, Long materialId, String quantity, String remark) {
        StockInboundReqVO reqVO = new StockInboundReqVO();
        reqVO.setProjectId(projectId);
        reqVO.setMaterialId(materialId);
        reqVO.setQuantity(new BigDecimal(quantity));
        reqVO.setRemark(remark);
        return reqVO;
    }

    /**
     * 一致性断言：stock.available_qty == SUM(stock_log.quantity)
     *
     * 既直接用聚合 SQL 复核，也走 Service 的 validateStockBalance（生产对账复用同一套逻辑），
     * 避免「两个数都算错但恰好相等」的假绿。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param expected   期望余额
     */
    private void assertBalanceConsistent(Long projectId, Long materialId, String expected) {
        BigDecimal logSum = stockLogMapper.selectSumQuantity(projectId, materialId);
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        assertNotNull(logSum, "SUM(quantity) 不应为 null（SQL 已 COALESCE）");
        assertEquals(0, stock.getAvailableQty().compareTo(logSum),
                String.format("余额与流水之和不一致：available_qty=%s, SUM(quantity)=%s",
                        stock.getAvailableQty(), logSum));
        assertQty(expected, stock.getAvailableQty());
        assertQty(expected, logSum);
        stockService.validateStockBalance(projectId, materialId);
    }

    /**
     * 数量断言：只比数值不比 scale
     *
     * H2 的 decimal(18,4) 读回后 scale 可能与期望字面量不同（如 150.5000 vs 150.5），
     * 用 compareTo 才是业务意义上的相等。
     */
    private void assertQty(String expected, BigDecimal actual) {
        assertNotNull(actual, "数量不应为 null");
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                "数量不匹配：期望 " + expected + "，实际 " + actual);
    }

    /**
     * 直接插入一条流水并显式指定 create_time
     *
     * 为什么不用 Service 的 inbound：inbound 的 create_time 由框架自动填充为「当前时间」，
     * 无法构造「跨天/跨小时」的时间范围用例；要测 idx_project_create 的区间过滤，
     * 必须能精确控制这一列。直接走 mapper 插入是最小依赖的做法。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param quantity   变动数量
     * @param createTime 流水时间（显式指定）
     */
    private void insertLogAt(Long projectId, Long materialId, BigDecimal quantity, LocalDateTime createTime) {
        StockLogDO log = StockLogDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .bizType(StockBizTypeEnum.INBOUND.getBizType())
                .bizNo("IN-TEST-" + createTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                .quantity(quantity)
                .balanceAfter(quantity)
                .build();
        // createTime 来自 BaseDO（父类），Lombok @Builder 只覆盖子类自身字段，故用 setter 显式指定。
        // DefaultDBFieldHandler#insertFill 只在 createTime 为 null 时才填当前时间，
        // 这里已赋值，不会被覆盖——这正是本 helper 能构造历史时间流水的前提。
        log.setCreateTime(createTime);
        stockLogMapper.insert(log);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
