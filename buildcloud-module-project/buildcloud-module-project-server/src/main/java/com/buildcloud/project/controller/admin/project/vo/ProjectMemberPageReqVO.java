package com.buildcloud.project.controller.admin.project.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 管理后台 - 项目成员分页 Request VO
 *
 * 成员列表的主路径是「按项目查成员」（idx_project_role），
 * 副路径「按用户查其参与的项目」走 /list-by-user 接口（成员白名单数据权限用）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目成员分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProjectMemberPageReqVO extends PageParam {

    @Schema(description = "所属项目编号", example = "1")
    private Long projectId;

    @Schema(description = "用户编号", example = "1")
    private Long userId;

    @Schema(description = "项目内角色：1-项目经理 2-技术负责人 3-施工员 4-安全员 5-材料员 9-其他", example = "1")
    private Integer projectRole;

}
