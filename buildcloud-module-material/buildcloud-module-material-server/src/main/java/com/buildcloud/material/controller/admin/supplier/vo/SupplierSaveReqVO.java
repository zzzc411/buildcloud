package com.buildcloud.material.controller.admin.supplier.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 供应商新增/修改 Request VO
 *
 * 校验分两层：
 * 1. Bean Validation（本类注解）：必填/长度/编码字符集/手机号——能进 Service 前拦截的错误就不查库；
 * 2. Service 业务校验：supplier_no 唯一（需查库，见 SupplierServiceImpl）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 供应商新增/修改 Request VO")
@Data
public class SupplierSaveReqVO {

    @Schema(description = "供应商编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "供应商编码（租户内唯一）", requiredMode = Schema.RequiredMode.REQUIRED, example = "SUP-0001")
    @NotEmpty(message = "供应商编码不能为空")
    @Size(max = 64, message = "供应商编码长度不能超过 64 个字符")
    @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "供应商编码只能包含字母、数字、点、下划线和中划线")
    private String supplierNo;

    @Schema(description = "供应商名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "杭州钢铁贸易有限公司")
    @NotEmpty(message = "供应商名称不能为空")
    @Size(max = 200, message = "供应商名称长度不能超过 200 个字符")
    private String name;

    @Schema(description = "联系人", example = "张三")
    @Size(max = 64, message = "联系人长度不能超过 64 个字符")
    private String contactPerson;

    @Schema(description = "联系电话", example = "13800138000")
    @Size(max = 32, message = "联系电话长度不能超过 32 个字符")
    // 联系电话允许座机（带区号中划线）与手机，故只做「数字 + 常见分隔符」约束，
    // 不用基座 @Mobile（会拒绝 0571-88888888 这类固话）
    @Pattern(regexp = "^[0-9+\\-() ]{5,32}$", message = "联系电话格式不正确")
    private String contactPhone;

    @Schema(description = "联系地址", example = "浙江省杭州市拱墅区xx路 1 号")
    @Size(max = 500, message = "联系地址长度不能超过 500 个字符")
    private String address;

    @Schema(description = "状态：0-开启 1-停用", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
