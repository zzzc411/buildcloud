package com.buildcloud.material.controller.admin.category.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 管理后台 - 材料目录分页 Request VO
 *
 * 说明：目录是**树形小表**（一个租户通常几十到几百个节点），前端组树需要全量数据，
 * 故主路径是 `GET /list`（不分页，返回扁平列表供前端组树）；
 * 分页接口保留给「目录很多时按名称/编码搜索」的运维场景。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料目录分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MaterialCategoryPageReqVO extends PageParam {

    @Schema(description = "父目录编号（按层级过滤）", example = "0")
    private Long parentId;

    @Schema(description = "目录名称（模糊匹配）", example = "钢材")
    private String name;

    @Schema(description = "目录编码（模糊匹配）", example = "STEEL")
    private String code;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

}
