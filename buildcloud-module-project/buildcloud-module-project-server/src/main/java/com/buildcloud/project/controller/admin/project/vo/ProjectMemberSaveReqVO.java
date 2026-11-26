package com.buildcloud.project.controller.admin.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 项目成员新增/修改 Request VO
 *
 * 唯一性（uk_project_user：tenant_id + project_id + user_id）在 Service 层前置校验，
 * 以便返回业务错误码 {@code PROJECT_MEMBER_USER_DUPLICATE} 而不是数据库异常。
 * 角色变更走 update（同一用户同一项目不新增行）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目成员新增/修改 Request VO")
@Data
public class ProjectMemberSaveReqVO {

    @Schema(description = "成员编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "所属项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "所属项目不能为空")
    private Long projectId;

    @Schema(description = "用户编号（system_users.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "用户不能为空")
    private Long userId;

    @Schema(description = "项目内角色：1-项目经理 2-技术负责人 3-施工员 4-安全员 5-材料员 9-其他",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    @NotNull(message = "项目内角色不能为空")
    private Integer projectRole;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
