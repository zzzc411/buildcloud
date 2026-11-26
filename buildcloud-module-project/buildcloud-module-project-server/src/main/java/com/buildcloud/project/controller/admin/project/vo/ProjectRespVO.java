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
 * 管理后台 - 项目 Response VO
 *
 * 精修相对生成器产物的改动：生成器在多语言/导出场景会给每个字段加 Excel 注解与本表全部列；
 * 这里保留 spec §5.1 的完整字段集（台账/详情共用），但去掉生成器误留的 id"（主键）"等冗长措辞，
 * 并把日期语义写准（date 列返回 yyyy-MM-dd）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目 Response VO")
@Data
public class ProjectRespVO {

    @Schema(description = "项目编号（主键）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "项目编号（业务编码）", requiredMode = Schema.RequiredMode.REQUIRED, example = "BC-2026-0001")
    private String projectNo;

    @Schema(description = "项目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "滨江商务中心")
    private String projectName;

    @Schema(description = "项目类型，见 ProjectTypeEnum", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer projectType;

    @Schema(description = "项目状态，见 ProjectStatusEnum", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer status;

    @Schema(description = "省份编码", example = "330000")
    private String provinceCode;

    @Schema(description = "省份名称", example = "浙江省")
    private String provinceName;

    @Schema(description = "城市编码", example = "330100")
    private String cityCode;

    @Schema(description = "城市名称", example = "杭州市")
    private String cityName;

    @Schema(description = "详细地址")
    private String address;

    @Schema(description = "建设单位（业主）", example = "杭州城投")
    private String buildUnit;

    @Schema(description = "合同额（元）", example = "120000000.00")
    private BigDecimal contractAmount;

    @Schema(description = "计划开工日期", example = "2026-01-01")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate planStartDate;

    @Schema(description = "计划竣工日期", example = "2028-12-31")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate planEndDate;

    @Schema(description = "实际开工日期", example = "2026-02-01")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate actualStartDate;

    @Schema(description = "实际竣工日期", example = "2029-01-31")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate actualEndDate;

    @Schema(description = "项目经理（system_users.id）", example = "1")
    private Long managerUserId;

    @Schema(description = "所属部门（system_dept.id）", example = "100")
    private Long deptId;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

}
