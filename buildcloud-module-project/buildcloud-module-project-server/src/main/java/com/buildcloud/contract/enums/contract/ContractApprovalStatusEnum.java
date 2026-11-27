package com.buildcloud.contract.enums.contract;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 合同进度款审批状态枚举
 *
 * **刻意与基座 {@link cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum} 数值一一对齐**
 * （-1 / 1 / 2 / 3 / 4），与项目子域 {@code ProjectApprovalStatusEnum}（W3-P4）同口径，原因：
 * 1. 业务表 bc_contract.approval_status 直接落库的就是流程实例状态值，回调时无需映射换算，
 *    少一层转换就少一处「映射写错导致状态错位」的隐患；
 * 2. 排障时 {@code SELECT approval_status FROM bc_contract} 与
 *    {@code SELECT STATUS_ FROM ACT_HI_VARINST WHERE NAME_='STATUS'} 可直接对读；
 * 3. 与基座 OA/CRM 的业务表口径一致（读码笔记 §2.2「业务表 status 直接复用 BpmProcessInstanceStatusEnum」）。
 *
 * 与 {@link ContractStatusEnum}（合同业务状态机：草稿/审批中/履行中/已结算/已作废）正交：
 * **审批状态只记录流程走到哪一步，合同业务状态才决定能不能收付款、能不能改字段**。
 * 两者的联动只发生在回调处（见 {@code ContractApprovalServiceImpl#handleApprovalResult}）：
 * 通过 → 合同 审批中(1) → 履行中(2)；驳回/取消 → 合同 审批中(1) → 草稿(0)。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ContractApprovalStatusEnum implements ArrayValuable<Integer> {

    /**
     * 未开始：合同刚登记、尚未提交进度款审批
     *
     * 对应 bc_contract.approval_status 的列默认值 -1。
     */
    NOT_START(-1, "未开始"),

    /**
     * 审批中：已发起 payment-approve 流程实例，尚未出结果
     */
    RUNNING(1, "审批中"),

    /**
     * 审批通过：流程正常结束且结果为通过（此时流水已生成、合同已转履行中）
     */
    APPROVE(2, "审批通过"),

    /**
     * 审批不通过：审批人驳回（终止型驳回），合同退回草稿、不生成流水
     */
    REJECT(3, "审批不通过"),

    /**
     * 已取消：发起人撤回或管理员终止，语义同驳回（合同退回草稿）
     */
    CANCEL(4, "已取消");

    public static final Integer[] ARRAYS = Arrays.stream(values())
            .map(ContractApprovalStatusEnum::getStatus).toArray(Integer[]::new);

    /**
     * 状态值（与 BpmProcessInstanceStatusEnum 对齐）
     */
    private final Integer status;

    /**
     * 状态描述
     */
    private final String desc;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    /**
     * 按状态值查枚举
     *
     * @param status 状态值
     * @return 枚举；取值不在范围内返回 {@code null}
     */
    public static ContractApprovalStatusEnum valueOf(Integer status) {
        return ArrayUtil.firstMatch(item -> item.getStatus().equals(status), values());
    }

    /**
     * 是否为审批终态（通过 / 不通过 / 已取消）
     *
     * 用途：回调侧的**幂等终态短路**——已是终态且结果相同时直接返回，
     * 避免重复回调把「通过」再执行一次（会重复生成流水、重复累加 paid_amount）。
     *
     * @param status 状态值
     * @return 是否终态
     */
    public static boolean isEndStatus(Integer status) {
        return APPROVE.getStatus().equals(status)
                || REJECT.getStatus().equals(status)
                || CANCEL.getStatus().equals(status);
    }

}
