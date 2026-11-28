package com.buildcloud.material.service.stock.reconcile;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import com.buildcloud.material.framework.job.config.ReconcileProperties;
import com.buildcloud.material.service.stock.StockPredeductService;
import com.buildcloud.material.service.stock.redis.StockRollbackResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link StockReconcileService} 的实现（spec v1.2 §6.1 步骤⑦ 的「对账兜底」）
 *
 * ========== 一轮对账的执行顺序（**顺序就是正确性的一部分**，别重排） ==========
 *
 * <pre>
 *   ① 加载本轮扫描集（bc_material_stock 全部活跃行，按 projectId 哈希分片）
 *   ② 逐行检测 D1 / D2 / D3 —— 全部**先检测、后修复**（不在循环里"边查边修"）
 *   ③ 按 D1 → D2 → D3 顺序统一修复
 *   ④ 落库快照（bc_reconcile_log，每类一行）
 *   ⑤ 超阈值 → 告警（站内信 + error 日志）
 * </pre>
 *
 * **为什么检测与修复分成两个阶段**：D3 的判定依赖 D1/D2 的检测结果——
 * 只要一个维度上存在 D1/D2，它的 `Redis.available` 与 `DB.available` 本来就"不该相等"
 * （Redis 少在途预扣、多已扣未删明细），此时报 D3 是**噪声**，会让告警里 90% 都是这三类的重复计数。
 * 分阶段后规则变得明确且可测试：**有 D1/D2 的维度不参与 D3 判定**。
 *
 * **但"排除"不等于"不管"**——这是本类最容易想错的一处（W5-P4 实测踩过）：
 * 被排除出 D3 判定的那个量差，必须由**造成它的那一类修复在同一轮内**补平：
 * - D1 补扣：DB 降下来、Redis 值不变 → 两侧自然相等，**不需要额外动作**；
 * - D2 回补：DB 涨上去、Redis 不变 → 两侧必然差一个回补量，**必须由 `alignRedisAfterD2` 收口**。
 * 若指望"下一轮 D3 兜底"，第二轮就必然再报一条 D3，幂等直接破产（实测 D1=0 D2=0 D3=1）。
 * 所以"晚一轮"这条兜底只对**意外**成立（例如 Redis 写失败），不能当作**设计路径**用。
 *
 * ========== 修复的幂等来源：看现状，不看记忆 ==========
 *
 * 每个修复动作执行前都重新读取当前状态，已经对齐的直接跳过：
 * - D1 回滚：`StockPredeductService#rollback` 的 Lua 在明细不存在时返回 NOT_FOUND 且无副作用；
 * - D1 补扣：先查 `bc_stock_log` 是否已有该 reqNo 的 biz_type=2 流水（与消费者行级幂等同一条判据），
 *   再用 `RequisitionMapper#deductCas`（WHERE status = 1）做整单闸门；
 * - D2 删流水：条件删除写成 `WHERE id = ? AND deleted = 0`，影响 0 行视为"已被别人修过"；
 *   删完再按 `alignRedisAfterD2` 把 Redis RESET 到 DB 值（绝对口径，结果与从哪里出发无关）；
 * - D3 校正：`initAvailable(RESET)` 是幂等写（同样的值写两次结果相同）。
 * 因此「同一批差异连续跑两轮」第二轮必然 diff=0，不需要"已修复"标记表。
 *
 * **这条幂等性对 D2 曾经不成立**（W5-P4 实测第二轮 D3=1）：D2 只回补 DB、不碰 Redis，
 * 而它造成的那个量差被"排除出 D3 判定"的规则挡在了检测阶段之外，于是只剩下一轮再报一条 D3。
 * 结论：**任何被排除出某个判定的维度，造成排除的那一类修复必须在同一轮内把它收拾干净**。
 *
 * ========== 为什么 @Transactional 只加在"单行修复"上，不加在整个 reconcile 上 ==========
 *
 * 一轮对账会扫几百到几千个库存维度、跑几秒到几分钟。若整轮一个事务：
 * 1. 长事务持有大量行锁，期间**正常申领/入库会被阻塞**（对账把业务拖死，最坏的一种副作用）；
 * 2. 中途任何一条失败就整轮回滚——已修好的差异也一起回滚，等于每轮都从头来，
 *    而失败的那条会一直挡着，形成"永远修不完"的死循环。
 * 所以粒度为「单行（单 reqNo / 单维度）一个事务」：修好一条就落一条，失败只影响那一条，
 * 并作为"未修复"计入快照与告警。这与 L3-18「先想清楚失败时停在哪个中间态」的口径一致。
 *
 * ========== 多租户 ==========
 *
 * 本类是**系统级运维任务**：要对所有租户的库存做对账。基座多租户插件默认会追加
 * `tenant_id = 当前上下文的值`，而 Job 线程没有租户上下文（恒为 null）→ 查不到任何数据。
 * 因此 {@link #reconcile(int, int)} 标 {@link TenantIgnore}（与基座 {@code TokenCleanJob} 同口径），
 * 由"每个租户各自一份 Job"改为"一份 Job 扫全部租户"。
 *
 * **代价（如实说明）**：快照表 `bc_reconcile_log.tenant_id` 在 TenantIgnore 下写入的是
 * 当前线程的 tenantId（null → 落库为 0/默认值），因此**无法按租户维度查快照**。
 * 这是本 Phase 的已知限制：差异明细里带了 projectId，可从项目反查租户；
 * 若将来要按租户出对账报表，应改为"按租户循环执行"（外层遍历 system_tenant，内层 TenantUtils.execute），
 * 而不是去掉 TenantIgnore（去掉后什么都扫不到，比现在的限制更糟）。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class StockReconcileServiceImpl implements StockReconcileService {

    /**
     * 批次号时间格式（`RCL-yyyyMMddHHmmss-SSS-分片号`）
     */
    static final DateTimeFormatter BATCH_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss-SSS");

    /**
     * detail_json 字段容量（varchar(4000)）与保留余量
     *
     * 必须**主动截断**：MySQL STRICT 模式下超长是**报错**（Data too long）而不是静默截断，
     * 一次大规模差异就会让快照整行插入失败，进而被外层 catch 成"对账失败"——
     * 而实际上差异已经被发现、甚至已经被修复了。审计表写不进去不能反过来影响对账结论。
     */
    static final int DETAIL_JSON_MAX_LENGTH = 4000;

    static final String TRUNCATED_SUFFIX = "...(truncated)";

    /**
     * 零值常量（避免各处 `new BigDecimal("0")` 重复构造，也让"0 不是 null"的语义集中在声明处）
     */
    private static final BigDecimal ZERO = BigDecimal.ZERO;
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

    @Resource
    private StockPredeductService stockPredeductService;

    @Resource
    private ReconcileAlertService reconcileAlertService;

    @Resource
    private ReconcileProperties reconcileProperties;

    /**
     * 事务承载组件（独立 bean；**必须注入代理对象**调用，见 {@link ReconcileDeductionTransaction} 类注释）
     */
    @Resource
    private ReconcileDeductionTransaction deductionTransaction;

    /**
     * Jackson（用于 detail_json 序列化）。**直接 new 一个**而不是注入容器的 ObjectMapper：
     * 容器的 ObjectMapper 被基座配置成"日期写时间戳、Long 写 String"等业务口径，
     * 而这里只是要把一个内部结构串成可读 JSON 存进审计表，用业务口径反而会让运维看不懂。
     */
    private final ObjectMapper detailJsonMapper = new ObjectMapper();

    // ================================================================================
    // 主流程
    // ================================================================================

    @Override
    @TenantIgnore
    public ReconcileResult reconcile(int shardIndex, int shardTotal) {
        long start = System.currentTimeMillis();
        int total = Math.max(shardTotal, 1);
        int index = Math.max(shardIndex, 0);
        String batchNo = "RCL-" + LocalDateTime.now().format(BATCH_TIME_FORMAT) + "-" + index;

        // ① 加载本分片的库存维度
        List<MaterialStockDO> stocks = loadShardStocks(index, total);
        log.info("[reconcile][对账开始 batchNo={} shard={}/{} 扫描维度数={} fixEnabled={}]",
                batchNo, index, total, stocks.size(), reconcileProperties.isFixEnabled());

        // ② 检测（全部先检测完，再统一修复，见类注释）
        List<ReconcileResult.DiffItem> d1Items = new ArrayList<>();
        List<ReconcileResult.DiffItem> d2Items = new ArrayList<>();
        List<ReconcileResult.DiffItem> d3Items = new ArrayList<>();
        // 出现 D1/D2 的维度不参与 D3 判定（见类注释「为什么检测与修复分成两个阶段」）
        Set<String> skipD3 = new LinkedHashSet<>();
        for (MaterialStockDO stock : stocks) {
            try {
                detect(stock, d1Items, d2Items, d3Items, skipD3);
            } catch (Exception e) {
                // 单个维度检测失败（如 Redis 超时）不中断整轮：其余维度照常对账，
                // 失败的那一条没有进入任何差异列表 → 会被下一轮重新检测到。
                // 这里必须打 error 且带上维度，否则"某维度永远检测失败"会表现为"这个维度永远没问题"。
                log.error("[reconcile][维度检测异常，跳过（下一轮会重试）projectId={} materialId={}]",
                        stock.getProjectId(), stock.getMaterialId(), e);
            }
        }

        // ③ 按 D1 → D2 → D3 顺序修复
        boolean fixEnabled = reconcileProperties.isFixEnabled();
        if (!fixEnabled) {
            log.warn("[reconcile][fixEnabled=false：只检测不修复 batchNo={} D1={} D2={} D3={}]",
                    batchNo, d1Items.size(), d2Items.size(), d3Items.size());
        }
        List<ReconcileResult.TypeResult> typeResults = new ArrayList<>(3);
        typeResults.add(fixType(ReconcileDiffTypeEnum.REDIS_WITHOUT_DB_LOG, d1Items, fixEnabled));
        typeResults.add(fixType(ReconcileDiffTypeEnum.DB_LOG_WITHOUT_REDIS, d2Items, fixEnabled));
        typeResults.add(fixType(ReconcileDiffTypeEnum.AVAILABLE_MISMATCH, d3Items, fixEnabled));

        ReconcileResult result = ReconcileResult.builder()
                .batchNo(batchNo)
                .shardIndex(index)
                .shardTotal(total)
                .scannedRows(stocks.size())
                .typeResults(typeResults)
                .costMillis(System.currentTimeMillis() - start)
                .build();

        // ④ 落库快照（每类一行）
        saveSnapshot(result);

        // ⑤ 告警（阈值判定在 ReconcileAlertService 内）
        if (reconcileAlertService.shouldAlert(result.totalDiffCount())) {
            reconcileAlertService.alert(batchNo, result.summary(), result.totalDiffCount());
        }

        log.info("[reconcile][对账完成 batchNo={} 扫描={} {} 检出总计={} 修复总计={} 耗时={}ms]",
                batchNo, stocks.size(), result.summary(), result.totalDiffCount(),
                result.totalFixedCount(), result.getCostMillis());
        return result;
    }

    /**
     * 加载本分片的库存维度
     *
     * 分片方式：按 `projectId` 取模（任务书要求"按 projectId 哈希"）。
     * **为什么在内存里分片而不是 SQL 里 `% 分片数`**：
     * - 注解 SQL 写 `MOD(project_id, ?) = ?` 会让 MySQL **无法使用任何索引**（函数作用在列上），
     *   对账要扫全表时多一次全表扫是可以接受的，但 W6 分库分表后这条 SQL 必须改写；
     * - 内存分片只需一次全表读（`SELECT ... WHERE deleted = 0`），后续按 projectId 取模即可，
     *   查询语句保持"普通、可被 W6 的路由识别"的形状。
     * 代价是大表时内存占用 = 库存行数 × 行宽（库存行数是"项目×物料"量级，万级以内可接受）。
     * W6 若上了 ShardingSphere，应改为「按分片键范围查询」而不是拉全表——这条留在注释里防止遗忘。
     *
     * @param shardIndex 分片序号
     * @param shardTotal 分片总数
     * @return 本分片的库存行（保持 id 升序，便于日志与断点定位）
     */
    private List<MaterialStockDO> loadShardStocks(int shardIndex, int shardTotal) {
        List<MaterialStockDO> all = materialStockMapper.selectList(new LambdaQueryWrapper<MaterialStockDO>()
                .orderByAsc(MaterialStockDO::getId));
        if (shardTotal <= 1) {
            return all;
        }
        List<MaterialStockDO> shard = new ArrayList<>();
        for (MaterialStockDO stock : all) {
            Long projectId = stock.getProjectId();
            if (projectId == null) {
                continue;
            }
            if (Math.floorMod(projectId, (long) shardTotal) == shardIndex) {
                shard.add(stock);
            }
        }
        return shard;
    }

    // ================================================================================
    // 二、检测：三类差异的判定
    // ================================================================================

    /**
     * 检测一个库存维度上的三类差异（**只读，不做任何修复**）
     *
     * @param stock     库存行
     * @param d1Items   D1 明细收集器
     * @param d2Items   D2 明细收集器
     * @param d3Items   D3 明细收集器
     * @param skipD3    出现 D1/D2 的维度（不参与 D3 判定）
     */
    private void detect(MaterialStockDO stock, List<ReconcileResult.DiffItem> d1Items,
                        List<ReconcileResult.DiffItem> d2Items, List<ReconcileResult.DiffItem> d3Items,
                        Set<String> skipD3) {
        Long projectId = stock.getProjectId();
        Long materialId = stock.getMaterialId();
        if (projectId == null || materialId == null) {
            log.error("[detect][库存行缺少 projectId/materialId，跳过 stockId={}]", stock.getId());
            return;
        }
        BigDecimal redisAvailable = stockPredeductService.queryAvailable(projectId, materialId);
        Map<String, BigDecimal> redisDetails = stockPredeductService.queryPredeductDetails(projectId, materialId);
        List<StockLogDO> deductLogs = stockLogMapper.selectListRequisitionOutLogs(projectId, materialId);

        // reqNo → 已落库的扣减数量（同一 reqNo 理论上只有一条，防御性地取第一条）
        Map<String, BigDecimal> dbDeductByReqNo = new LinkedHashMap<>();
        for (StockLogDO logRow : deductLogs) {
            if (StrUtil.isNotBlank(logRow.getBizNo())) {
                dbDeductByReqNo.putIfAbsent(logRow.getBizNo(),
                        logRow.getQuantity() == null ? ZERO : logRow.getQuantity().abs());
            }
        }

        // ---------- D1：Redis 有预扣、DB 无扣减流水 ----------
        for (Map.Entry<String, BigDecimal> entry : redisDetails.entrySet()) {
            String reqNo = entry.getKey();
            if (dbDeductByReqNo.containsKey(reqNo)) {
                continue;
            }
            d1Items.add(ReconcileResult.DiffItem.builder()
                    .projectId(projectId).materialId(materialId).reqNo(reqNo)
                    .description("Redis 明细有预扣但 bc_stock_log 无 biz_type=2 流水")
                    .before("redisPrededuct=" + plain(entry.getValue()) + ", dbLogCount=0")
                    .fixed(false)
                    .build());
            skipD3.add(dimensionKey(projectId, materialId));
        }

        // ---------- D2：DB 有扣减流水、Redis 无预扣 ----------
        for (Map.Entry<String, BigDecimal> entry : dbDeductByReqNo.entrySet()) {
            String reqNo = entry.getKey();
            if (redisDetails.containsKey(reqNo)) {
                continue;
            }
            d2Items.add(ReconcileResult.DiffItem.builder()
                    .projectId(projectId).materialId(materialId).reqNo(reqNo)
                    .description("bc_stock_log 有 biz_type=2 流水但 Redis 明细无该 reqNo")
                    .before("dbDeduct=" + plain(entry.getValue()) + ", redisPrededuct=null")
                    .fixed(false)
                    .build());
            skipD3.add(dimensionKey(projectId, materialId));
        }

        // ---------- D3：可用量不一致 ----------
        // 只看检测期的 skipD3（D1/D2 命中）——**不包括**"本轮 D2 可能修好"带来的量差。
        // 那一部分在修复阶段由 fixD2 收口，理由见 fixD2 的「先回补 DB，再按 DB 校正 Redis」。
        if (skipD3.contains(dimensionKey(projectId, materialId))) {
            return;
        }
        BigDecimal dbAvailable = stock.getAvailableQty() == null ? ZERO : stock.getAvailableQty();
        if (redisAvailable == null) {
            // key 不存在：不是"可用量 0"，而是"缓存没初始化"。以 DB 重建即可，
            // 但**不能**把它和"值不一致"混为一谈——一个是缓存缺失，一个是量漂移，
            // 排查时结论完全不同（见 StockPredeductService#queryAvailable 的 Javadoc）。
            d3Items.add(ReconcileResult.DiffItem.builder()
                    .projectId(projectId).materialId(materialId)
                    .description("Redis 可用量 key 不存在（缓存未初始化）")
                    .before("redis=null, db=" + plain(dbAvailable))
                    .fixed(false)
                    .build());
            return;
        }
        if (redisAvailable.compareTo(dbAvailable) != 0) {
            d3Items.add(ReconcileResult.DiffItem.builder()
                    .projectId(projectId).materialId(materialId)
                    .description("Redis 可用量与 DB 可用量不一致")
                    .before("redis=" + plain(redisAvailable) + ", db=" + plain(dbAvailable))
                    .fixed(false)
                    .build());
        }
    }

    // ================================================================================
    // 三、修复：按类型分派
    // ================================================================================

    /**
     * 修复某一类型的全部差异（逐条独立，单条失败不影响其它条）
     *
     * @param type       差异类型
     * @param items      检测出的差异明细（**会被就地更新**为修复结果）
     * @param fixEnabled 是否执行修复
     * @return 该类型的统计
     */
    private ReconcileResult.TypeResult fixType(ReconcileDiffTypeEnum type,
                                               List<ReconcileResult.DiffItem> items,
                                               boolean fixEnabled) {
        int fixed = 0;
        for (ReconcileResult.DiffItem item : items) {
            if (!fixEnabled) {
                item.setAction("未执行（fixEnabled=false）");
                continue;
            }
            try {
                boolean ok = switch (type) {
                    case REDIS_WITHOUT_DB_LOG -> fixD1(item);
                    case DB_LOG_WITHOUT_REDIS -> fixD2(item);
                    case AVAILABLE_MISMATCH -> fixD3(item);
                };
                item.setFixed(ok);
                if (ok) {
                    fixed++;
                }
            } catch (Exception e) {
                // 单条修复失败：记为未修复（进告警），继续下一条。
                // **不把异常外抛**：一轮对账里 1 条失败不该让另外 999 条也不修。
                item.setFixed(false);
                item.setAction(StrUtil.blankToDefault(item.getAction(), "修复") + "失败：" + e.getMessage());
                log.error("[fixType][修复失败 type={} projectId={} materialId={} reqNo={} action={}]",
                        type.getCode(), item.getProjectId(), item.getMaterialId(), item.getReqNo(),
                        item.getAction(), e);
            }
        }
        return ReconcileResult.TypeResult.builder()
                .type(type)
                .diffCount(items.size())
                .fixedCount(fixed)
                .items(new ArrayList<>(items))
                .build();
    }

    // ---------------- D1 ----------------

    /**
     * D1 修复：单据终态 → 回滚 Redis；单据在途 → 补扣减；其余 → 不修
     *
     * ========== 为什么以「单据状态」而不是「单据是否有冲正流水」做分支判据 ==========
     *
     * D1 的成因就是"DB 什么都没写"（既没扣减流水），所以"有没有冲正流水"在这里恒为否——
     * 那个判据（{@code StockRollbackService} 用的那条）在这个场景下没有区分力。
     * 能用的信息只有：①Redis 说这笔预扣真实发生过；②单据当前状态。
     *
     * - 状态 = 已取消(9)/已关闭(8)：这两个终态的语义就是"预扣已回滚"（spec §5.2 状态机）。
     *   现在 Redis 里还留着这笔明细，说明**回滚失败了**（Redis 写失败/进程崩溃在回滚之前）。
     *   单据已经不会再被扣减，所以这笔预扣就是纯粹的残留 → **只还 Redis，DB 一行不动**。
     *   **不写冲正流水**：DB 从没扣过，写一条"冲正 +qty"会让 `SUM(quantity)` 与 `available_qty`
     *   立刻不平（凭空多出一笔正数）。这正是 `StockRollbackService` 在 V2 在途分支里
     *   "只还 Redis、不写冲正流水"的同一条纪律。
     *
     * - 状态 已提交(1)/已扣减(2)/已出库(3)/已完成(4)/部分回退(5)：单据是活单，Redis 的预扣
     *   是它"占住"的库存。DB 没落扣减流水说明消费者的整笔事务回滚了（典型：DB 条件扣减影响 0 行）。
     *   → **补扣减**：DB 条件扣减 + 写 biz_type=2 流水 + 状态 CAS 1→2。
     *   补扣依据是**库里明细的数量**（`bc_requisition_item.quantity`），不是 Redis 里记的数——
     *   两者不一致时以库为准（Redis 只是缓存），并在日志里把两个数都打出来。
     *
     * - 状态 草稿(0)/单据不存在/明细缺失：没有任何可靠依据判断"该扣多少/该不该扣"。
     *   → **不修，只告警**。这里的克制是刻意的：D1 里唯一"看起来能修"的动作是回滚 Redis，
     *   但草稿单据本来就不该有预扣（说明是预扣后本地事务失败的残留），而"
     *   单据不存在"更可能是整单被物理删除——两种情况都可能是数据事故的一部分，
     *   自动把 Redis 还回去会**掩盖**事故（第二天没人再看到差异）。
     *
     * @param item 差异明细（会被就地更新）
     * @return 是否修复成功
     */
    private boolean fixD1(ReconcileResult.DiffItem item) {
        Long projectId = item.getProjectId();
        Long materialId = item.getMaterialId();
        String reqNo = item.getReqNo();
        BigDecimal redisQty = stockPredeductService.queryPrededuct(reqNo, projectId, materialId);
        if (redisQty == null) {
            // 上一轮/并发对账已经处理过：无副作用视为"已对齐"
            item.setFixed(true);
            item.setAction("跳过（Redis 明细已不存在，已对齐）");
            item.setAfter("redisPrededuct=null");
            return true;
        }

        RequisitionDO requisition = requisitionMapper.selectByReqNo(reqNo);
        if (requisition == null || !projectId.equals(requisition.getProjectId())) {
            item.setAction("无（申领单不存在或项目不匹配，需人工核对）");
            item.setAfter("redisPrededuct=" + plain(redisQty));
            log.error("[fixD1][D1 差异但申领单不存在，需人工核对 reqNo={} projectId={} materialId={} redisQty={}]",
                    reqNo, projectId, materialId, redisQty);
            return false;
        }

        Integer status = requisition.getStatus();
        boolean terminal = RequisitionStatusEnum.CANCELED.getStatus().equals(status)
                || RequisitionStatusEnum.CLOSED.getStatus().equals(status);
        if (terminal) {
            return rollbackRedisOnly(item, requisition, redisQty);
        }
        if (RequisitionStatusEnum.DRAFT.getStatus().equals(status)) {
            item.setAction("无（单据仍是草稿，预扣来源不明，需人工核对）");
            item.setAfter("redisPrededuct=" + plain(redisQty));
            log.error("[fixD1][D1 差异但单据是草稿（预扣后本地事务失败的残留？），需人工核对 reqNo={} "
                    + "projectId={} materialId={} redisQty={}]", reqNo, projectId, materialId, redisQty);
            return false;
        }
        return replenishDeduction(item, requisition, redisQty);
    }

    /**
     * D1 分支一：单据已取消/已关闭 → 只回滚 Redis（DB 一行不动，不写冲正流水）
     *
     * @param item        差异明细
     * @param requisition 申领单
     * @param redisQty    Redis 里记录的预扣量
     * @return 是否成功
     */
    private boolean rollbackRedisOnly(ReconcileResult.DiffItem item, RequisitionDO requisition,
                                      BigDecimal redisQty) {
        // 用**Redis 里记录的数**回滚（而不是库里明细的数）：回滚脚本会拿它与明细比对，
        // 传库里的数会在两者不一致时被脚本拒绝（QTY_MISMATCH），反而修不动。
        // 这里的目的是"清掉这笔 Redis 残留"，量以 Redis 自己记的为准最自洽。
        StockRollbackResult result = stockPredeductService.rollback(item.getReqNo(),
                item.getProjectId(), item.getMaterialId(), redisQty);
        boolean ok = result == StockRollbackResult.ROLLED_BACK || result == StockRollbackResult.NOT_FOUND;
        item.setFixed(ok);
        item.setAction(ok ? "回滚 Redis 预扣（单据已终止：status=" + requisition.getStatus() + "）"
                : "回滚 Redis 预扣失败（脚本返回 " + result + "）");
        item.setAfter(ok
                ? "redisPrededuct=null, dbLogCount=0（DB 未扣过，不写冲正流水）"
                : "redisPrededuct=" + plain(stockPredeductService.queryPrededuct(item.getReqNo(),
                item.getProjectId(), item.getMaterialId())));
        log.info("[fixD1][回滚 Redis 残留预扣 reqNo={} projectId={} materialId={} qty={} status={} result={}]",
                item.getReqNo(), item.getProjectId(), item.getMaterialId(), redisQty,
                requisition.getStatus(), result);
        return ok;
    }

    /**
     * D1 分支二：单据在途 → 补扣减（DB 条件扣减 + 写 biz_type=2 流水 + 状态 CAS 1→2）
     *
     * ========== 幂等三道（与 {@code RequisitionDeductTransactionService} 完全同构） ==========
     *
     * 1. 行级流水判据：本单本物料已有 biz_type=2 流水 → 已扣过，跳过；
     * 2. DB 条件扣减 `available_qty >= qty`：**最后一道实时防线**，防超卖；
     * 3. 状态 CAS `WHERE status = 1`：整单闸门，第二个执行者影响 0 行 → 回滚本条事务。
     *
     * **为什么可以直接复用消费者的那套动作而不发 MQ 消息**：
     * 发消息是"让消费者去扣"，而消费者扣的时候仍会走上面三道判据——即结果等价。
     * 但发消息多一个不确定环节（消息可能再次丢失，那就白等一轮对账），而这里已经有
     * 完整的项目/物料/数量信息，直接扣是确定性的。这也符合 spec §6.1 步骤⑦ 的
     * "差异告警 + 修复"（修复是 Job 的职责，不是"再投一次消息碰运气"）。
     *
     * 注意成功后的 Redis：**不清明细**。消费者正常路径也不清（明细由后续驳回/关单的回滚脚本
     * 或对账清理），而 D1 修完这一条之后，Redis 明细与 DB 流水已一一对应，不再是差异。
     *
     * @param item        差异明细
     * @param requisition 申领单（在途）
     * @param redisQty    Redis 里记录的预扣量（仅用于日志比对）
     * @return 是否成功
     */
    private boolean replenishDeduction(ReconcileResult.DiffItem item, RequisitionDO requisition,
                                       BigDecimal redisQty) {
        Long projectId = item.getProjectId();
        Long materialId = item.getMaterialId();
        String reqNo = item.getReqNo();

        // 幂等 ①：行级流水判据（本单本物料是否已有扣减流水）
        Long existing = stockLogMapper.selectCountByBizNoAndType(projectId, materialId, reqNo,
                StockBizTypeEnum.REQUISITION_OUT.getBizType());
        if (existing != null && existing > 0) {
            item.setFixed(true);
            item.setAction("跳过（已有 biz_type=2 流水，已对齐）");
            item.setAfter("dbLogCount=" + existing);
            return true;
        }

        // 数量依据：库里的明细行（**以 DB 为准**），不是 Redis 里记的数
        RequisitionItemDO dbItem = findRequisitionItem(requisition.getId(), materialId);
        if (dbItem == null || dbItem.getQuantity() == null || dbItem.getQuantity().signum() <= 0) {
            item.setAction("无（申领明细缺失或数量非正，无法确定补扣量，需人工核对）");
            item.setAfter("requisitionItem=null");
            log.error("[replenishDeduction][D1 需补扣但申领明细缺失 reqNo={} materialId={} requisitionId={}]",
                    reqNo, materialId, requisition.getId());
            return false;
        }
        BigDecimal quantity = dbItem.getQuantity();
        if (redisQty.compareTo(quantity) != 0) {
            // 不阻断修复（以库为准），但必须留痕：这种不一致本身就是需要人看的数据异常
            log.warn("[replenishDeduction][Redis 预扣量与库内明细数量不一致，以库为准 reqNo={} materialId={} "
                    + "redisQty={} dbQty={}]", reqNo, materialId, plain(redisQty), plain(quantity));
        }

        boolean ok = applyDeduction(requisition.getId(), projectId, materialId, reqNo, quantity);
        item.setFixed(ok);
        item.setAction(ok ? "补扣减（DB 条件扣减 + 写 biz_type=2 流水 + 状态 1→2）"
                : "补扣减失败（DB 条件扣减影响 0 行或状态 CAS 未命中，需人工核对）");
        MaterialStockDO after = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        item.setAfter(ok
                ? "dbDeduct=" + plain(quantity) + ", dbAvailable=" + plain(after == null ? ZERO : after.getAvailableQty())
                : "dbAvailable=" + plain(after == null ? ZERO : after.getAvailableQty()));
        return ok;
    }

    /**
     * 补扣减的**单行事务**（DB 条件扣减 + 流水 + 状态 CAS，同成同败）
     *
     * 抽成独立 public 方法并标 {@code @Transactional} 的理由与
     * {@code RequisitionDeductTransactionService} 相同：**自调用不经过代理，事务会静默失效**。
     * 因此本方法必须**从本类外部**（Spring 代理对象）调用……但 Spring 的自调用限制意味着
     * `this.applyDeduction(...)` 不走代理。这里改用 `TransactionTemplate` 之外的方式：
     * 由 {@link ReconcileDeductionTransaction}（独立 bean，注入进来）承载事务，
     * 与 W5-P2 消费链路的拆分方式保持一致。
     *
     * @param requisitionId 申领单编号
     * @param projectId     项目编号
     * @param materialId    物料编号
     * @param reqNo         申领单号
     * @param quantity      扣减量（> 0，取自库内明细）
     * @return 是否扣减成功（false = 影响 0 行或状态已变，**本次未生效**）
     */
    private boolean applyDeduction(Long requisitionId, Long projectId, Long materialId,
                                   String reqNo, BigDecimal quantity) {
        return deductionTransaction.deduct(requisitionId, projectId, materialId, reqNo, quantity);
    }

    // ---------------- D2 ----------------

    /**
     * D2 修复：DB 有扣减流水、Redis 无预扣 → **删除脏流水 + 回补 DB**
     *
     * ========== 为什么选"删流水 + 回补 DB"而不是"补 Redis 明细"（任务书要求说明） ==========
     *
     * 两种修法都能让"Redis 明细 ↔ DB 流水"重新一一对应，但后果不同：
     *
     * | | 补 Redis 明细（HDEL/HSET 反向操作） | **删流水 + 回补 DB（本次采纳）** |
     * | --- | --- | --- |
     * | 前提假设 | 假设"DB 那笔扣减是对的，Redis 明细是被误删的" | 假设"DB 那笔扣减是多余的" |
     * | 需要改谁 | 改 **缓存** | 改 **事实源（DB）**，但用**冲正流水**留痕 |
     * | 若假设错了的后果 | 我们会**保留一笔不该存在的扣减**：库存永久少一块，且账面看不出（Redis/DB 依然"一致"，只是都错） | 我们会**多回补一笔**：库存多一块（超卖方向） |
     * | 可逆性 | 不可逆（Redis 明细是内存态，事后无从判断哪笔是补的） | **可逆且有据**：冲正流水（biz_type=4）+ 原始扣减流水（biz_type=2）都在表里，人工核对后可再冲回 |
     *
     * 关键事实：**D2 的成因里"重复消费"和"人工改库"占绝大多数**——这两种情况都意味着
     * DB 那笔扣减本身是不该有的（重复消费 = 多扣了一次；人工改库 = 数据被人动过）。
     * 而"Redis 明细被误删"在本项目里几乎不可能：明细只由 Lua 脚本（预扣加、回滚删）与
     * 对账自己触碰，没有 TTL，也不存在第三方写入方。
     *
     * 所以选**删流水 + 回补**，并且：
     * 1. 删除是**逻辑删除**（`deleted = 1`），原始行仍在表里可查（不是物理抹掉证据）；
     * 2. **不写 biz_type=4 冲正流水**：删掉一笔负数流水让 SUM 增加一个 qty，DB 回补也让 available
     *    增加同一个 qty，两者已经抵消完毕；再写一笔正数冲正会让 `SUM > available` 多出一个 qty
     *    （W5-P4 实测 SUM=130 vs available=100）。完整演算见
     *    {@code ReconcileDeductionTransaction#reverseDirtyDeduction} 的注释；
     * 3. 数量取**被删流水的绝对值**（不是库内明细数量）：要还原的是"这一笔多扣的量"，
     *    而不是"这张单该扣多少"。单据的明细数量属另一件事，不该在这里被顺手对齐；
     * 4. **回补完必须把 Redis 一起对齐**（{@link #alignRedisAfterD2}）：
     *    DB 涨了、Redis 没涨 → 本维度两侧必然差出一个 `deductedQty`，
     *    而这个维度已经被排除出 D3 判定，不在这里收口就只剩"下一轮再报一条 D3"这一条路（实测会破坏幂等）。
     *
     * **若单据状态显示它确实应该扣**（已扣减/已出库/已完成/部分回退），说明这笔扣减是业务需要的，
     * 此时删流水会让单据变成"已扣减但无流水"——那又是一个不平的账。
     * 所以这一支**只处理"单据不在已扣减之后"的情形**（草稿/已提交/已取消/已关闭/单据不存在），
     * 其余情形不自动修复、只告警（那是"Redis 明细丢了但业务确实扣过"，
     * 正确动作是人工确认后重建 Redis 明细，不能由 Job 猜）。
     *
     * @param item 差异明细（会被就地更新）
     * @return 是否修复成功
     */
    private boolean fixD2(ReconcileResult.DiffItem item) {
        Long projectId = item.getProjectId();
        Long materialId = item.getMaterialId();
        String reqNo = item.getReqNo();

        StockLogDO logRow = stockLogMapper.selectLatestByBizNoAndType(projectId, materialId, reqNo,
                StockBizTypeEnum.REQUISITION_OUT.getBizType());
        if (logRow == null) {
            // 已被上一轮/并发对账处理
            item.setFixed(true);
            item.setAction("跳过（biz_type=2 流水已不存在，已对齐）");
            item.setAfter("dbLogCount=0");
            return true;
        }
        BigDecimal deductedQty = logRow.getQuantity() == null ? ZERO : logRow.getQuantity().abs();
        if (deductedQty.signum() <= 0) {
            item.setAction("无（扣减流水数量为 0，无法计算回补量，需人工核对）");
            item.setAfter("logId=" + logRow.getId() + ", quantity=" + plain(deductedQty));
            return false;
        }

        RequisitionDO requisition = requisitionMapper.selectByReqNo(reqNo);
        if (requisition != null && isDeductionConfirmedByStatus(requisition.getStatus())) {
            item.setAction("无（单据状态=" + requisition.getStatus() + " 表明扣减确属业务所需，"
                    + "Redis 明细缺失需人工重建，不自动修复）");
            item.setAfter("dbDeduct=" + plain(deductedQty));
            log.error("[fixD2][D2 差异但单据已扣减/后置状态，不自动修复 reqNo={} projectId={} materialId={} status={} qty={}]",
                    reqNo, projectId, materialId, requisition.getStatus(), deductedQty);
            return false;
        }

        boolean ok = deductionTransaction.reverseDirtyDeduction(logRow.getId(), projectId, materialId,
                reqNo, deductedQty);
        item.setFixed(ok);
        item.setAction(ok ? "删除脏扣减流水（逻辑删除）+ 回补 DB"
                : "删除脏流水/回补失败（流水已被并发处理或库存行不存在）");
        MaterialStockDO after = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        item.setAfter((ok ? "dbDeduct=0, dbReversed=" + plain(deductedQty) + ", " : "")
                + "dbAvailable=" + plain(after == null ? ZERO : after.getAvailableQty()));
        return ok && alignRedisAfterD2(item);
    }

    /**
     * D2 修复的第二段：**把 DB 回补出来的量同步到 Redis**，并（在必要时）重建在途预扣明细
     *
     * ========== 为什么这一段必须属于 D2 的修复，而不能留给下一轮的 D3 ==========
     *
     * 检测阶段把「有 D1/D2 的维度」排除出 D3 判定（见类注释），理由是"修完 D1/D2 之后可用量会自动对齐，
     * 万一没对齐下一轮会以 D3 报出来". 这条理由对 D1 成立（D1 补扣后 Redis 值不变、DB 降下来，两侧自然相等），
     * **但对 D2 不成立**：D2 的修复动作是 `available_qty += qty`（DB 涨），Redis 一侧**什么都没变**，
     * 于是修完之后两侧必然差出正好一个 `deductedQty`。留给下一轮 D3 的后果不是"晚一轮"，而是：
     *
     * 1. **幂等性破产**：同一批差异连跑两轮，第二轮必然再报一条 D3（W5-P4 实测：第二轮 D3=1）；
     * 2. **一个"修复"制造一个"新差异"**：DB 已经回补了、Redis 还停在旧值，中间这一轮里
     *    任何一次预扣都会按"偏小的 Redis 值"判断额度 → 误拒（少卖）；若方向相反则会超卖。
     *
     * 所以收口放在 D2 自己身上：**同一个差异，一轮之内修到自洽**。
     * 这也是"检测阶段排除 D3"这条规则唯一站得住的前提——
     * 排除出去的量差必须有人负责补齐，负责的人只能是把它造成的那一类修复。
     *
     * ========== 为什么用"RESET 到 DB 再按明细重建"，而不是 `INCRBY deductedQty` ==========
     *
     * 增量式（在 Redis 旧值上加 `deductedQty`）只在"Redis 旧值与 DB 之差**刚好等于** deductedQty"时正确。
     * 而 D2 的成因本身就是"两侧已经不对了"，Redis 里很可能还压着别的漂移（例如同时被人工改过）。
     * 增量会把那份漂移原样保留下来，并且**看起来修好了**（本轮不再报），是最难查的一种半修。
     * RESET 到 `DB.available_qty` 则是绝对口径：结果与本轮从哪里出发无关，天然幂等。
     *
     * **明细必须重建**：`initAvailable(RESET)` 会清空该维度的预扣明细，而本维度上
     * **其它 reqNo 的在途预扣是合法的**（它们与 DB 流水一一对应，检测期已比对过），
     * 清掉就会让那些单据在驳回时 `rollback` 返回 NOT_FOUND、预扣永远还不回去（库存永久少一块）。
     * 所以先读明细 → RESET 到 DB 值 → 逐笔 `tryPrededuct` 重建，与 {@link #fixD3} 完全同一套动作，
     * 只是期望值不同：这里**不**期望"重建后 == DB"，因为 DB 值已含实扣、在途预扣要在其上再扣一次。
     *
     * ========== 事务边界（如实说明，不假装是一个事务） ==========
     *
     * DB 侧（删流水 + 回补）在 {@code ReconcileDeductionTransaction} 的本地事务里，Redis 侧（RESET + 重建）
     * 在事务**之外**——两者不可能原子。失败方向是经过选择且是安全的那一侧：
     * - 若 DB 段失败并回滚 → Redis 根本没被动过，两侧与修复前一致，下一轮重来；
     * - 若 DB 段成功、Redis 段失败（Redis 挂了/超时）→ **DB 是对的、Redis 落后**，
     *   本方法返回 false（本条记为未修复进告警），且下一轮检测会以 **D3** 报出并由 D3 校正到 DB 值。
     *
     * 反过来若把顺序倒过来（先改 Redis 再回补 DB），失败的中间态就是"Redis 说库存回来了、DB 还没回补"——
     * 那是**超卖方向**的错。所以顺序固定为 DB 先、Redis 后，且只在 DB 成功后才动 Redis。
     *
     * @param item 差异明细（会被就地更新 after 字段）
     * @return 是否对齐成功（false = Redis 与 DB 仍有差，本轮记为未修复，由下一轮 D3 兜底）
     */
    private boolean alignRedisAfterD2(ReconcileResult.DiffItem item) {
        Long projectId = item.getProjectId();
        Long materialId = item.getMaterialId();
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        if (stock == null) {
            // 库存行在修复过程中消失：DB 侧的账已经不在了，无从判定 Redis 该是多少
            log.warn("[alignRedisAfterD2][库存行已消失，跳过 Redis 对齐 projectId={} materialId={}]",
                    projectId, materialId);
            return false;
        }
        BigDecimal dbAvailable = stock.getAvailableQty() == null ? ZERO : stock.getAvailableQty();

        // ① 先读在途明细（必须在 RESET 之前，RESET 会清空明细）
        Map<String, BigDecimal> details = stockPredeductService.queryPredeductDetails(projectId, materialId);

        // ② RESET 到 DB 基线
        stockPredeductService.initAvailable(projectId, materialId, dbAvailable,
                StockPredeductService.InitMode.RESET);

        // ③ 重建在途预扣
        int rebuilt = 0;
        BigDecimal pendingTotal = ZERO;
        for (Map.Entry<String, BigDecimal> entry : details.entrySet()) {
            BigDecimal qty = entry.getValue();
            if (qty == null || qty.signum() <= 0) {
                // 非正数量（脏明细）不参与重建，也**不**计入期望值——
                // 两处必须用同一个过滤条件，否则"期望值"会多减一个没被重建的量，把正常的修复判成失败
                continue;
            }
            pendingTotal = pendingTotal.add(qty);
            var result = stockPredeductService.tryPrededuct(projectId, materialId, entry.getKey(), qty);
            if (result == com.buildcloud.material.service.stock.redis.StockPredeductResult.SUCCESS
                    || result == com.buildcloud.material.service.stock.redis.StockPredeductResult.IDEMPOTENT) {
                rebuilt++;
            } else {
                log.error("[alignRedisAfterD2][重建在途预扣失败 reqNo={} projectId={} materialId={} qty={} result={}]",
                        entry.getKey(), projectId, materialId, qty, result);
            }
        }

        BigDecimal redisAfter = stockPredeductService.queryAvailable(projectId, materialId);
        // 期望值：RESET 到 DB 基线后，再扣掉全部**被重建**的在途预扣
        BigDecimal expected = dbAvailable.subtract(pendingTotal);
        boolean ok = redisAfter != null && redisAfter.compareTo(expected) == 0;
        item.setAfter(StrUtil.blankToDefault(item.getAfter(), "") + ", redis=" + plain(redisAfter)
                + "（D2 回补后已同步到 Redis" + (pendingTotal.signum() == 0 ? ""
                : "，重建在途明细 " + rebuilt + " 笔 / 合计 " + plain(pendingTotal)) + "）");
        if (!ok) {
            log.error("[alignRedisAfterD2][D2 修复后 Redis 对齐失败 projectId={} materialId={} redisAfter={} "
                    + "expected={} rebuilt={}/{}]", projectId, materialId, plain(redisAfter),
                    plain(expected), rebuilt, details.size());
        }
        return ok;
    }

    /**
     * 单据状态是否"确认扣减属业务所需"（D2 据此拒绝自动删除流水）
     *
     * @param status 单据状态
     * @return 已扣减(2)/已出库(3)/已完成(4)/部分回退(5) → true
     */
    private boolean isDeductionConfirmedByStatus(Integer status) {
        return RequisitionStatusEnum.DEDUCTED.getStatus().equals(status)
                || RequisitionStatusEnum.OUTBOUND.getStatus().equals(status)
                || RequisitionStatusEnum.COMPLETED.getStatus().equals(status)
                || RequisitionStatusEnum.PARTIAL_RETURNED.getStatus().equals(status);
    }

    // ---------------- D3 ----------------

    /**
     * D3 修复：以 **DB 为准**校正 Redis 可用量
     *
     * ========== 为什么以 DB 为准（任务书要求说明，完整版在接口 Javadoc） ==========
     *
     * 一句话：**DB 是事实源，Redis 是它的缓存**。
     * - DB 的每一笔变动都在本地事务里、有行锁或条件更新保护，且有流水可自证（`SUM == available`）；
     * - Redis 只有当前值，进程重启、被清、ACL 变更、入库同步失败都会让它与 DB 分叉，
     *   而且**分叉后自己无法判断哪个值是真的**；
     * - 反过来（以 Redis 为准写 DB）意味着让"可能已经漂移的缓存"去覆盖"有流水背书的账"，
     *   偏差方向还可能是**库存虚高 → 超卖**。以 DB 为准只会**少卖**（预扣更容易被拒，用户重试即可）。
     *
     * 唯一的例外已在 D1/D2 里处理完（那两类是"单据/流水"层面的差异，不是"量"的差异）；
     * 走到 D3 时，本维度上 Redis 明细与 DB 流水已经对齐（检测阶段已排除有 D1/D2 的维度），
     * 所以直接把 Redis 写成 DB 值即可，不会有"把在途预扣一起抹掉"的风险。
     *
     * ========== 为什么用 RESET 而不是 ACCUMULATE ==========
     *
     * ACCUMULATE 需要 Redis 里已有 key 且只做增量；D3 要的是"对齐到 DB 值",
     * 而增量在"Redis 偏大/偏小"两种方向下需要算出不同的 delta，多一层中间计算就多一处错。
     * RESET 直接把基线写成 DB 值，语义就是"以 DB 为准"。它同时会删掉该维度的预扣明细——
     * 在本维度**没有 D1/D2** 的前提下，明细与 DB 流水是一一对应的，删掉明细虽然会丢失
     * "在途预扣记录"，但下一个动作是"用 DB 值重建可用量"，而 DB 值里**已经反映了那些预扣吗**？
     * ——没有。见下一条。
     *
     * ========== 在校正后的基线上重建明细（保证幂等、不留"幽灵预扣"） ==========
     *
     * `DB.available_qty` 的语义是"已落库的实扣之后的可用量"，**不含**在途预扣；
     * 而 Redis 明细里的那些 reqNo 仍然代表"已提交但 DB 未扣"的单据（它们在 D1 里被排查过），
     * 也就是说这些预扣**还没落到 DB**。若只 RESET 可用量而不重建明细，就会出现
     * 「单据在途、明细丢失」→ 下一轮对账报 D2（DB 有扣减流水？还没有……）或
     * 更糟：单据被驳回时 `rollback` 返回 NOT_FOUND，**预扣永远还不回去**（库存永久少一块）。
     *
     * 所以：RESET 到 DB 值 → **按明细把在途预扣重新扣一遍**（逐笔 `tryPrededuct`），
     * 最终 `Redis.available = DB.available - Σ在途明细`，与 `StockPredeductService` 的
     * 语义定义完全一致（见该类注释的公式）。
     * 这个过程天然幂等：RESET 之后的起点是 DB 值，重建是确定性的。
     *
     * @param item 差异明细（会被就地更新）
     * @return 是否修复成功
     */
    private boolean fixD3(ReconcileResult.DiffItem item) {
        Long projectId = item.getProjectId();
        Long materialId = item.getMaterialId();
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        if (stock == null) {
            // 库存行在检测与修复之间被删掉（管理动作）：无处可校正，下一轮自然不再扫到它
            item.setAction("无（库存行已不存在）");
            item.setAfter("stock=null");
            log.warn("[fixD3][库存行已消失，跳过校正 projectId={} materialId={}]", projectId, materialId);
            return true;
        }
        BigDecimal dbAvailable = stock.getAvailableQty() == null ? ZERO : stock.getAvailableQty();
        BigDecimal redisBefore = stockPredeductService.queryAvailable(projectId, materialId);
        if (redisBefore != null && redisBefore.compareTo(dbAvailable) == 0) {
            // 已对齐（并发/上一轮修过）
            item.setFixed(true);
            item.setAction("跳过（可用量已对齐）");
            item.setAfter("redis=" + plain(redisBefore) + ", db=" + plain(dbAvailable));
            return true;
        }

        // ① 先读取在途明细（**必须在 RESET 之前**：RESET 会清空该维度明细，
        //    之后再也读不到在途预扣，重建就无从谈起——W5-P4 实测踩过的坑）
        Map<String, BigDecimal> details = stockPredeductService.queryPredeductDetails(projectId, materialId);

        // ② RESET 到 DB 基线（同时清空该维度明细，见方法注释末节）
        stockPredeductService.initAvailable(projectId, materialId, dbAvailable,
                StockPredeductService.InitMode.RESET);

        // ③ 按在途明细重建预扣（DB 值不含在途预扣，必须补回来，否则驳回时还不回去）
        int rebuilt = 0;
        for (Map.Entry<String, BigDecimal> entry : details.entrySet()) {
            BigDecimal qty = entry.getValue();
            if (qty == null || qty.signum() <= 0) {
                continue;
            }
            // 用 tryPrededuct 而不是直接 HSET：它会重新校验"够不够"，
            // 若在途合计已经超过 DB 可用量（说明账已经严重不平），预扣会失败并留下记录，
            // 下一轮会以 D3 再次报出来——比强行写进去把超卖掩盖掉安全得多。
            var result = stockPredeductService.tryPrededuct(projectId, materialId, entry.getKey(), qty);
            if (result == com.buildcloud.material.service.stock.redis.StockPredeductResult.SUCCESS
                    || result == com.buildcloud.material.service.stock.redis.StockPredeductResult.IDEMPOTENT) {
                rebuilt++;
            } else {
                log.error("[fixD3][重建在途预扣失败 reqNo={} projectId={} materialId={} qty={} result={}]",
                        entry.getKey(), projectId, materialId, qty, result);
            }
        }

        BigDecimal redisAfter = stockPredeductService.queryAvailable(projectId, materialId);
        // 成功判据：所有在途预扣都重建成功 + 重建后的可用量 == DB 可用量 - Σ在途（见类注释的公式）
        BigDecimal expected = dbAvailable;
        for (BigDecimal qty : details.values()) {
            expected = expected.subtract(qty);
        }
        boolean ok = rebuilt == details.size()
                && redisAfter != null && redisAfter.compareTo(expected) == 0;
        if (!ok) {
            // 不抛：把"修完仍不对"如实记成未修复（进告警），下一轮继续尝试并留下第二份证据
            log.error("[fixD3][校正后仍不一致 projectId={} materialId={} redisAfter={} expected={} rebuilt={}/{}]",
                    projectId, materialId, plain(redisAfter), plain(expected), rebuilt, details.size());
        }
        item.setFixed(ok);
        item.setAction("以 DB 为准校正 Redis 可用量（RESET→重建在途预扣 " + rebuilt + "/" + details.size() + " 笔）");
        item.setAfter("redis=" + plain(redisAfter) + ", db=" + plain(dbAvailable)
                + (details.isEmpty() ? "" : ", 在途明细=" + details.size() + " 笔, 期望可用量=" + plain(expected)));
        log.info("[fixD3][可用量校正 projectId={} materialId={} redisBefore={} db={} redisAfter={} rebuilt={}/{}]",
                projectId, materialId, plain(redisBefore), plain(dbAvailable), plain(redisAfter),
                rebuilt, details.size());
        return ok;
    }

    // ================================================================================
    // 四、快照落库
    // ================================================================================

    /**
     * 把本轮结果落库为快照（每类差异一行）
     *
     * **落库失败不影响对账结论**：catch 住并记 error。理由与 detail_json 截断同源——
     * 审计表是旁路，写不进去不能让"已经修好的差异"变成"对账失败然后重跑一遍"。
     *
     * @param result 对账结果
     */
    private void saveSnapshot(ReconcileResult result) {
        for (ReconcileResult.TypeResult typeResult : result.getTypeResults()) {
            try {
                ReconcileLogDO logRow = ReconcileLogDO.builder()
                        .batchNo(result.getBatchNo())
                        .checkTime(LocalDateTime.now())
                        .checkType(typeResult.getType().getType())
                        .diffCount(typeResult.getDiffCount())
                        .fixedCount(typeResult.getFixedCount())
                        .status(typeResult.getStatus().getStatus())
                        .detailJson(toDetailJson(typeResult))
                        .build();
                reconcileLogMapper.insert(logRow);
            } catch (Exception e) {
                log.error("[saveSnapshot][对账快照落库失败（不影响本轮修复结果）batchNo={} type={} diff={} fixed={}]",
                        result.getBatchNo(), typeResult.getType().getCode(),
                        typeResult.getDiffCount(), typeResult.getFixedCount(), e);
            }
        }
    }

    /**
     * 差异明细序列化为 detail_json（**主动截断**到字段容量以内）
     *
     * @param typeResult 单类统计
     * @return JSON 文本（不含明细时写 `{"items":[]}`，不是 null——查表时不会出现"是没差异还是没记"的歧义）
     */
    String toDetailJson(ReconcileResult.TypeResult typeResult) {
        String json;
        try {
            json = detailJsonMapper.writeValueAsString(typeResult.getItems());
        } catch (Exception e) {
            log.error("[toDetailJson][差异明细序列化失败，降级为条数摘要 type={}]",
                    typeResult.getType().getCode(), e);
            json = "{\"serializeError\":true,\"diffCount\":" + typeResult.getDiffCount() + "}";
        }
        if (json.length() <= DETAIL_JSON_MAX_LENGTH) {
            return json;
        }
        return StrUtil.sub(json, 0, DETAIL_JSON_MAX_LENGTH - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }

    // ================================================================================
    // helpers
    // ================================================================================

    /**
     * 查某张申领单下某物料的明细行（补扣量/对照量的唯一来源）
     *
     * @param requisitionId 申领单编号
     * @param materialId    物料编号
     * @return 明细行；不存在返回 null
     */
    private RequisitionItemDO findRequisitionItem(Long requisitionId, Long materialId) {
        List<RequisitionItemDO> items = requisitionItemMapper.selectListByRequisitionId(requisitionId);
        if (CollUtil.isEmpty(items)) {
            return null;
        }
        return items.stream().filter(i -> materialId.equals(i.getMaterialId())).findFirst().orElse(null);
    }

    /**
     * 维度键（projectId:materialId），用于"该维度是否已有 D1/D2"的去重判断
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 维度键
     */
    static String dimensionKey(Long projectId, Long materialId) {
        return projectId + ":" + materialId;
    }

    /**
     * BigDecimal 的可读输出（去尾零，避免日志里出现 "100.0000"）
     *
     * @param value 数值
     * @return 文本（null 输入返回 "null"）
     */
    static String plain(BigDecimal value) {
        if (value == null) {
            return "null";
        }
        return value.stripTrailingZeros().toPlainString();
    }

}
