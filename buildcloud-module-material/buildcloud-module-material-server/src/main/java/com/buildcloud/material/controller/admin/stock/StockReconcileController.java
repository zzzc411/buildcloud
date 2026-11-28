package com.buildcloud.material.controller.admin.stock;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.buildcloud.material.controller.admin.stock.vo.StockReconcileTriggerRespVO;
import com.buildcloud.material.service.stock.reconcile.ReconcileResult;
import com.buildcloud.material.service.stock.reconcile.StockReconcileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 库存对账（手动触发入口）
 *
 * ========== 这个接口为什么必须存在（任务书要求） ==========
 *
 * 对账的**常态入口**是 XXL-Job（{@code StockReconcileJob}），而 local 环境
 * `xxl.job.enabled=false`（默认关闭），执行器根本不注册，`@XxlJob` 方法不会被任何调度器调用。
 * 没有本接口，"本地验证对账真的能跑通"这件事就只能靠单测——而单测用的是 H2 + Redis 替身，
 * 证明不了真库真 Redis 上的账平。本接口直接调用同一个 Service，是本地/预发**唯一的真链路触发方式**。
 *
 * 生产上它保留（不是临时脚手架）——理由：
 * 1. 事故时"立刻对一次账"是常规运维动作，等 5 分钟的定时窗口不合适；
 * 2. 排障时可以指定分片（`shardIndex/shardTotal`），单独重跑出问题的那个分片；
 * 3. 它是**只读之外唯一的写操作**，所以权限单独开一条 `material:stock:reconcile`，
 *    而不是复用 `material:stock:query`——查库存的人不该有"触发库存自动修复"的能力。
 *
 * ========== URL 与权限（AGENTS R-9） ==========
 *
 * 首段 = 服务标识 `buildcloud-material`，第二段 = 域 `stock`，经 adminApi 前缀暴露：
 * `POST /admin-api/buildcloud-material/stock/reconcile/trigger`。
 * 放在 {@code StockController} 之外**单独一个 Controller**：职责不同（一个读写库存业务，
 * 一个是运维动作），混在一起会让 `@PreAuthorize` 的权限边界变得含糊。
 *
 * ========== 幂等性与并发 ==========
 *
 * 本接口**不做**分布式互斥：两次并发触发会各自扫一遍并各自修复，但因为修复逻辑本身是幂等的
 * （看现状不看记忆，见 {@code StockReconcileServiceImpl} 类注释），结果是安全的——
 * 最坏情况是多写了几行 `bc_reconcile_log` 快照。为此引入一把 Redis 锁的代价
 * （锁失效时的排查成本）高于收益。若要严格单跑，运维侧用 XXL-Job 的「单机串行」阻塞策略即可。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 库存对账")
@RestController
@RequestMapping("/buildcloud-material/stock/reconcile")
@Validated
@Slf4j
public class StockReconcileController {

    @Resource
    private StockReconcileService stockReconcileService;

    /**
     * 手动触发一轮库存对账
     *
     * @param shardIndex 分片序号（默认 0：单机全量）
     * @param shardTotal 分片总数（默认 1：不分片）
     * @return 对账结果（三类差异的检出/修复统计 + 差异明细）
     */
    @PostMapping("/trigger")
    @Operation(summary = "手动触发库存对账",
            description = "检测并修复 Redis 预扣与 DB 流水的三类差异（D1/D2/D3），落 bc_reconcile_log 快照，"
                    + "差异超阈值发站内信。分片参数与 XXL-Job 分片广播口径一致：按 projectId % shardTotal 取模。")
    @Parameter(name = "shardIndex", description = "分片序号（0 起）", example = "0")
    @Parameter(name = "shardTotal", description = "分片总数（1 表示不分片）", example = "1")
    @PreAuthorize("@ss.hasPermission('material:stock:reconcile')")
    public CommonResult<StockReconcileTriggerRespVO> trigger(
            @RequestParam(value = "shardIndex", required = false, defaultValue = "0")
            @Min(value = 0, message = "分片序号不能小于 0") Integer shardIndex,
            @RequestParam(value = "shardTotal", required = false, defaultValue = "1")
            @Min(value = 1, message = "分片总数不能小于 1")
            @Max(value = 1024, message = "分片总数不能大于 1024") Integer shardTotal) {
        // 分片序号必须小于总数：写反了会导致"两个执行器扫同一片、另一些片永远没人扫"，
        // 而对账的漏扫是**静默**的（扫过的都正常，没扫的根本不出现）。宁可 400。
        if (shardIndex >= shardTotal) {
            throw new IllegalArgumentException("分片序号必须小于分片总数：shardIndex=" + shardIndex
                    + ", shardTotal=" + shardTotal);
        }
        log.info("[trigger][手动触发对账 shard={}/{}]", shardIndex, shardTotal);
        ReconcileResult result = stockReconcileService.reconcile(shardIndex, shardTotal);

        StockReconcileTriggerRespVO respVO = StockReconcileTriggerRespVO.builder()
                .batchNo(result.getBatchNo())
                .shardIndex(result.getShardIndex())
                .shardTotal(result.getShardTotal())
                .scannedRows(result.getScannedRows())
                .totalDiffCount(result.totalDiffCount())
                .totalFixedCount(result.totalFixedCount())
                .hasUnfixed(result.hasUnfixed())
                .costMillis(result.getCostMillis())
                .summary(result.summary())
                .details(result.getTypeResults().stream()
                        .map(t -> StockReconcileTriggerRespVO.TypeDetail.builder()
                                .checkType(t.getType().getType())
                                .code(t.getType().getCode())
                                .name(t.getType().getName())
                                .diffCount(t.getDiffCount())
                                .fixedCount(t.getFixedCount())
                                .status(t.getStatus().getStatus())
                                .statusName(t.getStatus().getName())
                                .items(t.getItems().stream()
                                        .map(i -> StockReconcileTriggerRespVO.DiffDetail.builder()
                                                .projectId(i.getProjectId())
                                                .materialId(i.getMaterialId())
                                                .reqNo(i.getReqNo())
                                                .description(i.getDescription())
                                                .action(i.getAction())
                                                .fixed(i.isFixed())
                                                .before(i.getBefore())
                                                .after(i.getAfter())
                                                .build())
                                        .toList())
                                .build())
                        .toList())
                .build();
        return success(respVO);
    }

}
