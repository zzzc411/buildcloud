package com.buildcloud.project.controller.admin.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 项目里程碑 Response VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目里程碑 Response VO")
@Data
public class ProjectMilestoneRespVO {

    @Schema(description = "里程碑编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "所属项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long projectId;

    @Schema(description = "里程碑名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "主体结构封顶")
    private String milestoneName;

    @Schema(description = "计划完成日期", example = "2026-06-30")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate planDate;

    @Schema(description = "实际完成日期", example = "2026-07-05")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate actualDate;

    @Schema(description = "权重（百分比）", example = "25.00")
    private BigDecimal weight;

    @Schema(description = "里程碑状态：0-未开始 1-进行中 2-已完成 3-已延期", example = "2")
    private Integer status;

    @Schema(description = "排序号", example = "1")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

}
