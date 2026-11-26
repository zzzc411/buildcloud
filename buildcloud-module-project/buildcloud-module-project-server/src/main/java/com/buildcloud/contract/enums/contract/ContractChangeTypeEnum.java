package com.buildcloud.contract.enums.contract;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 合同变更类型枚举
 *
 * 对应 bc_contract_change.type：1-设计变更 2-现场签证 3-索赔 9-其他。
 *
 * 注意：增减方向**不**由本枚举表达——`change_amount` 用带符号金额（正数增项、负数减项），
 * 见 ER 图设计取舍 §3.2；本枚举只表达「变更的业务性质」，两者正交。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ContractChangeTypeEnum implements ArrayValuable<Integer> {

    DESIGN_CHANGE(1, "设计变更"),
    SITE_VISA(2, "现场签证"),
    CLAIM(3, "索赔"),
    OTHER(9, "其他");

    /**
     * 类型值
     */
    private final Integer type;

    /**
     * 类型名
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(ContractChangeTypeEnum::getType).toArray(Integer[]::new);

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
