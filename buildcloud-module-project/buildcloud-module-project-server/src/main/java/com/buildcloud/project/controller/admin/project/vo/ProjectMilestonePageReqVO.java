package com.buildcloud.project.controller.admin.project.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;

/**
 * 管理后台 - 项目里程碑分页 Request VO
 *
 * 里程碑查询的**主路径是按项目**（idx_project_plan_date），故 projectId 逻辑上必填；
 * 这里不加 @NotNull，而是由 Service 侧对「全量跨项目分页」保持沉默支持（运维排查用），
 * 前端页面一律传 projectId。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目里程碑分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProjectMilestonePageReqVO extends PageParam {

    @Schema(description = "所属项目编号", example = "1")
    private Long projectId;

    @Schema(description = "里程碑名称（模糊匹配）", example = "封顶")
    private String milestoneName;

    @Schema(description = "里程碑状态：0-未开始 1-进行中 2-已完成 3-已延期", example = "2")
    private Integer status;

    @Schema(description = "计划完成日期区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate[] planDate;

}
