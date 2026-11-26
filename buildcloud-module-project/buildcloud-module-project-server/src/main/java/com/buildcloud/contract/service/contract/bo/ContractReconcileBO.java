package com.buildcloud.contract.service.contract.bo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 合同台账对账 BO（spec v1.2 §5.1「台账对账：计划 vs 实际差异」）
 *
 * 由 {@link com.buildcloud.contract.dal.mysql.contract.ContractMapper#selectReconcile} 一条聚合 SQL 填充，
 * 再由 {@code ContractServiceImpl#getReconcile} 补算三个派生字段，接口一次调用返回全部口径：
 *
 * | 口径 | 字段 | 来源 |
 * | --- | --- | --- |
 * | 合同金额 | {@link #contractAmount} | bc_contract.amount |
 * | 计划合计 | {@link #planAmountTotal} | SUM(bc_contract_payment_plan.plan_amount) |
 * | 实际合计 | {@link #actualAmountTotal} | 收付两口径之和 = receiptAmountTotal + paymentAmountTotal |
 * | 变更净额 | {@link #changeAmountTotal} | SUM(bc_contract_change.change_amount)（带符号） |
 * | 变更后金额 | {@link #changedContractAmount} | contractAmount + changeAmountTotal |
 * | 差异 1 | {@link #planActualDiff} | planAmountTotal - actualAmountTotal |
 * | 差异 2 | {@link #contractChangedDiff} | changedContractAmount - contractAmount（= changeAmountTotal，单列便于报表展示） |
 * | 双写一致性 | {@link #paidAmountDiff} | paidAmount - actualAmountTotal（冗余累计收付与流水之和的差，应为 0） |
 *
 * @author buildcloud
 */
@Data
public class ContractReconcileBO {

    /**
     * 合同编号（主键）
     */
    private Long contractId;

    /**
     * 合同业务编号
     */
    private String contractNo;

    /**
     * 合同名称
     */
    private String contractName;

    /**
     * 合同方向：1-收入 2-支出
     */
    private Integer direction;

    /**
     * 合同状态
     */
    private Integer status;

    // ========== 原始聚合值（SQL 直接返回） ==========

    /**
     * 合同金额（元）
     */
    private BigDecimal contractAmount;

    /**
     * 合同表冗余的累计已收/已付金额（元）
     */
    private BigDecimal paidAmount;

    /**
     * 收付款计划总额（元）
     */
    private BigDecimal planAmountTotal;

    /**
     * 计划期数
     */
    private Integer planCount;

    /**
     * 实际收款合计（type=1，元）
     */
    private BigDecimal receiptAmountTotal;

    /**
     * 实际付款合计（type=2，元）
     */
    private BigDecimal paymentAmountTotal;

    /**
     * 实际收付款流水笔数
     */
    private Integer recordCount;

    /**
     * 变更净额（元，带符号：正数增项、负数减项）
     */
    private BigDecimal changeAmountTotal;

    /**
     * 变更记录笔数
     */
    private Integer changeCount;

    // ========== 派生字段（Service 层计算） ==========

    /**
     * 实际收付合计（元）= 收款 + 付款
     */
    private BigDecimal actualAmountTotal;

    /**
     * 变更后合同金额（元）= 合同金额 + 变更净额
     */
    private BigDecimal changedContractAmount;

    /**
     * 差异 1：计划 vs 实际（元）= 计划合计 - 实际合计
     *
     * 正数表示"计划尚未收付完"，负数表示"实际已超额收付"，0 表示计划已完全执行。
     */
    private BigDecimal planActualDiff;

    /**
     * 差异 2：合同 vs 变更后（元）= 变更后金额 - 合同金额（即变更净额，单列便于报表直接展示）
     */
    private BigDecimal contractChangedDiff;

    /**
     * 双写一致性差异（元）= 合同冗余 paid_amount - 实际流水合计
     *
     * 正常流程（每笔流水都原子回写 paid_amount）应为 0；非 0 说明存在绕过回写的数据（历史导入/手工改库），
     * W5 对账 Job 以此为告警口径（ER 图 §3.8 待复核点 2）。
     */
    private BigDecimal paidAmountDiff;

}
