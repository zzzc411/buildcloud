package com.buildcloud.contract.enums.contract;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 合同状态枚举
 *
 * 状态机（spec v1.2 §5.1）：草稿 → 审批中 → 履行中 → 已结算，旁路 已作废。
 * 合法流转如下（含旁路），流转校验集中在 {@link ContractStatusEnum#canTransfer} 与
 * {@code ContractServiceImpl#updateContractStatus}，禁止在 Controller 内散落判断：
 *
 * <pre>
 * 草稿(0)   → 审批中(1) → 履行中(2) → 已结算(3)
 *   │            │            │            │
 *   └────────────┴────────────┴────────────┴──→ 已作废(9)
 * </pre>
 *
 * 说明：
 * 1. 已结算(3) 与 已作废(9) 为终态，不可再流转（作废后只能重建合同，保证台账口径不含废单）；
 * 2. 已作废(9) 以外都可以作废——审批中/履行中的合同因故终止走作废而不是删除（删除仅限草稿）。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ContractStatusEnum implements ArrayValuable<Integer> {

    DRAFT(0, "草稿"),
    APPROVING(1, "审批中"),
    PERFORMING(2, "履行中"),
    SETTLED(3, "已结算"),
    VOIDED(9, "已作废");

    /**
     * 状态值
     */
    private final Integer status;

    /**
     * 状态名
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(ContractStatusEnum::getStatus).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAY;
    }

    /**
     * 判断两个状态值是否为同一个状态
     *
     * @param status 状态值
     * @return 是否相等
     */
    public boolean match(Integer status) {
        return this.status.equals(status);
    }

    /**
     * 校验状态值是否为合法枚举值
     *
     * @param status 状态值
     * @return 是否合法
     */
    public static boolean isValid(Integer status) {
        return ArrayUtil.contains(ARRAY, status);
    }

    /**
     * 判定状态流转是否合法（状态机唯一实现，Service 层直接调用，避免规则散落）
     *
     * 合法边：
     * 草稿 → 审批中 / 已作废；
     * 审批中 → 履行中 / 已作废（驳回退回草稿由审批回调处理，本期未接入 BPM，故不设 审批中 → 草稿）；
     * 履行中 → 已结算 / 已作废；
     * 已结算、已作废为终态，无出边。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 是否允许
     */
    public static boolean canTransfer(Integer from, Integer to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        if (DRAFT.status.equals(from)) {
            return APPROVING.status.equals(to) || VOIDED.status.equals(to);
        }
        if (APPROVING.status.equals(from)) {
            return PERFORMING.status.equals(to) || VOIDED.status.equals(to);
        }
        if (PERFORMING.status.equals(from)) {
            return SETTLED.status.equals(to) || VOIDED.status.equals(to);
        }
        // 已结算 / 已作废：终态
        return false;
    }

    /**
     * 判断是否可登记收付款（只有履行中的合同才允许发生实际收付流水，spec §5.1）
     *
     * @param status 合同状态值
     * @return 是否可登记
     */
    public static boolean canPay(Integer status) {
        return PERFORMING.status.equals(status);
    }

}
