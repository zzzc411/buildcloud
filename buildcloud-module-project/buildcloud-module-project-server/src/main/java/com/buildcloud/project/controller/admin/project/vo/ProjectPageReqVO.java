package com.buildcloud.project.controller.admin.project.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 项目分页 Request VO
 *
 * 精修相对生成器产物的改动：
 * 1. 日期区间用 LocalDate[]（bc_project 的四个日期列是 date 类型，生成器给的是 LocalDate[]，
 *    这里保留，但把 @DateTimeFormat 收敛为 yyyy-MM-dd 语义要求的 FRONT 格式）；
 * 2. 去掉生成器按列铺开的 address/remark/city_name 等无索引字段，避免误用导致慢查询；
 * 3. 加 @EqualsAndHashCode(callSuper = true)：PageParam 有父类字段，避免 Lombok 告警。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProjectPageReqVO extends PageParam {

    @Schema(description = "项目编号", example = "BC-2026-0001")
    private String projectNo;

    @Schema(description = "项目名称（模糊匹配）", example = "滨江商务中心")
    private String projectName;

    @Schema(description = "项目类型，见 ProjectTypeEnum", example = "1")
    private Integer projectType;

    @Schema(description = "项目状态，见 ProjectStatusEnum", example = "1")
    private Integer status;

    @Schema(description = "省份编码", example = "330000")
    private String provinceCode;

    @Schema(description = "城市编码", example = "330100")
    private String cityCode;

    @Schema(description = "建设单位（模糊匹配）", example = "杭州城投")
    private String buildUnit;

    @Schema(description = "项目经理（system_users.id）", example = "1")
    private Long managerUserId;

    @Schema(description = "所属部门（system_dept.id）", example = "100")
    private Long deptId;

    @Schema(description = "计划开工日期区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDate[] planStartDate;

    @Schema(description = "计划竣工日期区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDate[] planEndDate;

    @Schema(description = "创建时间区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
