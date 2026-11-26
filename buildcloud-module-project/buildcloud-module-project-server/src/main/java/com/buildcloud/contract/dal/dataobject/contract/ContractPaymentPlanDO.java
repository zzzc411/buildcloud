package com.buildcloud.contract.dal.dataobject.contract;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 合同收付款计划 DO
 *
 * 表 bc_contract_payment_plan 对应 spec v1.2 §5.1：期次/计划金额/计划日期/状态。
 * 唯一键 uk_contract_period(tenant_id, contract_id, period)：同一合同内期次唯一，避免重复编制同一期计划。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）。
 *
 * @author buildcloud
 */
@TableName("bc_contract_payment_plan")
@KeySequence("bc_contract_payment_plan_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractPaymentPlanDO extends TenantBaseDO {

    /**
     * 计划编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 所属合同（bc_contract.id）
     */
    private Long contractId;

    /**
     * 期次（第几期，从 1 起；同合同内唯一）
     */
    private Integer period;

    /**
     * 计划收付款金额（元）
     */
    private BigDecimal planAmount;

    /**
     * 计划收付款日期
     */
    private LocalDate planDate;

    /**
     * 计划状态：0-未到期 1-待到账 2-已收付 3-已逾期
     *
     * 枚举 {@link com.buildcloud.contract.enums.contract.PaymentPlanStatusEnum}
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;

}
