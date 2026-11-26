package com.buildcloud.project.controller.admin.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;

/**
 * 管理后台 - 项目新增/修改 Request VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目新增/修改 Request VO")
@Data
public class ProjectSaveReqVO {

    @Schema(description = "项目编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "项目编号（业务编码，租户内唯一）", requiredMode = Schema.RequiredMode.REQUIRED, example = "BC-2026-0001")
    @NotEmpty(message = "项目编号不能为空")
    @Size(max = 64, message = "项目编号长度不能超过 64 个字符")
    private String projectNo;

    @Schema(description = "项目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "滨江商务中心")
    @NotEmpty(message = "项目名称不能为空")
    @Size(max = 200, message = "项目名称长度不能超过 200 个字符")
    private String projectName;

    @Schema(description = "项目类型，见 ProjectTypeEnum", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "项目类型不能为空")
    private Integer projectType;

    @Schema(description = "项目状态，见 ProjectStatusEnum", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "项目状态不能为空")
    private Integer status;

    @Schema(description = "省份编码", example = "330000")
    private String provinceCode;

    @Schema(description = "省份名称", example = "浙江省")
    private String provinceName;

    @Schema(description = "城市编码", example = "330100")
    private String cityCode;

    @Schema(description = "城市名称", example = "杭州市")
    private String cityName;

    @Schema(description = "详细地址", example = "滨江区江南大道 1 号")
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

}
