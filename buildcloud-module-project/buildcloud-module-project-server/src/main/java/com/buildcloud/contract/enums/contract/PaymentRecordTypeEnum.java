package com.buildcloud.contract.enums.contract;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 实际收付款类型枚举
 *
 * 对应 bc_contract_payment_record.type：1-收款 2-付款。
 * 对账时按 type 分别汇总（合同 direction 是「这份合同整体是该收还是该付」，
 * record.type 是「这笔流水实际是收还是付」，两者可分口径核对，故都对账返回）。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum PaymentRecordTypeEnum implements ArrayValuable<Integer> {

    RECEIPT(1, "收款"),
    PAYMENT(2, "付款");

    /**
     * 类型值
     */
    private final Integer type;

    /**
     * 类型名
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(PaymentRecordTypeEnum::getType).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAY;
    }

    /**
     * 校验类型值是否合法
     *
     * @param type 类型值
     * @return 是否合法
     */
    public static boolean isValid(Integer type) {
        return ArrayUtil.contains(ARRAY, type);
    }

}
