package com.buildcloud.contract.controller.admin.contract.vo.contractapproval;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理后台 - 进度款审批状态 Response VO
 *
 * 对应 {@code GET /buildcloud-project/contract/approval/status}。
 * 前端用它决定按钮可用性（与项目立项审批的 status 接口同构）：
 * - {@code approvalStatus = -1}（未开始）或终态 → 显示「提交进度款审批」；
 * - {@code approvalStatus = 1}（审批中）→ 显示「审批中」+ 在途金额与期次；
 * - {@code approvalStatus = 3}（不通过）→ 显示「重新提交」。
 *
 * 同时返回**合同业务状态**与**累计收付**，让前端一次调用就能刷新台账行，
 * 不必再打一次合同详情接口。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 进度款审批状态 Response VO")
@Data
public class ContractApprovalStatusRespVO {

    @Schema(description = "合同编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long contractId;

    @Schema(description = "合同业务编号", example = "HT-2026-0001")
    private String contractNo;

    @Schema(description = "流程实例编号（未发起过审批时为空）", example = "payment-approve:1:8f1d2c3b-....")
    private String processInstanceId;

    @Schema(description = "审批状态：-1-未开始 1-审批中 2-审批通过 3-审批不通过 4-已取消，见 ContractApprovalStatusEnum",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer approvalStatus;

    @Schema(description = "审批状态名（如「审批中」）", example = "审批中")
    private String approvalStatusName;

    @Schema(description = "合同业务状态：0-草稿 1-审批中 2-履行中 3-已结算 9-已作废，见 ContractStatusEnum", example = "0")
    private Integer contractStatus;

    @Schema(description = "合同业务状态名（如「履行中」）", example = "草稿")
    private String contractStatusName;

    @Schema(description = "合同金额（元）", example = "10000000.00")
    private BigDecimal contractAmount;

    @Schema(description = "累计已收/已付金额（元）", example = "0.00")
    private BigDecimal paidAmount;

    @Schema(description = "在途申请金额（元，仅审批中时有值）", example = "3000000.00")
    private BigDecimal pendingPaymentAmount;

    @Schema(description = "在途申请期次（仅审批中时有值）", example = "1")
    private Integer pendingPeriod;

    @Schema(description = "面向操作者的补充说明", example = "已提交审批，等待财务审批")
    private String reason;

}
