package com.buildcloud.material.controller.admin.stock.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 项目库存 Response VO
 *
 * 暴露 version 是刻意的：前端/联调方在「先读后写」的交互（如 W5 的手工调拨工具）里需要它做
 * 乐观锁重试；纯展示场景可以忽略该字段。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目库存 Response VO")
@Data
public class StockRespVO {

    @Schema(description = "库存编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "所属项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long projectId;

    @Schema(description = "物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long materialId;

    @Schema(description = "可用库存数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "100.0000")
    private BigDecimal availableQty;

    @Schema(description = "锁定数量（已提交待审批的预扣量）", requiredMode = Schema.RequiredMode.REQUIRED, example = "0.0000")
    private BigDecimal lockedQty;

    @Schema(description = "乐观锁版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer version;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

    @Schema(description = "更新时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime updateTime;

}
