package com.buildcloud.material.controller.admin.stock.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 手动触发库存对账的响应 VO
 *
 * 与 {@code ReconcileResult}（服务层领域对象）分开的理由与基座惯例一致：
 * 领域对象将来会随内部重构变化（比如加字段、改结构），而接口响应是**对外契约**，
 * 两者绑死会让内部重构变成"前端/压测脚本要跟着改"。
 * 本 VO 只暴露运维真正需要的字段，并把"差异类型"这类枚举值展开成可读的名字。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 库存对账触发结果")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReconcileTriggerRespVO {

    @Schema(description = "对账批次号", requiredMode = Schema.RequiredMode.REQUIRED, example = "RCL-20261128231500-123-0")
    private String batchNo;

    @Schema(description = "分片序号", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Integer shardIndex;

    @Schema(description = "分片总数", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer shardTotal;

    @Schema(description = "扫描的库存维度数", requiredMode = Schema.RequiredMode.REQUIRED, example = "128")
    private Integer scannedRows;

    @Schema(description = "检出差异总数", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    private Integer totalDiffCount;

    @Schema(description = "修复成功总数", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    private Integer totalFixedCount;

    @Schema(description = "是否存在未修复差异", requiredMode = Schema.RequiredMode.REQUIRED, example = "false")
    private Boolean hasUnfixed;

    @Schema(description = "耗时（毫秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "235")
    private Long costMillis;

    @Schema(description = "摘要", requiredMode = Schema.RequiredMode.REQUIRED, example = "D1=1（修 1） D2=1（修 1） D3=1（修 1）")
    private String summary;

    @Schema(description = "三类差异的明细统计", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<TypeDetail> details;

    /**
     * 单类差异的统计与明细
     */
    @Schema(description = "单类差异统计")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypeDetail {

        @Schema(description = "差异类型值：1-D1 2-D2 3-D3", example = "1")
        private Integer checkType;

        @Schema(description = "差异类型简称", example = "D1")
        private String code;

        @Schema(description = "差异类型名", example = "Redis 有预扣但 DB 无扣减流水")
        private String name;

        @Schema(description = "检出条数", example = "1")
        private Integer diffCount;

        @Schema(description = "修复成功条数", example = "1")
        private Integer fixedCount;

        @Schema(description = "结论：0-无差异 1-有差异未修复 2-已全部修复", example = "2")
        private Integer status;

        @Schema(description = "结论名", example = "已全部修复")
        private String statusName;

        @Schema(description = "差异明细（含修复前后值）")
        private List<DiffDetail> items;

    }

    /**
     * 单条差异明细
     */
    @Schema(description = "单条差异明细")
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffDetail {

        @Schema(description = "项目编号", example = "1")
        private Long projectId;

        @Schema(description = "物料编号", example = "2")
        private Long materialId;

        @Schema(description = "申领单号（D3 无此值）", example = "REQ-20261128-0001")
        private String reqNo;

        @Schema(description = "差异描述")
        private String description;

        @Schema(description = "修复动作")
        private String action;

        @Schema(description = "是否修复成功", example = "true")
        private Boolean fixed;

        @Schema(description = "修复前的值")
        private String before;

        @Schema(description = "修复后的值")
        private String after;

    }

}
