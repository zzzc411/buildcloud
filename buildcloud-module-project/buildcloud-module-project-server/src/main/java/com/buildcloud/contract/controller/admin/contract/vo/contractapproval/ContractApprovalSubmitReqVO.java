package com.buildcloud.contract.controller.admin.contract.vo.contractapproval;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理后台 - 进度款审批提交 Request VO
 *
 * 对应 {@code POST /buildcloud-project/contract/approval/submit}。
 *
 * 三个字段就是让「审批」与「资金流水」对得上的全部信息：
 * 合同（谁付）、金额（付多少）、期次（第几期，便于与 bc_contract_payment_plan 对照）。
 * 提交后金额与期次会暂存在合同的 pending_payment_amount / pending_period 上——
 * 因为基座的审批回调事件不带流程变量（详见 sql/buildcloud/06-contract-approval.sql 文件头）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 进度款审批提交 Request VO")
@Data
public class ContractApprovalSubmitReqVO {

    @Schema(description = "合同编号（bc_contract.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "合同编号不能为空")
    private Long contractId;

    @Schema(description = "期次（第几期进度款，从 1 起）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "期次不能为空")
    @Min(value = 1, message = "期次必须从 1 起")
    private Integer period;

    @Schema(description = "本次申请金额（元）", requiredMode = Schema.RequiredMode.REQUIRED, example = "3000000.00")
    @NotNull(message = "申请金额不能为空")
    @DecimalMin(value = "0.01", message = "申请金额必须大于 0")
    private BigDecimal amount;

}
