package com.buildcloud.project.controller.admin.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理后台 - 项目状态变更 Request VO
 *
 * 只传目标状态：源状态由 Service 从库里读（不信任前端传来的源状态，
 * 否则并发下会出现「两个请求各自基于旧状态判定合法」的双写），
 * 合法流转由 {@link com.buildcloud.project.service.project.ProjectStatusService} 集中校验。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目状态变更 Request VO")
@Data
public class ProjectUpdateStatusReqVO {

    @Schema(description = "项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "项目编号不能为空")
    private Long id;

    @Schema(description = "目标状态：0-筹备 1-在建 2-停工 3-竣工", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "目标状态不能为空")
    private Integer status;

}
