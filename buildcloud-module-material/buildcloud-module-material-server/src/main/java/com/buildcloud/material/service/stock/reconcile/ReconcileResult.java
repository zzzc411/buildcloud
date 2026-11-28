package com.buildcloud.material.service.stock.reconcile;

import com.buildcloud.material.enums.stock.ReconcileDiffTypeEnum;
import com.buildcloud.material.enums.stock.ReconcileStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮库存对账的结果（按差异类型聚合）
 *
 * ========== 为什么按类型分开统计，而不是给一个总数 ==========
 *
 * 三类的失败原因、修复动作、需要谁介入完全不同：
 * - D1 未修复 = 单据在途但 DB 扣不动（Redis 与 DB 的量已经漂移）→ 找 DBA/开发；
 * - D2 未修复 = Redis 明细补不回去 → 找 Redis 运维（内存/配置）；
 * - D3 未修复 = 总账校正失败 → 通常是 D1/D2 没修完的连带结果。
 * 只报总数会让值班的人从"27"这个数字里看不出该找谁。
 *
 * ========== 与 bc_reconcile_log 的对应 ==========
 *
 * 每个 {@link TypeResult} 恰好落一行快照（batch_no + check_type 唯一确定一行），
 * 因此 {@link TypeResult#getStatus()} 用 {@link ReconcileStatusEnum#of(int, int)} 推导，
 * 落库时直接取值，不在 Mapper 侧再算一次。
 *
 * @author buildcloud
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconcileResult {

    /**
     * 对账批次号
     */
    private String batchNo;

    /**
     * 分片序号（0 表示不分片/单机执行；>0 为 XXL-Job 分片广播的 index）
     */
    private int shardIndex;

    /**
     * 分片总数（1 表示不分片）
     */
    private int shardTotal;

    /**
     * 扫描的库存维度数（bc_material_stock 行数，仅本分片）
     */
    private int scannedRows;

    /**
     * 三类差异的统计（顺序固定 D1 → D2 → D3，与修复顺序一致；无差异时也包含三项，count=0）
     */
    @Builder.Default
    private List<TypeResult> typeResults = new ArrayList<>();

    /**
     * 本轮耗时（毫秒）
     */
    private long costMillis;

    /**
     * 本轮检出差异总数（= 三类 diffCount 之和）
     *
     * @return 总数
     */
    public int totalDiffCount() {
        return typeResults.stream().mapToInt(TypeResult::getDiffCount).sum();
    }

    /**
     * 本轮修复成功总数（= 三类 fixedCount 之和）
     *
     * @return 总数
     */
    public int totalFixedCount() {
        return typeResults.stream().mapToInt(TypeResult::getFixedCount).sum();
    }

    /**
     * 是否存在未修复项（**告警判定与 Job 返回码都看它**）
     *
     * @return 任一类型 fixedCount < diffCount 即为 true
     */
    public boolean hasUnfixed() {
        return typeResults.stream().anyMatch(r -> r.getFixedCount() < r.getDiffCount());
    }

    /**
     * 取某一类型的统计（单测与调用方按类型断言用）
     *
     * @param type 差异类型
     * @return 统计项；不存在返回 null（正常路径不会发生：三类总是全给）
     */
    public TypeResult typeResult(ReconcileDiffTypeEnum type) {
        return typeResults.stream()
                .filter(r -> r.getType() == type)
                .findFirst()
                .orElse(null);
    }

    /**
     * 人类可读摘要（告警文案与日志用）
     *
     * @return 形如 "D1=2（修 2） D2=0（修 0） D3=1（修 0）"
     */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (TypeResult r : typeResults) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(r.getType().getCode()).append('=').append(r.getDiffCount())
                    .append("（修 ").append(r.getFixedCount()).append(')');
        }
        return sb.toString();
    }

    /**
     * 单类差异的统计与明细
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypeResult {

        /**
         * 差异类型
         */
        private ReconcileDiffTypeEnum type;

        /**
         * 检出条数
         */
        private int diffCount;

        /**
         * 修复成功条数
         */
        private int fixedCount;

        /**
         * 逐条差异明细（供 detail_json 落库与人工排查）
         */
        @Builder.Default
        private List<DiffItem> items = new ArrayList<>();

        /**
         * 对账结论（由 diff/fixed 推导，见 {@link ReconcileStatusEnum#of(int, int)}）
         *
         * @return 结论
         */
        public ReconcileStatusEnum getStatus() {
            return ReconcileStatusEnum.of(diffCount, fixedCount);
        }

    }

    /**
     * 单条差异明细（**含修复前后值**，任务书要求"每条修复记日志含修复前后值"）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffItem {

        /**
         * 项目编号
         */
        private Long projectId;

        /**
         * 物料编号
         */
        private Long materialId;

        /**
         * 申领单号（D3 为 null：量级差异不挂具体单据）
         */
        private String reqNo;

        /**
         * 差异描述（人类可读，写进 detail_json）
         */
        private String description;

        /**
         * 修复动作（如 "补扣减" / "回滚预扣" / "补明细" / "校正可用量"；未修复为 "无"）
         */
        private String action;

        /**
         * 是否修复成功
         */
        private boolean fixed;

        /**
         * 修复前的值（如 "redis=70,db=100" 或 "qty=30"）
         */
        private String before;

        /**
         * 修复后的值
         */
        private String after;

    }

}
