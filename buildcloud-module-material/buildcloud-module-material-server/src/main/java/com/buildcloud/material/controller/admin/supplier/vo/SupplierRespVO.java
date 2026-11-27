package com.buildcloud.material.controller.admin.supplier.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 供应商 Response VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 供应商 Response VO")
@Data
public class SupplierRespVO {

    @Schema(description = "供应商编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "供应商编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "SUP-0001")
    private String supplierNo;

    @Schema(description = "供应商名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "杭州钢铁贸易有限公司")
    private String name;

    @Schema(description = "联系人", example = "张三")
    private String contactPerson;

    @Schema(description = "联系电话", example = "13800138000")
    private String contactPhone;

    @Schema(description = "联系地址")
    private String address;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
