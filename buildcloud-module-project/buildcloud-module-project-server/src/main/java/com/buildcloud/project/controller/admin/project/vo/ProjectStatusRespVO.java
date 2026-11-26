package com.buildcloud.project.controller.admin.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理后台 - 项目状态（枚举选项）Response VO
 *
 * 用于「可流转目标状态」下拉：与后端状态机校验同一张表（{@code ProjectStatusService}），
 * 避免前端自己硬编码状态图导致按钮与后端拒绝不一致。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目状态 Response VO")
@Data
public class ProjectStatusRespVO {

    @Schema(description = "状态值：0-筹备 1-在建 2-停工 3-竣工", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer status;

    @Schema(description = "状态名", requiredMode = Schema.RequiredMode.REQUIRED, example = "在建")
    private String name;

}
