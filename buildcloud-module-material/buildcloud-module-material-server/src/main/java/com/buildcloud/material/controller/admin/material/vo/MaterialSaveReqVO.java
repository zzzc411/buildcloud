package com.buildcloud.material.controller.admin.material.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 物料新增/修改 Request VO
 *
 * 校验分两层：
 * 1. Bean Validation（本类注解）：必填/长度/编码字符集——能在进 Service 前拦截的错误就不查库；
 * 2. Service 业务校验：material_no 唯一、所属目录存在（需查库，见 MaterialServiceImpl）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 物料新增/修改 Request VO")
@Data
public class MaterialSaveReqVO {

    @Schema(description = "物料编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "物料编码（租户内唯一）", requiredMode = Schema.RequiredMode.REQUIRED, example = "MT-HRB400-20")
    @NotEmpty(message = "物料编码不能为空")
    @Size(max = 64, message = "物料编码长度不能超过 64 个字符")
    // 编码会出现在申领单/流水单号里，限制字符集避免空格、斜杠等在多系统传递时产生歧义
    @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "物料编码只能包含字母、数字、点、下划线和中划线")
    private String materialNo;

    @Schema(description = "物料名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "螺纹钢")
    @NotEmpty(message = "物料名称不能为空")
    @Size(max = 200, message = "物料名称长度不能超过 200 个字符")
    private String name;

    @Schema(description = "规格型号（如 HRB400 Φ20）", example = "HRB400 Φ20")
    @Size(max = 200, message = "规格型号长度不能超过 200 个字符")
    private String spec;

    @Schema(description = "计量单位（个/吨/米/立方…）", requiredMode = Schema.RequiredMode.REQUIRED, example = "吨")
    @NotEmpty(message = "计量单位不能为空")
    @Size(max = 32, message = "计量单位长度不能超过 32 个字符")
    private String unit;

    @Schema(description = "所属目录（bc_material_category.id，可为空表示未归类）", example = "1")
    private Long categoryId;

    @Schema(description = "状态：0-开启 1-停用（停用后不可申领）", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
