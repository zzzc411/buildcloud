package com.buildcloud.project.controller.admin.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 项目成员 Response VO
 *
 * 只回 userId + projectRole，不回用户姓名/手机号：跨服务取 system 用户信息属 RPC 翻译，
 * 留到成员管理页面接入时统一做（避免 project 服务为一次列表调用强依赖 system 聚合接口）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目成员 Response VO")
@Data
public class ProjectMemberRespVO {

    @Schema(description = "成员编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "所属项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long projectId;

    @Schema(description = "用户编号（system_users.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long userId;

    @Schema(description = "项目内角色：1-项目经理 2-技术负责人 3-施工员 4-安全员 5-材料员 9-其他", example = "3")
    private Integer projectRole;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

}
