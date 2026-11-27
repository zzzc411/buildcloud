package com.buildcloud.material.enums.requisition;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 申领单审批状态枚举
 *
 * **刻意与基座 `BpmProcessInstanceStatusEnum` 数值一一对齐**（-1 / 1 / 2 / 3 / 4），
 * 与 project 域的 `ProjectApprovalStatusEnum`、contract 域的 `ContractApprovalStatusEnum` 同口径。理由：
 * 1. `bc_requisition.approval_status` 直接落库的就是流程实例状态值，审批回调无需映射换算，
 *    少一层转换就少一处「映射写错导致状态错位」的隐患；
 * 2. 排障时 `SELECT approval_status FROM bc_requisition` 与
 *    `SELECT STATUS_ FROM ACT_HI_VARINST WHERE NAME_='STATUS'` 可直接对读；
 * 3. 与基座 OA/CRM 业务表口径一致（读码笔记 §2.2）。
 *
 * 与 {@link RequisitionStatusEnum}（业务状态机）**正交**：
 * - 业务状态（0-9）决定「库存动没动、还能不能取消」；
 * - 审批状态（-1/1/2/3/4）只记录「审批流走到哪一步」。
 *
 * 本 Phase（W4-P4）只写 {@link #NOT_START}（-1）——V1 是同步扣减，不走审批；
 * P5 接入 requisition-approve 后，提交时改为 {@link #RUNNING}，回调时置 2/3/4。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum RequisitionApprovalStatusEnum implements ArrayValuable<Integer> {

    /**
     * 未开始：单据尚未发起审批（V1 同步链路的常态；DDL 列默认值 -1）
     */
    NOT_START(-1, "未开始"),

    /**
     * 审批中：已发起 requisition-approve 流程实例
     */
    RUNNING(1, "审批中"),

    /**
     * 审批通过：流程正常结束且结果为通过
     */
    APPROVE(2, "审批通过"),

    /**
     * 审批不通过：审批人驳回（业务侧应转「已取消」并回补库存）
     */
    REJECT(3, "审批不通过"),

    /**
     * 已取消：发起人撤回或管理员终止
     */
    CANCEL(4, "已取消");

    /**
     * 状态值（与 BpmProcessInstanceStatusEnum 对齐）
     */
    private final Integer status;

    /**
     * 状态描述
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(RequisitionApprovalStatusEnum::getStatus).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAY;
    }

    /**
     * 按状态值查枚举
     *
     * @param status 状态值
     * @return 枚举；取值不在范围内返回 {@code null}
     */
    public static RequisitionApprovalStatusEnum valueOf(Integer status) {
        return ArrayUtil.firstMatch(item -> item.getStatus().equals(status), values());
    }

    /**
     * 是否为审批终态（通过 / 不通过 / 已取消）
     *
     * 用途：P5 审批回调的**幂等终态短路**——已是终态且结果相同时直接返回，
     * 避免重复回调把「通过」再执行一次（会重复出库、重复写流水）。
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
