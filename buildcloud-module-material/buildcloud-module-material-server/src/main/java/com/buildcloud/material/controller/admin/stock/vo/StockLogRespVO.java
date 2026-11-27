package com.buildcloud.material.controller.admin.stock.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 库存流水 Response VO
 *
 * quantity 带符号返回（入库为正、扣减为负），前端不做正负翻转，直接展示即可；
 * balanceAfter 是「这笔流水提交后的余额」快照，逐笔相减可与 quantity 互相校验。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 库存流水 Response VO")
@Data
public class StockLogRespVO {

    @Schema(description = "流水编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "所属项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long projectId;

    @Schema(description = "物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long materialId;

    @Schema(description = "业务类型：1-入库 2-申领扣减 3-退回 4-冲正", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer bizType;

    @Schema(description = "业务单号（入库单号/申领单号）", example = "IN-20261127-0001")
    private String bizNo;

    @Schema(description = "变动数量（正负号表示增减）", requiredMode = Schema.RequiredMode.REQUIRED, example = "100.0000")
    private BigDecimal quantity;

    @Schema(description = "变动后余额", requiredMode = Schema.RequiredMode.REQUIRED, example = "100.0000")
    private BigDecimal balanceAfter;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间（同时是流水时间序）", requiredMode = Schema.RequiredMode.REQUIRED)
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

}
