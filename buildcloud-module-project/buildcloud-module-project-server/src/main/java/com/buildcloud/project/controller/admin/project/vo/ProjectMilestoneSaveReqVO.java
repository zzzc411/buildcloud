package com.buildcloud.project.controller.admin.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;

/**
 * 管理后台 - 项目里程碑新增/修改 Request VO
 *
 * 校验分两层：
 * 1. Bean Validation（本类注解）：字段必填/长度/权重区间——能在进 Service 前拦截的错就不查库；
 * 2. Service 业务校验：项目是否存在、同项目权重合计是否超 100（需要查库，见 ProjectMilestoneServiceImpl）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目里程碑新增/修改 Request VO")
@Data
public class ProjectMilestoneSaveReqVO {

    @Schema(description = "里程碑编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "所属项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "所属项目不能为空")
    private Long projectId;

    @Schema(description = "里程碑名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "主体结构封顶")
    @NotEmpty(message = "里程碑名称不能为空")
    @Size(max = 200, message = "里程碑名称长度不能超过 200 个字符")
    private String milestoneName;

    @Schema(description = "计划完成日期", example = "2026-06-30")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate planDate;

    @Schema(description = "实际完成日期", example = "2026-07-05")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate actualDate;

    @Schema(description = "权重（百分比 0.00~100.00）", requiredMode = Schema.RequiredMode.REQUIRED, example = "25.00")
    @NotNull(message = "权重不能为空")
    @DecimalMin(value = "0", message = "权重不能小于 0")
    @DecimalMax(value = "100", message = "权重不能大于 100")
    private BigDecimal weight;

    @Schema(description = "里程碑状态：0-未开始 1-进行中 2-已完成 3-已延期", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "里程碑状态不能为空")
    private Integer status;

    @Schema(description = "排序号（同项目内升序展示）", example = "1")
    private Integer sort;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
