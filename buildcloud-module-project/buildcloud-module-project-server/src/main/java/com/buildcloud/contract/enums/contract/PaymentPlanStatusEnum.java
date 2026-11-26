package com.buildcloud.contract.enums.contract;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 收付款计划状态枚举
 *
 * 对应 bc_contract_payment_plan.status：0-未到期 1-待到账 2-已收付 3-已逾期。
 * 状态推进由「到期日 + 实际收付核销」驱动，本期先由业务手工维护（status 随计划保存写入），
 * 逾期扫描 Job 归 W5 对账 Job（spec §5.1 台账对账）。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum PaymentPlanStatusEnum implements ArrayValuable<Integer> {

    NOT_DUE(0, "未到期"),
    TO_BE_PAID(1, "待到账"),
    PAID(2, "已收付"),
    OVERDUE(3, "已逾期");

    /**
     * 状态值
     */
    private final Integer status;

    /**
     * 状态名
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(PaymentPlanStatusEnum::getStatus).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAY;
    }

    /**
     * 校验状态值是否合法
     *
     * @param status 状态值
     * @return 是否合法
     */
    public static boolean isValid(Integer status) {
        return ArrayUtil.contains(ARRAY, status);
    }

}
