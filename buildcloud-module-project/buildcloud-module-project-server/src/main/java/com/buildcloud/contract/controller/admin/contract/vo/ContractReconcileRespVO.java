package com.buildcloud.contract.controller.admin.contract.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理后台 - 合同台账对账 Response VO（spec v1.2 §5.1「台账对账：计划 vs 实际差异」）
 *
 * 返回结构（七个口径 + 四个计数，一次请求给全，避免前端多次拼）：
 *
 * <pre>
 * {
 *   "contractId": 1, "contractNo": "HT-...", "direction": 1, "status": 2,
 *   "contractAmount":       120000000.00,  // 合同金额
 *   "planAmountTotal":       80000000.00,  // 计划合计
 *   "planCount": 2,
 *   "receiptAmountTotal":    30000000.00,  // 实际收款合计（type=1）
 *   "paymentAmountTotal":     8000000.00,  // 实际付款合计（type=2）
 *   "actualAmountTotal":     38000000.00,  // 实际合计 = 收 + 付
 *   "recordCount": 3,
 *   "changeAmountTotal":      3000000.00,  // 变更净额（带符号）
 *   "changeCount": 1,
 *   "changedContractAmount":123000000.00,  // 变更后金额 = 合同金额 + 变更净额
 *   "planActualDiff":        42000000.00,  // 差异1：计划 - 实际
 *   "contractChangedDiff":    3000000.00,  // 差异2：变更后 - 合同
 *   "paidAmount":            38000000.00,  // 合同冗余累计收付
 *   "paidAmountDiff":             0.00     // 双写一致性：paidAmount - actualAmountTotal
 * }
 * </pre>
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同台账对账 Response VO")
@Data
public class ContractReconcileRespVO {

    @Schema(description = "合同编号（主键）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long contractId;

    @Schema(description = "合同编号（业务编码）", example = "HT-2026-0001")
    private String contractNo;

    @Schema(description = "合同名称", example = "滨江商务中心总包合同")
    private String contractName;

    @Schema(description = "合同方向：1-收入 2-支出", example = "1")
    private Integer direction;

    @Schema(description = "合同状态", example = "2")
    private Integer status;

    // ========== 金额口径 ==========

    @Schema(description = "合同金额（元）", example = "120000000.00")
    private BigDecimal contractAmount;

    @Schema(description = "收付款计划合计（元）", example = "80000000.00")
    private BigDecimal planAmountTotal;

    @Schema(description = "实际收款合计（元，type=1）", example = "30000000.00")
    private BigDecimal receiptAmountTotal;

    @Schema(description = "实际付款合计（元，type=2）", example = "8000000.00")
    private BigDecimal paymentAmountTotal;

    @Schema(description = "实际收付合计（元）= 收款 + 付款", example = "38000000.00")
    private BigDecimal actualAmountTotal;

    @Schema(description = "变更净额（元，带符号：正数增项、负数减项）", example = "3000000.00")
    private BigDecimal changeAmountTotal;

    @Schema(description = "变更后合同金额（元）= 合同金额 + 变更净额", example = "123000000.00")
    private BigDecimal changedContractAmount;

    @Schema(description = "合同表冗余的累计已收/已付金额（元）", example = "38000000.00")
    private BigDecimal paidAmount;

    // ========== 差异口径 ==========

    @Schema(description = "差异 1：计划 vs 实际（元）= 计划合计 - 实际合计；正数=尚未收付完", example = "42000000.00")
    private BigDecimal planActualDiff;

    @Schema(description = "差异 2：合同 vs 变更后（元）= 变更后金额 - 合同金额", example = "3000000.00")
    private BigDecimal contractChangedDiff;

    @Schema(description = "双写一致性差异（元）= 累计收付 - 实际流水合计，正常应为 0", example = "0.00")
    private BigDecimal paidAmountDiff;

    // ========== 计数 ==========

    @Schema(description = "计划期数", example = "2")
    private Integer planCount;

    @Schema(description = "实际收付款流水笔数", example = "3")
    private Integer recordCount;

    @Schema(description = "变更记录笔数", example = "1")
    private Integer changeCount;

}
