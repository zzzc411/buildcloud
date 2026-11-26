package com.buildcloud.contract.enums.contract;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 合同方向枚举
 *
 * 对应 bc_contract.direction（建表 SQL 注释：1-收入（我方收款） 2-支出（我方付款））。
 * 台账对账与报表汇总必须按 direction 分口径，故独立成枚举，禁止魔法数字。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ContractDirectionEnum implements ArrayValuable<Integer> {

    INCOME(1, "收入"),
    EXPENSE(2, "支出");

    /**
     * 方向值
     */
    private final Integer direction;

    /**
     * 方向名
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(ContractDirectionEnum::getDirection).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAY;
    }

    /**
     * 校验方向值是否合法
     *
     * @param direction 方向值
     * @return 是否合法
     */
    public static boolean isValid(Integer direction) {
        return ArrayUtil.contains(ARRAY, direction);
    }

}
