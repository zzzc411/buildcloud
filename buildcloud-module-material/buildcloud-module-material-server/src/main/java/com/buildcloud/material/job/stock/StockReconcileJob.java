package com.buildcloud.material.job.stock;

import com.buildcloud.material.service.stock.reconcile.ReconcileResult;
import com.buildcloud.material.service.stock.reconcile.StockReconcileService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 库存对账 Job（spec v1.2 §6.1 步骤⑦「XXL-Job 定时比对 Redis 预扣 vs DB 流水」）
 *
 * ========== 写法依据（照基座既有 Job，不臆造 API） ==========
 *
 * 照 `yudao-module-system` 的 {@code TokenCleanJob} / {@code DemoJob}：
 * - `@Component` + `@XxlJob("执行器里配置的 JobHandler 名称")`；
 * - 用 {@link XxlJobHelper} 上报结果（`handleSuccess` / `handleFail`），这样调度中心能看到明文结论，
 *   而不是只看到"成功/失败"和一段堆栈；
 * - **不自己 try-catch 成"成功"**：异常让 XXL-Job 记失败并触发其告警配置。
 *
 * ========== 分片广播（任务书要求：按 projectId 哈希，多执行器并行） ==========
 *
 * {@link XxlJobHelper#getShardIndex()} / {@link XxlJobHelper#getShardTotal()} 是 XXL-Job 的标准入口：
 * - 路由策略选「分片广播」时，调度中心一次触发会打到**所有**执行器实例，每个实例拿到不同的
 *   index，且 shardTotal = 实例数；
 * - 路由策略选「第一个/轮询」时，shardTotal = 1、shardIndex = 0 → 退化为单机全量。
 *
 * **哈希口径**：`Math.floorMod(projectId, shardTotal)`（在 Service 内实现，见
 * `StockReconcileServiceImpl#loadShardStocks`）。选 projectId 而不是 materialId 的理由：
 * projectId 是 `bc_stock_log` 的**分片键**（spec §6.3，W6 按它哈希 4 库 8 表），
 * 对账分片与存储分片用同一个键，将来 W6 接入后"每个执行器只处理自己那几个分片库"成为可能；
 * 而 materialId 的分布与实际库存行分布无关（同一物料可能跨几十个项目）。
 *
 * ========== 本地环境怎么验证（xxl.job.enabled=false 的绕过方式，如实说明） ==========
 *
 * `application-local.yaml` 里 `xxl.job.enabled=false`（**默认就是 false**），含义是
 * 「不注册执行器」——`YudaoXxlJobAutoConfiguration` 用
 * `@ConditionalOnProperty(prefix = "xxl.job", name = "enabled", havingValue = "true")` 守卫，
 * 关闭时连 `XxlJobExecutor` Bean 都不创建，**本类的 `@XxlJob` 方法不会被任何调度器调用**，
 * 同时 `XxlJobHelper` 的返回值也没有接收方（写日志而已）。所以本地"跑一次对账"不能靠它。
 *
 * 绕过方式（三选一，本 Phase 采用第 1 条）：
 * 1. **手动触发接口**（推荐，已交付）：`POST /admin-api/buildcloud-material/stock/reconcile/trigger`
 *    （admin 权限 `material:stock:reconcile`），直接调同一个 Service，
 *    参数 `shardIndex` / `shardTotal` 可模拟任意分片——本地联调与压测前的"账平校验"都用它；
 * 2. 临时开执行器：启动参数加 `--xxl.job.enabled=true`
 *    并确保 `xxl.job.admin.addresses`（local 默认 `http://127.0.0.1:9090/xxl-job-admin`）可达，
 *    再去调度中心新建 JobHandler=`stockReconcileJob`、路由策略=分片广播、Cron 自定义；
 *    **注意**：本机 xxl-job-admin 容器若未启动，开 enabled=true 会让**启动阶段**就去注册，
 *    连不上时执行器只是重试（不阻塞启动），但日志会持续报注册失败——排查时要先分清这是环境噪声；
 * 3. 单测：`StockReconcileServiceTest`（H2 + Redis 替身，验证三类差异与幂等）与
 *    `StockReconcileIntegrationTest`（**真 Redis**，验证修复后 `Redis == DB == SUM(stock_log)`）。
 *
 * ========== 生产怎么配（交给运维的清单） ==========
 *
 * 1. 调度中心 → 执行器管理 → 新建执行器：AppName = 服务的 spring.application.name
 *    （即 buildcloud-material-server）；
 * 2. 任务管理 → 新建：JobHandler = stockReconcileJob、Cron 建议每 5 分钟一次
 *    （调度中心里填表达式用分钟粒度，例如 0 0/5 * * * ？ 的六段式写法，具体以调度中心校验为准）、
 *    路由策略 = **分片广播**、阻塞处理策略 = **单机串行**（对账是重活，宁可排队也不要堆叠）、
 *    任务超时 = 600 秒、失败重试 = 0（下一轮会自然重来，且重试会放大告警）；
 * 3. 执行器参数（可选）：留空即使用「分片广播」的分片号；填 {@code 0 1} 可强制单机全量（应急排查用，
 *    注：本 Phase 的实现只读分片号，任务参数仅用于日志留痕）；
 * 4. 环境变量/配置：buildcloud.material.reconcile.alert-user-ids（站内信接收人）、
 *    alert-threshold（默认 0）、fix-enabled（默认 true）。
 *
 * @author buildcloud
 */
@Component
@Slf4j
public class StockReconcileJob {

    /**
     * 暴露给测试/运维的 handler 名（与调度中心里配置的 JobHandler 必须一致）
     */
    public static final String JOB_HANDLER = "stockReconcileJob";

    @Resource
    private StockReconcileService stockReconcileService;

    /**
     * 执行一次库存对账（检测 → 修复 → 落快照 → 超阈值告警）
     */
    @XxlJob(JOB_HANDLER)
    public void execute() {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        log.info("[execute][对账 Job 开始 shard={}/{} jobParam={}]",
                shardIndex, shardTotal, XxlJobHelper.getJobParam());

        // **不 catch**：异常交给 XXL-Job 记失败并按其告警规则通知。
        // 但要先把"哪个分片失败"写进上下文日志——否则调度中心只看到一条堆栈，无从定位。
        ReconcileResult result = stockReconcileService.reconcile(shardIndex, shardTotal);

        String message = String.format("对账完成 shard=%d/%d 批次=%s 扫描=%d %s 检出=%d 修复=%d 耗时=%dms",
                result.getShardIndex(), result.getShardTotal(), result.getBatchNo(), result.getScannedRows(),
                result.summary(), result.totalDiffCount(), result.totalFixedCount(), result.getCostMillis());
        if (result.hasUnfixed()) {
            // 有未修复项 → 让调度中心把这轮记为失败（人在调度中心能看到红色 + 明细在 bc_reconcile_log）
            XxlJobHelper.handleFail(message + "（存在未修复差异，详见 bc_reconcile_log）");
            return;
        }
        XxlJobHelper.handleSuccess(message);
    }

}
