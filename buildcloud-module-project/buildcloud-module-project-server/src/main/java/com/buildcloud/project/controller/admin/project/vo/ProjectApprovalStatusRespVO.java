package com.buildcloud.project.controller.admin.project.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理后台 - 项目立项审批状态 Response VO
 *
 * 对应 {@code GET /buildcloud-project/project/approval/status}。
 * 前端用它决定审批按钮的可用性：
 * - {@code approvalStatus = -1}（未开始）或终态 → 显示「提交审批」；
 * - {@code approvalStatus = 1}（审批中）→ 显示「审批中」+ 当前待办节点；
 * - {@code approvalStatus = 3}（不通过）→ 显示「重新提交」。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 项目立项审批状态 Response VO")
@Data
public class ProjectApprovalStatusRespVO {

    @Schema(description = "项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long projectId;

    @Schema(description = "流程实例编号（未发起过审批时为空）", example = "project-approve:1:8f1d2c3b-....")
    private String processInstanceId;

    @Schema(description = "审批状态：-1-未开始 1-审批中 2-审批通过 3-审批不通过 4-已取消，见 ProjectApprovalStatusEnum",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer approvalStatus;

    @Schema(description = "审批状态名（如「审批中」）", example = "审批中")
    private String approvalStatusName;

    @Schema(description = "项目业务状态：0-筹备 1-在建 2-停工 3-竣工，见 ProjectStatusEnum", example = "0")
    private Integer projectStatus;

    @Schema(description = "项目业务状态名（如「筹备」）", example = "筹备")
    private String projectStatusName;

    @Schema(description = "审批结果的补充说明：通过/驳回原因，或「已提交审批，等待审批人处理」", example = "同意立项")
    private String reason;

}
