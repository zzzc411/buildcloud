package com.buildcloud.material.controller.admin.material.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 管理后台 - 物料分页 Request VO
 *
 * 检索维度对应真库索引：
 * - categoryId → idx_category_id（目录树点击后的列表主路径）；
 * - name       → idx_name（前缀不可控的模糊查询，走索引前缀）；
 * - materialNo → uk_material_no（唯一键前缀，等值即可命中）；
 * - spec       → 无索引，模糊查询仅作辅助筛选（数据量小，可接受）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 物料分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MaterialPageReqVO extends PageParam {

    @Schema(description = "物料编码（模糊匹配）", example = "MT-HRB400")
    private String materialNo;

    @Schema(description = "物料名称（模糊匹配）", example = "螺纹钢")
    private String name;

    @Schema(description = "规格型号（模糊匹配）", example = "HRB400")
    private String spec;

    @Schema(description = "所属目录编号", example = "1")
    private Long categoryId;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

}
