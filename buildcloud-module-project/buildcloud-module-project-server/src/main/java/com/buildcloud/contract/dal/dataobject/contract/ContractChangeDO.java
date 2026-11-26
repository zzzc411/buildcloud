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

/**
 * 合同变更签证 DO
 *
 * 表 bc_contract_change 对应 spec v1.2 §5.1：类型/增减金额/原因/关联审批。
 *
 * change_amount 为**带符号金额**：正数=增项（追加金额），负数=减项（核减金额），
 * 使「合同变更后金额 = amount + SUM(change_amount)」一处口径可算，无需按类型判断加减
 * （设计取舍见 docs/architecture/er-diagram-v0.md §3.2）。业务层拒绝 0 值
 * （错误码 CONTRACT_CHANGE_AMOUNT_ZERO）。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）。
 *
 * @author buildcloud
 */
@TableName("bc_contract_change")
@KeySequence("bc_contract_change_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractChangeDO extends TenantBaseDO {

    /**
     * 变更编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 所属合同（bc_contract.id）
     */
    private Long contractId;

    /**
     * 变更类型：1-设计变更 2-现场签证 3-索赔 9-其他
     *
     * 枚举 {@link com.buildcloud.contract.enums.contract.ContractChangeTypeEnum}
     */
    private Integer type;

    /**
     * 增减金额（元）：正数=增项，负数=减项，不可为 0
     */
    private BigDecimal changeAmount;

    /**
     * 变更原因
     */
    private String changeReason;

    /**
     * 关联审批单号（BPM 流程业务单号，可空）
     */
    private String approvalNo;

    /**
     * 备注
     */
    private String remark;

}
