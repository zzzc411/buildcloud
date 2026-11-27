package com.buildcloud.material.controller.admin.supplier.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 管理后台 - 供应商分页 Request VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 供应商分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class SupplierPageReqVO extends PageParam {

    @Schema(description = "供应商编码（模糊匹配）", example = "SUP-0001")
    private String supplierNo;

    @Schema(description = "供应商名称（模糊匹配）", example = "杭州钢铁")
    private String name;

    @Schema(description = "联系人（模糊匹配）", example = "张三")
    private String contactPerson;

    @Schema(description = "联系电话（模糊匹配）", example = "138")
    private String contactPhone;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

}
