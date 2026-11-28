package com.buildcloud.material.service.stock;

import com.buildcloud.material.service.stock.redis.StockCacheKeys;
import com.buildcloud.material.service.stock.redis.StockPredeductResult;
import com.buildcloud.material.service.stock.redis.StockRollbackResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_PREDEDUCT_INIT_FAILED;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_PREDEDUCT_QTY_INVALID;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_PREDEDUCT_ROLLBACK_CONFLICT;

/**
 * {@link StockPredeductService} 的实现：Redisson {@link RScript} 执行 {@code scripts/lua/} 下的脚本
 *
 * ========== 脚本从哪来（不猜 API，逐项核对过 4.8.0 的签名） ==========
 *
 * 基座 Redis starter（{@code yudao-spring-boot-starter-redis}）的注释写得明确：
 * 「采用 Spring Data Redis 操作 Redis，底层使用 Redisson 作为客户端」。material 模块
 * 经 {@code redisson-spring-boot-starter} 传递引入 {@code org.redisson:redisson:4.8.0}，
 * 由基座 {@code YudaoRedisAutoConfiguration} + {@code RedissonAutoConfigurationV2} 建好
 * {@link RedissonClient}（AGENTS §6.3：先读基座用法，不臆造 API）。
 *
 * 用到的 API（{@code javap} 逐项核对过 4.8.0 的字节码，非记忆）：
 * <pre>
 *   RedissonClient#getScript(Codec)                                   // 拿脚本执行器（可指定 codec）
 *   &lt;R&gt; R eval(Mode, ReturnType, List&lt;Object&gt; keys, Object... values) // 每次按脚本文本执行
 *   RScript.ReturnType.LONG                                           // Lua 整数 → Java Long
 *   StringCodec.INSTANCE                                              // 字符串与 redis-cli 同口径
 * </pre>
 * **踩坑记录（本 Phase 实际踩到）**：{@code ReturnType} 没有 {@code INTEGER} 常量，
 * 只有 {@code BOOLEAN/LONG/LIST/STRING/VALUE/MAPVALUE/MAPVALUELIST}；Lua 返回整数用
 * {@code LONG}（Redis 协议里脚本整数是 `<integer>`，映射到 Long）。凭印象写 `INTEGER`
 * 编译直接红——这正是 AGENTS §6.3「不猜接口」的实证。
 *
 * ========== 为什么用 eval 而不是 scriptLoad + evalSha ==========
 *
 * sha1 缓存有两种失效场景：① Redis 重启（脚本缓存清空）；② `SCRIPT FLUSH`（运维排查时常用）。
 * 用 evalSha 就必须自己处理 {@code NOSCRIPT} 并回退 eval（基座没有现成的封装，自己写一份
 * 等于重新实现 Redisson 已经做过的事）。Redisson 的 {@code eval} 内部就是
 * 「先 EVALSHA，遇 NOSCRIPT 自动 EVAL 并回填缓存」，语义等价且不用维护 sha1。
 * 首调用有一次完整的脚本文本传输（几百字节），之后同样走 EVALSHA——压测中这点差异可忽略。
 *
 * ========== Codec 必须显式指定 StringCodec ==========
 *
 * {@code getScript()} 不传 codec 时，Redisson 会用**默认 codec（JsonJacksonCodec）** 去
 * 序列化 KEYS/ARGV。那样 `pm:stock:1:2` 会被序列化成带引号的 JSON 字符串，
 * 与直接 `redis-cli` 写入 / 读取的 key 对不上（运维排查时"明明有值却查不到"）。
 * 显式传 {@link StringCodec#INSTANCE} 后，KEYS/ARGV 以纯字符串下发，与 redis-cli 完全同口径。
 *
 * ========== 脚本文件位置 ==========
 *
 * {@code src/main/resources/lua/*.lua}。源文件同时存在仓库根 {@code scripts/lua/}（可被
 * {@code docker exec bc-redis redis-cli EVAL} 直接手工验证、也便于评审阅读）；
 * 两份内容必须一致——单测 {@code StockPredeductLuaScriptSyncTest} 会逐字节比对，
 * 改一漏一立刻红，避免"文档里的脚本"与"真正跑的脚本"分叉。
 *
 * ========== 本类不做 DB 操作 ==========
 *
 * 与 Lua 脚本同口径：Redis 侧只负责内存票数的占用与归还，DB 的真实扣减/流水/状态推进
 * 由 W5-P2 的 MQ 消费者（按 version 乐观锁）完成。所以这里**没有**任何 Mapper 依赖，
 * 也就不存在「Redis 事务与 DB 事务混在一起」的问题。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class StockPredeductServiceImpl implements StockPredeductService {

    /**
     * 预扣脚本：scripts/lua/stock-prededuct.lua
     */
    static final String SCRIPT_PREDEDUCT = "lua/stock-prededuct.lua";

    /**
     * 回滚脚本：scripts/lua/stock-prededuct-rollback.lua
     */
    static final String SCRIPT_ROLLBACK = "lua/stock-prededuct-rollback.lua";

    /**
     * 初始化脚本：scripts/lua/stock-init.lua
     */
    static final String SCRIPT_INIT = "lua/stock-init.lua";

    /**
     * 脚本里用到的 KEYS 数量（三个脚本都是 2：可用量 + 明细）
     *
     * 保留为常量而不是散在注释里：Lua 脚本的 `KEYS[1]`/`KEYS[2]` 与这里的 List 顺序是
     * 一处隐式契约，数量对不上时 Redis 会直接报错（比顺序错安全），但没有常量化就没人对数。
     */
    static final int KEYS_COUNT = 2;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 脚本内容（启动时一次读入内存；Lua 脚本只有几百字节，没必要每次读文件）
     */
    private String predeductScript;

    private String rollbackScript;

    private String initScript;

    /**
     * 启动即加载脚本 + **探活执行一次**（fail fast）
     *
     * 为什么要在这里真跑一次：如果等到第一个业务请求才发现脚本语法错/Redis 连不上，
     * 表现为「接口 500 且原因藏在 Lua 报错里」，排查成本远高于启动失败。
     * 这里用一组必定不存在的 id 跑一次预扣脚本，**期望返回 NEED_INIT(-1)**：
     * 该路径只做 HEXISTS + GET，不写任何 key（探测不产生垃圾数据）。
     * 用 redis-cli 手敲同一命令的输出见计划进度日志（W5-P1）。
     */
    @PostConstruct
    public void init() throws IOException {
        this.predeductScript = readScript(SCRIPT_PREDEDUCT);
        this.rollbackScript = readScript(SCRIPT_ROLLBACK);
        this.initScript = readScript(SCRIPT_INIT);
        // fail fast：连不上 Redis 或脚本语法错误会在这里直接抛出，服务启动失败（而不是带病运行）
        Object probe = getScript().eval(RScript.Mode.READ_WRITE, predeductScript,
                RScript.ReturnType.LONG, buildKeys(PROBE_PROJECT_ID, PROBE_MATERIAL_ID),
                PROBE_REQ_NO, "1");
        log.info("[init][Lua 预扣脚本加载成功，探活返回 code={}（期望 {}：不存在的 key 应报需初始化）]",
                probe, StockPredeductResult.NEED_INIT.getCode());
    }

    /**
     * 探活用的伪 id（负数，DB 主键自增不会产生；即使误用也不会命中真实库存）
     */
    private static final Long PROBE_PROJECT_ID = -1L;
    private static final Long PROBE_MATERIAL_ID = -1L;
    private static final String PROBE_REQ_NO = "__probe__";

    // ========== 初始化 ==========

    @Override
    public void initAvailable(Long projectId, Long materialId, BigDecimal qty, InitMode mode) {
        if (qty == null || qty.signum() < 0) {
            throw exception(STOCK_PREDEDUCT_QTY_INVALID, qty);
        }
        InitMode effectiveMode = mode == null ? InitMode.RESET : mode;
        List<Object> keys = buildKeys(projectId, materialId);
        // TTL 传 0 = 不过期：库存是长期数据，过期后要靠入库重新同步才有值；
        // 是否需要 TTL 由 W5-P2 接入时结合"是否允许丢缓存自动重建"决定，本 Phase 默认不过期。
        Long code = getScript().eval(RScript.Mode.READ_WRITE, initScript, RScript.ReturnType.LONG,
                keys, qty.toPlainString(), "0", effectiveMode.name().toLowerCase());
        if (code == null || code != 1L) {
            // ACCUMULATE 模式下 key 不存在会返回 -4：这不是"可忽略"的情况——
            // 入库累加却把基线丢了，等于 Redis 与 DB 从这一刻起分叉，必须让调用方感知
            log.error("[initAvailable][初始化失败 projectId={} materialId={} qty={} mode={} code={}]",
                    projectId, materialId, qty, effectiveMode, code);
            throw exception(STOCK_PREDEDUCT_INIT_FAILED, projectId, materialId, effectiveMode.name(), code);
        }
        log.info("[initAvailable][Redis 可用量已同步 projectId={} materialId={} qty={} mode={}]",
                projectId, materialId, qty, effectiveMode);
    }

    // ========== 预扣 ==========

    @Override
    public StockPredeductResult tryPrededuct(Long projectId, Long materialId, String reqNo, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            // 预扣 0 或负数 = 无意义或反向加库存，必须在进 Lua 前拦住（脚本里也有兜底）
            throw exception(STOCK_PREDEDUCT_QTY_INVALID, qty);
        }
        Long code = getScript().eval(RScript.Mode.READ_WRITE, predeductScript, RScript.ReturnType.LONG,
                buildKeys(projectId, materialId), reqNo, qty.toPlainString());
        StockPredeductResult result = StockPredeductResult.parse(code);
        if (result == null) {
            // 未识别的返回码（脚本被改坏 / 返回类型不符）：不能当成成功，也不能当成库存不足，
            // 直接抛出，由上层按系统异常处理并告警
            log.error("[tryPrededuct][无法识别的脚本返回码 projectId={} materialId={} reqNo={} code={}]",
                    projectId, materialId, reqNo, code);
            throw new IllegalStateException("Lua 预扣脚本返回了未定义的返回码：" + code);
        }
        if (result != StockPredeductResult.SUCCESS) {
            log.info("[tryPrededuct][预扣未扣减 projectId={} materialId={} reqNo={} qty={} result={}]",
                    projectId, materialId, reqNo, qty, result);
        } else if (log.isDebugEnabled()) {
            // 成功路径默认不打 INFO：预扣是每次申领的必经动作，高并发下每单一条 INFO 会把
            // 日志盘写满（W5 压测 100 并发 × 持续跑），而"成功"本身不是需要排查的事件。
            // 需要逐单核对时把本类日志级别调到 DEBUG（或直接查 Redis 明细：reqNo→qty）。
            log.debug("[tryPrededuct][预扣成功 projectId={} materialId={} reqNo={} qty={}]",
                    projectId, materialId, reqNo, qty);
        }
        return result;
    }

    // ========== 回滚 ==========

    @Override
    public StockRollbackResult rollback(String reqNo, Long projectId, Long materialId, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw exception(STOCK_PREDEDUCT_QTY_INVALID, qty);
        }
        Long code = getScript().eval(RScript.Mode.READ_WRITE, rollbackScript, RScript.ReturnType.LONG,
                buildKeys(projectId, materialId), reqNo, qty.toPlainString());
        StockRollbackResult result = StockRollbackResult.parse(code);
        if (result == null) {
            log.error("[rollback][无法识别的脚本返回码 reqNo={} projectId={} materialId={} code={}]",
                    reqNo, projectId, materialId, code);
            throw new IllegalStateException("Lua 回滚脚本返回了未定义的返回码：" + code);
        }
        if (result == StockRollbackResult.NEED_INIT || result == StockRollbackResult.QTY_MISMATCH) {
            // 这两种都是"账目对不上"，脚本已拒绝改动。不能静默返回——否则库存差异无人知晓。
            log.error("[rollback][回滚被脚本拒绝 reqNo={} projectId={} materialId={} qty={} result={}]",
                    reqNo, projectId, materialId, qty, result);
            throw exception(STOCK_PREDEDUCT_ROLLBACK_CONFLICT, reqNo, result.getDesc());
        }
        log.info("[rollback][回滚结果 reqNo={} projectId={} materialId={} qty={} result={}]",
                reqNo, projectId, materialId, qty, result);
        return result;    }

    // ========== 查询 ==========

    @Override
    public BigDecimal queryAvailable(Long projectId, Long materialId) {
        // 用 RBucket<String> + StringCodec 直接读：Redis 里的值是 DECRBY 写下的整数字符串，
        // 走默认 JSON codec 会反序列化成 Integer/Long 再转 BigDecimal，容易在类型上翻车。
        String value = redissonClient.<String>getBucket(StockCacheKeys.availableStock(projectId, materialId),
                StringCodec.INSTANCE).get();
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.error("[queryAvailable][Redis 可用量值非法 projectId={} materialId={} value={}]",
                    projectId, materialId, value);
            return null;
        }
    }

    @Override
    public BigDecimal queryPrededuct(String reqNo, Long projectId, Long materialId) {
        String value = redissonClient.<String, String>getMap(StockCacheKeys.prededuct(projectId, materialId),
                StringCodec.INSTANCE).get(reqNo);
        return value == null ? null : new BigDecimal(value.trim());
    }

    @Override
    public Map<String, BigDecimal> queryPredeductDetails(Long projectId, Long materialId) {
        Map<String, String> raw = redissonClient.<String, String>getMap(
                StockCacheKeys.prededuct(projectId, materialId), StringCodec.INSTANCE).readAllMap();
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, BigDecimal> details = new LinkedHashMap<>(raw.size());
        raw.forEach((reqNo, qty) -> {
            try {
                details.put(reqNo, new BigDecimal(qty.trim()));
            } catch (NumberFormatException e) {
                // 明细值写坏（不应发生，Lua 只写数字）：跳过并告警，不整批失败——
                // 对账 Job 需要的是"能比对的那些"，脏数据本身就是要报的差异
                log.error("[queryPredeductDetails][明细值非法 projectId={} materialId={} reqNo={} qty={}]",
                        projectId, materialId, reqNo, qty);
            }
        });
        return details;
    }

    // ========== 内部方法 ==========

    /**
     * 构造脚本的 KEYS 列表（顺序与 Lua 脚本头注释严格一致：KEYS[1]=可用量、KEYS[2]=明细）
     *
     * 顺序错了不会报错，只会"把明细当可用量去 GET/DECRBY"——所以这里用定长 List 并在
     * 单测里断言两个元素分别等于 {@link StockCacheKeys} 的两个方法结果，用测试锁死顺序。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 长度为 {@link #KEYS_COUNT} 的不可变列表
     */
    private List<Object> buildKeys(Long projectId, Long materialId) {
        return Arrays.asList(
                StockCacheKeys.availableStock(projectId, materialId),
                StockCacheKeys.prededuct(projectId, materialId));
    }

    /**
     * 从 classpath 读取 Lua 脚本（UTF-8，无 BOM 要求）
     *
     * 用 {@link ClassPathResource} 而不是文件路径：打 fat jar 后脚本在 jar 内，
     * 用相对路径读会在生产形态下失败（本地 IDE 却正常，是典型的"本地过、部署炸"）。
     *
     * @param classpathLocation classpath 位置（如 lua/stock-prededuct.lua）
     * @return 脚本文本
     * @throws IOException 读取失败（脚本缺失 = 构建产物不完整，必须启动失败）
     */
    private String readScript(String classpathLocation) throws IOException {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        byte[] bytes = resource.getInputStream().readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 脚本执行器（显式 StringCodec，见类注释）
     */
    private RScript getScript() {
        return redissonClient.getScript((Codec) StringCodec.INSTANCE);
    }

}
