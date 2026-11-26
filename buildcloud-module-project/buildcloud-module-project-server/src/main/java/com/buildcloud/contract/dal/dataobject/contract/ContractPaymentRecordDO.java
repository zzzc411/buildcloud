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
 * 合同实际收付款流水 DO
 *
 * 表 bc_contract_payment_record 对应 spec v1.2 §5.1：金额/日期/关联审批单。
 *
 * approval_no 存 BPM 流程的业务单号字符串，不是 BPM 实例外键（设计取舍见
 * docs/architecture/er-diagram-v0.md §3.1：可空性 / 稳定性 / 跨库解耦三条理由）。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）。
 *
 * @author buildcloud
 */
@TableName("bc_contract_payment_record")
@KeySequence("bc_contract_payment_record_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractPaymentRecordDO extends TenantBaseDO {

    /**
     * 收付款流水编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 所属合同（bc_contract.id）
     */
    private Long contractId;

    /**
     * 关联审批单号（BPM 流程业务单号，手工补录可为空）
     */
    private String approvalNo;

    /**
     * 实际收付款金额（元）
     */
    private BigDecimal amount;

    /**
     * 实际收付款日期
     */
    private LocalDate payDate;

    /**
     * 类型：1-收款 2-付款
     *
     * 枚举 {@link com.buildcloud.contract.enums.contract.PaymentRecordTypeEnum}
     */
    private Integer type;

    /**
     * 备注
     */
    private String remark;

}
