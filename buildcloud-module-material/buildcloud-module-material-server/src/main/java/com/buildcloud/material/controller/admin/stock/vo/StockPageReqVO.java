package com.buildcloud.material.controller.admin.stock.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 管理后台 - 项目库存分页 Request VO
 *
 * 检索维度对齐 bc_material_stock 的索引：
 * - projectId → uk_project_material(tenant_id, project_id, material_id) 的最左前缀；
 * - materialId → idx_material_id（反向查「某物料在各项目的库存分布」）。
 * 两者都可单传：只传 projectId = 项目库存台账；只传 materialId = 跨项目库存分布。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目库存分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class StockPageReqVO extends PageParam {

    @Schema(description = "所属项目编号", example = "1")
    private Long projectId;

    @Schema(description = "物料编号", example = "1")
    private Long materialId;

}
