package com.buildcloud.material.controller.admin.material.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台 - 物料 Response VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 物料 Response VO")
@Data
public class MaterialRespVO {

    @Schema(description = "物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "物料编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "MT-HRB400-20")
    private String materialNo;

    @Schema(description = "物料名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "螺纹钢")
    private String name;

    @Schema(description = "规格型号", example = "HRB400 Φ20")
    private String spec;

    @Schema(description = "计量单位", requiredMode = Schema.RequiredMode.REQUIRED, example = "吨")
    private String unit;

    @Schema(description = "所属目录编号", example = "1")
    private Long categoryId;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
