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
 * 合同 DO
 *
 * 表 bc_contract 对应 spec v1.2 §5.1「合同子域」实体：编号/名称/关联项目/收入或支出/金额/签订日期/状态
 * （+ 甲乙方、累计收付、备注）。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）：bc_contract 含 tenant_id 列，只有继承 TenantBaseDO
 * 基座多租户插件（starter-biz-tenant 的 TenantLineInnerInterceptor）才会在 SQL 层自动追加 tenant_id 条件；
 * 审计字段（creator/create_time/updater/update_time/deleted）由父类 BaseDO 提供，tenant_id 由 TenantBaseDO 提供。
 *
 * @author buildcloud
 */
@TableName("bc_contract")
@KeySequence("bc_contract_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT，可不生效
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDO extends TenantBaseDO {

    /**
     * 合同编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 合同编号（业务编码，租户内唯一，见唯一键 uk_contract_no）
     */
    private String contractNo;

    /**
     * 合同名称
     */
    private String contractName;

    /**
     * 关联项目（bc_project.id；补充协议等可能暂不挂项目，故可空）
     */
    private Long projectId;

    /**
     * 合同方向：1-收入（我方收款） 2-支出（我方付款）
     *
     * 枚举 {@link com.buildcloud.contract.enums.contract.ContractDirectionEnum}
     */
    private Integer direction;

    /**
     * 合同金额（元，decimal(18,2)）
     */
    private BigDecimal amount;

    /**
     * 签订日期
     */
    private LocalDate signDate;

    /**
     * 合同状态：0-草稿 1-审批中 2-履行中 3-已结算 9-已作废
     *
     * 枚举 {@link com.buildcloud.contract.enums.contract.ContractStatusEnum}（状态机迁转规则见该枚举）
     */
    private Integer status;

    /**
     * 甲方（发包方/付款方，按方向语义不同）
     */
    private String partyA;

    /**
     * 乙方（承包方/收款方，按方向语义不同）
     */
    private String partyB;

    /**
     * 累计已收/已付金额（元）
     *
     * 由实际收付款流水登记时原子累加回写（UPDATE ... SET paid_amount = paid_amount + #{amount}），
     * 与 payment_record 之和在正常流程下应一致，台账对账以此校验（spec §5.1）。
     */
    private BigDecimal paidAmount;

    /**
     * 备注
     */
    private String remark;

    // ========== 进度款审批关联字段（W3-P5，见 sql/buildcloud/06-contract-approval.sql） ==========

    /**
     * 进度款审批流程实例编号（ACT_HI_PROCINST.PROC_INST_ID_）
     *
     * 发起审批成功时回写；驳回/取消后保留（审批历史可追溯），重新发起时覆盖为最新实例。
     * 回调侧的「串号校验」以此为准：回调带的实例号与它不一致 → 旧流程的迟到通知，丢弃。
     */
    private String processInstanceId;

    /**
     * 进度款审批状态：-1-未开始 1-审批中 2-通过 3-不通过 4-已取消
     *
     * 枚举 {@link com.buildcloud.contract.enums.contract.ContractApprovalStatusEnum}
     * （数值与基座 BpmProcessInstanceStatusEnum 一一对齐）。
     * 注意与 {@link #status}（合同业务状态机）正交：审批状态只记录流程走到哪一步。
     */
    private Integer approvalStatus;

    /**
     * 在途进度款申请金额（元）
     *
     * 提交审批时写入；审批通过后用于生成 bc_contract_payment_record，随后清空；
     * 驳回时直接清空（不生成流水）。为 null 表示当前无在途申请。
     */
    private BigDecimal pendingPaymentAmount;

    /**
     * 在途进度款申请期次（第几期，与 bc_contract_payment_plan.period 同口径）
     *
     * 与 {@link #pendingPaymentAmount} 同生命周期。
     */
    private Integer pendingPeriod;

}
