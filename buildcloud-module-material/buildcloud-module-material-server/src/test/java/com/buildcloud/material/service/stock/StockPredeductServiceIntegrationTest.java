package com.buildcloud.material.service.stock;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.buildcloud.material.service.stock.redis.StockCacheKeys;
import com.buildcloud.material.service.stock.redis.StockPredeductResult;
import com.buildcloud.material.service.stock.redis.StockRollbackResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import cn.iocoder.yudao.framework.redis.config.YudaoRedisAutoConfiguration;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_PREDEDUCT_QTY_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StockPredeductServiceImpl} 的**集成测试**（跑真实 Redis，验证 Lua 语义与原子性）
 *
 * ========== 为什么必须是集成测试，而不是 H2 单测 ==========
 *
 * 本组件要验证的核心是「**Redis 里 Lua 脚本的原子性**」：脚本是否在单线程内一次完成
 * 「判重 → 校验 → 扣减 → 记账」。任何内存 Redis 替身（jedis-mock 之类）都可能用
 * 不同的执行模型，在替身上"不超卖"**不能证明**在真 Redis 上不超卖——
 * 这类替身的价值是验证 API 调用姿势，不是验证并发语义。
 * 因此本类**连本地已有 Redis**（docker `bc-redis`，127.0.0.1:16379），
 * 不引入任何新依赖（AGENTS §6.4：优先「连本地已有 Redis」而非 Testcontainers）。
 *
 * ========== 环境发现（硬编码优先，环境变量可覆盖，均无则跳过） ==========
 *
 * 密码不在代码里（AGENTS R-1）：连接参数来自
 * 1. 硬编码的本地默认：`127.0.0.1:16379`，密码取自 `BC_TEST_REDIS_PASSWORD`；
 * 2. 或直接引用 `BC_TEST_REDIS_*` 系列环境变量覆盖。
 * 读不到密码时本类**全部跳过**并在日志里说明（不会假绿：跳过会在 surefire 报告里体现）。
 * 本机的红/绿证据见 plan-w5 进度日志中的实际命令与输出。
 *
 * ========== 隔离方式 ==========
 *
 * 每个用例用**独立的伪 projectId**（取自一个递增计数器 + 时间戳低位），
 * 且每个用例前后清掉自己那对 key。不 `FLUSHDB`——共享 Redis 下那是破坏性操作，
 * 会清掉别的服务/别的 worker 的数据（L3-9 同款教训：不要动别人的东西）。
 *
 * @author buildcloud
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = StockPredeductServiceIntegrationTest.Application.class)
@ActiveProfiles("unit-test")
@TestPropertySource(properties = {
        // 连真实 Redis：覆盖 application-unit-test.yaml 里为"内存 Redis"准备的端口。
        // 这里只覆盖连接参数，不启用 RedisTestConfiguration（那是 jedis-mock，本类故意不用）。
        "spring.data.redis.host=${BC_TEST_REDIS_HOST:127.0.0.1}",
        "spring.data.redis.port=${BC_TEST_REDIS_PORT:16379}",
        "spring.data.redis.password=${BC_TEST_REDIS_PASSWORD:}",
        "spring.main.lazy-initialization=false"
})
@EnabledIf("redisAvailable")
public class StockPredeductServiceIntegrationTest {

    /**
     * 只装配 Redis 侧的最小上下文：不连 DB、不起 Nacos/MQ。
     * 这是"集成测试"与"整个服务启动"的区别——被测组件不需要 DB（本组件不做 DB 操作）。
     */
    @Import({
            YudaoRedisAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedissonAutoConfigurationV2.class,
            StockPredeductServiceImpl.class
    })
    public static class Application {
    }

    /**
     * 每个测试类实例一组独立 id，保证与其他测试/其他 worker 的 key 不重叠
     */
    private static final long PROJECT_BASE = 9_000_000L + (System.currentTimeMillis() % 100_000L) * 10L;

    private static long projectSeq = 0;

    @Resource
    private StockPredeductService stockPredeductService;

    @Resource
    private RedissonClient redissonClient;

    private Long projectId;

    private final Long materialId = 1L;

    /**
     * 探测本机 Redis 是否可用（TCP 连通 + 认证 PING）
     *
     * 用 Redisson 之外的最简方式探活：TCP 连得上且 PING 回 +PONG 即视为可用。
     * 放在 {@link EnabledIf} 里，是为了「环境没有 Redis 时跳过」而不是「失败」——
     * 但要强调：**跳过不等于通过**，surefire 报告会写明 skipped。
     */
    static boolean redisAvailable() {
        String host = System.getenv().getOrDefault("BC_TEST_REDIS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("BC_TEST_REDIS_PORT", "16379"));
        String password = System.getenv().getOrDefault("BC_TEST_REDIS_PASSWORD", "");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            if (!password.isEmpty()) {
                socket.getOutputStream().write(("AUTH " + password + "\r\n").getBytes());
                socket.getInputStream().read(new byte[64]);
            }
            socket.getOutputStream().write("PING\r\n".getBytes());
            byte[] buf = new byte[64];
            int len = socket.getInputStream().read(buf);
            String reply = new String(buf, 0, Math.max(len, 0));
            return reply.contains("PONG");
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    public void setUp() {
        // 每个用例一对新 key：彻底避免用例间互串（尤其是并发用例会留下 0 值）
        projectSeq++;
        projectId = PROJECT_BASE + projectSeq;
        cleanUp();
    }

    // ========== 一、基础语义 ==========

    @Test
    public void testPrededuct_basic_success() {
        // 初始化 100 → 预扣 30 → available=70、明细 1 条
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));

        assertEquals(0, new BigDecimal("100").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)), "初始化后可用量应为 100");

        StockPredeductResult result = stockPredeductService.tryPrededuct(projectId, materialId, "REQ-001",
                new BigDecimal("30"));
        assertEquals(StockPredeductResult.SUCCESS, result);

        // 断言 1：Redis 可用量（直接读 Redis，不读 Service 的包装）
        assertEquals(0, new BigDecimal("70").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)), "预扣后可用量应为 70");
        // 断言 2：明细只有 1 条且数量正确（对账依赖它）
        Map<String, BigDecimal> details = stockPredeductService.queryPredeductDetails(projectId, materialId);
        assertEquals(1, details.size());
        assertEquals(0, new BigDecimal("30").compareTo(details.get("REQ-001")));
        // 断言 3：单条查询与明细一致
        assertEquals(0, new BigDecimal("30").compareTo(
                stockPredeductService.queryPrededuct("REQ-001", projectId, materialId)));
    }

    @Test
    public void testPrededuct_notEnough_noSideEffect() {
        // 初始化 100 → 预扣 200 → NOT_ENOUGH，可用量与明细都不变
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));

        StockPredeductResult result = stockPredeductService.tryPrededuct(projectId, materialId, "REQ-002",
                new BigDecimal("200"));
        assertEquals(StockPredeductResult.NOT_ENOUGH, result);

        assertEquals(0, new BigDecimal("100").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)), "失败不得改动可用量");
        assertTrue(stockPredeductService.queryPredeductDetails(projectId, materialId).isEmpty(),
                "失败不得写入明细");
        assertNull(stockPredeductService.queryPrededuct("REQ-002", projectId, materialId));
    }

    @Test
    public void testPrededuct_exactlyEnough() {
        // 恰好等于：初始化 100 → 预扣 100 → SUCCESS，available=0（边界，off-by-one 最爱出问题的地方）
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));

        assertEquals(StockPredeductResult.SUCCESS,
                stockPredeductService.tryPrededuct(projectId, materialId, "REQ-003", new BigDecimal("100")));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)), "恰好扣完后可用量应为 0");
        assertEquals(1, stockPredeductService.queryPredeductDetails(projectId, materialId).size());

        // 再扣 1 → 不足（0 >= 1 不成立）
        assertEquals(StockPredeductResult.NOT_ENOUGH,
                stockPredeductService.tryPrededuct(projectId, materialId, "REQ-004", BigDecimal.ONE));
    }

    @Test
    public void testPrededuct_idempotent_sameReqNo() {
        // 幂等：同一 reqNo 预扣两次 → 第二次 IDEMPOTENT，available 只扣一次
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));

        assertEquals(StockPredeductResult.SUCCESS,
                stockPredeductService.tryPrededuct(projectId, materialId, "REQ-005", new BigDecimal("30")));
        assertEquals(StockPredeductResult.IDEMPOTENT,
                stockPredeductService.tryPrededuct(projectId, materialId, "REQ-005", new BigDecimal("30")));

        assertEquals(0, new BigDecimal("70").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)),
                "重复提交不得重复扣减（应仍是 70，而不是 40）");
        assertEquals(1, stockPredeductService.queryPredeductDetails(projectId, materialId).size(),
                "明细仍是 1 条");
    }

    @Test
    public void testPrededuct_needInit_whenKeyAbsent() {
        // key 不存在 → NEED_INIT(-1)，且不留下明细
        StockPredeductResult result = stockPredeductService.tryPrededuct(projectId, materialId, "REQ-006",
                BigDecimal.ONE);
        assertEquals(StockPredeductResult.NEED_INIT, result);
        assertTrue(stockPredeductService.queryPredeductDetails(projectId, materialId).isEmpty());
    }

    @Test
    public void testPrededuct_invalidQty_rejected() {
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));
        // 这里只断言错误**码**（错误码比较），不比对错误**文案**：
        // 断言工具会把 msg 与 "{0}" 模板做字符串比对，而异常里是已格式化的文本，必然不相等
        // （W5-P1 实测踩到：`expected: <...当前 {}> but was: <...当前 0>`）。
        // 文案属于展示层，钉死它会随文案微调而误红；码值才是契约。
        assertErrorCode(() -> stockPredeductService.tryPrededuct(projectId, materialId, "REQ-007",
                BigDecimal.ZERO), STOCK_PREDEDUCT_QTY_INVALID);
        assertErrorCode(() -> stockPredeductService.tryPrededuct(projectId, materialId, "REQ-008",
                new BigDecimal("-5")), STOCK_PREDEDUCT_QTY_INVALID);
        // 非法入参在 Java 层就被拦下，Redis 里不该有任何痕迹
        assertEquals(0, new BigDecimal("100").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)));
    }

    /**
     * 只断言错误码（不比对带参数的消息文案）
     *
     * @param runnable   被执行的逻辑
     * @param errorCode  期望错误码
     */
    private static void assertErrorCode(org.junit.jupiter.api.function.Executable runnable,
                                        cn.iocoder.yudao.framework.common.exception.ErrorCode errorCode) {
        ServiceException actual = assertThrows(ServiceException.class, runnable);
        assertEquals(errorCode.getCode(), actual.getCode());
    }

    // ========== 二、并发（核心：证明 Lua 原子性） ==========

    @Test
    public void testPrededuct_concurrent_noOversell() throws Exception {
        // ===== 场景（任务书指定）=====
        // 初始 100，20 个线程各预扣 10 → 期望 10 成功 / 10 库存不足，available=0，明细 10 条。
        //
        // ===== 为什么这个断言能证明 Lua 的原子性 =====
        // 「读 → 判断 → 扣 → 记」若拆成 4 条客户端命令，20 个线程会都读到 100，
        // 都判断"够"，都 DECRBY 10 → available = -100（超卖），成功 20 次。
        // 只有在脚本整体原子执行时，才可能出现"恰好 10 次成功"这种精确数字。
        // 20 线程 × 10 = 200 需求 vs 100 库存，判胜的界限极窄（不是"几乎都失败"或"几乎都成功"），
        // 所以这条用例对竞态极其敏感：一旦有人把脚本改成分步执行，它会立刻变红。
        final int initStock = 100;
        final int threadCount = 20;
        final int qtyPerThread = 10;
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal(initStock));

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger notEnoughCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                final String reqNo = "REQ-CONC-" + i;
                pool.submit(() -> {
                    try {
                        // 所有线程在同一时刻起跑，最大化竞态窗口
                        startGate.await();
                        StockPredeductResult result = stockPredeductService.tryPrededuct(
                                projectId, materialId, reqNo, new BigDecimal(qtyPerThread));
                        if (result == StockPredeductResult.SUCCESS) {
                            successCount.incrementAndGet();
                        } else if (result == StockPredeductResult.NOT_ENOUGH) {
                            notEnoughCount.incrementAndGet();
                        } else {
                            unexpected.compareAndSet(null,
                                    new IllegalStateException("并发下出现了非预期的结果：" + result));
                        }
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(60, TimeUnit.SECONDS), "并发预扣超时（60 秒）");
        } finally {
            pool.shutdownNow();
        }

        assertNull(unexpected.get(), "并发预扣不应有意外异常/结果：" + unexpected.get());

        // ===== 断言 1（核心）：恰好 10 成功 / 10 不足 =====
        assertEquals(initStock / qtyPerThread, successCount.get(),
                "库存 100 / 每次 10 → 应恰好 10 次成功，实际 " + successCount.get());
        assertEquals(threadCount - initStock / qtyPerThread, notEnoughCount.get(),
                "其余 10 次应库存不足，实际 " + notEnoughCount.get());

        // ===== 断言 2（核心）：available = 0，绝不出现负数 =====
        BigDecimal available = stockPredeductService.queryAvailable(projectId, materialId);
        assertNotNull(available);
        assertEquals(0, BigDecimal.ZERO.compareTo(available),
                "最终可用量应为 0（0 超卖），实际 " + available);
        assertTrue(available.signum() >= 0, "可用量绝不允许为负（超卖），实际：" + available);

        // ===== 断言 3：明细恰好 10 条，且合计 = 100 = 期初库存（账平）=====
        Map<String, BigDecimal> details = stockPredeductService.queryPredeductDetails(projectId, materialId);
        assertEquals(successCount.get(), details.size(), "每条成功预扣恰好一条明细");
        BigDecimal detailSum = details.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal(initStock).compareTo(detailSum),
                "明细合计必须等于期初库存（期初 == available + Σ明细），实际 " + detailSum);

        // 证据留痕：并发用例的实际数字打到 surefire 报告里，供汇报/复核引用（不靠人工数日志）
        System.out.printf("[并发预扣证据] 初始=%d 线程=%d 每次=%d → 成功=%d 不足=%d 最终可用=%s 明细条数=%d 明细合计=%s%n",
                initStock, threadCount, qtyPerThread, successCount.get(), notEnoughCount.get(),
                available.toPlainString(), details.size(), detailSum.toPlainString());
    }

    @Test
    public void testPrededuct_concurrentSameReqNo_onlyOnce() throws Exception {
        // 另一类并发：**同一张单**被 8 个线程同时提交（前端双击 / 网关重试 / MQ 重投）。
        // 期望：1 次 SUCCESS、7 次 IDEMPOTENT（幂等），available 只扣一次。
        // 这验证的是「判重与扣减在同一个脚本内」——若分两步（先读明细再扣），
        // 8 个线程会全部读到"没有明细"，然后全部扣减。
        final int threadCount = 8;
        final int qty = 10;
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger idempotentCount = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        StockPredeductResult result = stockPredeductService.tryPrededuct(projectId, materialId,
                                "REQ-SAME", new BigDecimal(qty));
                        if (result == StockPredeductResult.SUCCESS) {
                            successCount.incrementAndGet();
                        } else if (result == StockPredeductResult.IDEMPOTENT) {
                            idempotentCount.incrementAndGet();
                        } else {
                            unexpected.compareAndSet(null,
                                    new IllegalStateException("非预期结果：" + result));
                        }
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(60, TimeUnit.SECONDS), "并发重复提交超时");
        } finally {
            pool.shutdownNow();
        }

        assertNull(unexpected.get(), "不应有意外异常：" + unexpected.get());
        assertEquals(1, successCount.get(), "同一 reqNo 并发提交只能真扣一次");
        assertEquals(threadCount - 1, idempotentCount.get(), "其余必须走幂等");
        assertEquals(0, new BigDecimal("90").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)), "可用量只扣一次（100-10=90）");
        assertEquals(1, stockPredeductService.queryPredeductDetails(projectId, materialId).size());
        System.out.printf("[并发幂等证据] 初始=100 线程=%d 同一 reqNo 每次=%d → 真扣成功=%d 幂等=%d 最终可用=%s%n",
                threadCount, qty, successCount.get(), idempotentCount.get(),
                stockPredeductService.queryAvailable(projectId, materialId).toPlainString());
    }

    // ========== 三、回滚 ==========

    @Test
    public void testRollback_restoresAndClearsDetail() {
        // 预扣 30 → 回滚 → available 还原 100、明细清除
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));
        assertEquals(StockPredeductResult.SUCCESS,
                stockPredeductService.tryPrededuct(projectId, materialId, "REQ-100", new BigDecimal("30")));

        StockRollbackResult result = stockPredeductService.rollback("REQ-100", projectId, materialId,
                new BigDecimal("30"));
        assertEquals(StockRollbackResult.ROLLED_BACK, result);

        assertEquals(0, new BigDecimal("100").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)), "回滚后可用量应还原为 100");
        assertTrue(stockPredeductService.queryPredeductDetails(projectId, materialId).isEmpty(), "明细应清除");
        assertNull(stockPredeductService.queryPrededuct("REQ-100", projectId, materialId));
    }

    @Test
    public void testRollback_nonExistentReqNo_noSideEffect() {
        // 回滚一个不存在的 reqNo → 安全无副作用（驳回与关单并发时的正常结果）
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));

        StockRollbackResult result = stockPredeductService.rollback("REQ-NOT-EXIST", projectId, materialId,
                new BigDecimal("30"));
        assertEquals(StockRollbackResult.NOT_FOUND, result);
        assertEquals(0, new BigDecimal("100").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)),
                "无预扣记录时不得凭空加库存");
        assertTrue(stockPredeductService.queryPredeductDetails(projectId, materialId).isEmpty());
    }

    @Test
    public void testRollback_twice_onlyOnce() {
        // 回滚两次：第二次 NOT_FOUND，库存不得多还一次（超时关单 + 人工驳回并发的等价场景）
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));
        stockPredeductService.tryPrededuct(projectId, materialId, "REQ-101", new BigDecimal("30"));

        assertEquals(StockRollbackResult.ROLLED_BACK,
                stockPredeductService.rollback("REQ-101", projectId, materialId, new BigDecimal("30")));
        assertEquals(StockRollbackResult.NOT_FOUND,
                stockPredeductService.rollback("REQ-101", projectId, materialId, new BigDecimal("30")));
        assertEquals(0, new BigDecimal("100").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)), "重复回滚不得把库存还成 130");
    }

    @Test
    public void testRollback_qtyMismatch_refusedAndThrows() {
        // 传入数量与明细不一致 → 脚本拒绝改动（返回 -2），Java 侧抛业务错误码（不静默）
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));
        stockPredeductService.tryPrededuct(projectId, materialId, "REQ-102", new BigDecimal("30"));

        assertThrows(ServiceException.class,
                () -> stockPredeductService.rollback("REQ-102", projectId, materialId, new BigDecimal("40")));
        // 账目原样：可用量仍是 70、明细仍在（没有被"错误数量"改坏）
        assertEquals(0, new BigDecimal("70").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)));
        assertEquals(1, stockPredeductService.queryPredeductDetails(projectId, materialId).size());
    }

    // ========== 四、初始化模式 ==========

    @Test
    public void testInit_accumulate_keepsDetails() {
        // 日常追加入库：可用量累加，明细保留（在途单据不受影响）
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));
        stockPredeductService.tryPrededuct(projectId, materialId, "REQ-200", new BigDecimal("30"));

        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("50"),
                StockPredeductService.InitMode.ACCUMULATE);

        assertEquals(0, new BigDecimal("120").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)), "70 + 50 = 120");
        assertEquals(1, stockPredeductService.queryPredeductDetails(projectId, materialId).size(),
                "累加模式不得清空在途明细");
    }

    @Test
    public void testInit_reset_clearsDetails() {
        // 缓存重建：RESET 覆盖基线并清明细
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("100"));
        stockPredeductService.tryPrededuct(projectId, materialId, "REQ-201", new BigDecimal("30"));

        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("500"));

        assertEquals(0, new BigDecimal("500").compareTo(
                stockPredeductService.queryAvailable(projectId, materialId)));
        assertTrue(stockPredeductService.queryPredeductDetails(projectId, materialId).isEmpty(),
                "RESET 必须清掉旧明细（否则旧 reqNo 会命中幂等而无实际扣减）");
    }

    @Test
    public void testInit_accumulateWithoutBaseKey_throws() {
        // ACCUMULATE 但基线不存在 → 必须报错，而不是静默建一个 50 的基线
        assertThrows(ServiceException.class, () -> stockPredeductService.initAvailable(projectId, materialId,
                new BigDecimal("50"), StockPredeductService.InitMode.ACCUMULATE));
        assertNull(stockPredeductService.queryAvailable(projectId, materialId), "失败不得留下半成品 key");
    }

    // ========== 五、与 Redis 命令行的口径一致性 ==========

    @Test
    public void testKeyNaming_matchesRedisCliView() {
        // 验证 codec 口径：Java 写入的 key 必须能被 redis-cli 原样看到（不带 JSON 引号）。
        // 用 RedissonClient 直接按裸 key 读回存的值——如果 codec 错，这里读不到。
        stockPredeductService.initAvailable(projectId, materialId, new BigDecimal("42"));
        String plainKey = StockCacheKeys.availableStock(projectId, materialId);
        Object raw = redissonClient.getBucket(plainKey,
                org.redisson.client.codec.StringCodec.INSTANCE).get();
        assertNotNull(raw, "按 redis-cli 口径的裸 key 必须能读到值（key=" + plainKey + "）");
        assertEquals("42", String.valueOf(raw));
        // 顺带钉死 key 字面量，防止将来有人改了拼接顺序而无人察觉
        assertEquals("pm:stock:" + projectId + ":" + materialId, plainKey);
    }

    // ========== 清理 ==========

    /**
     * 删除本用例自己那对 key（不动别人的数据）
     */
    private void cleanUp() {
        List<String> keys = new ArrayList<>();
        keys.add(StockCacheKeys.availableStock(projectId, materialId));
        keys.add(StockCacheKeys.prededuct(projectId, materialId));
        redissonClient.getKeys().delete(keys.toArray(new String[0]));
    }

}
