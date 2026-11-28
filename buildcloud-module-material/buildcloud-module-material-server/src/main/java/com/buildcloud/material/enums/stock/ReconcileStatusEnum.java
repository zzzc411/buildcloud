package com.buildcloud.material.enums.stock;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 库存对账结论枚举（bc_reconcile_log.status）
 *
 * 取值由 {@code diffCount} 与 {@code fixedCount} **推导**得出（见 {@link #of(int, int)}），
 * 不手写赋值——同一事实两处各写一遍是最容易分叉的地方（lessons L3-12 的同类教训）。
 *
 * ========== 为什么"无差异"与"已全部修复"要分开 ==========
 *
 * 两者的 {@code diffCount} 都是 0 的下一轮结果，但审计含义完全不同：
 * - {@link #CLEAN}：这一轮**什么都没发现**（健康状态的常态，噪声为零）；
 * - {@link #ALL_FIXED}：这一轮**发现了并修好了 N 条**（发生了自愈，值得在告警通道留一条痕迹）。
 * 合成一个值后，"最近一周自愈了几次"就查不出来了，而这正是对账 Job 价值的直接证据。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ReconcileStatusEnum implements ArrayValuable<Integer> {

    /**
     * 无差异（健康状态）
     */
    CLEAN(0, "无差异"),

    /**
     * 有差异且存在未修复项（需要人介入）
     */
    UNFIXED(1, "有差异未修复"),

    /**
     * 有差异但已全部修复（自愈成功）
     */
    ALL_FIXED(2, "已全部修复");

    /**
     * 状态值（落库值）
     */
    private final Integer status;

    /**
     * 状态名
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(ReconcileStatusEnum::getStatus).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAY;
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
     * 由检出数与修复数推导结论（**唯一推导入口**，禁止在调用方各写一份三元表达式）
     *
     * @param diffCount  本轮检出差异条数
     * @param fixedCount 本轮修复成功条数
     * @return 对账结论
     */
    public static ReconcileStatusEnum of(int diffCount, int fixedCount) {
        if (diffCount <= 0) {
            return CLEAN;
        }
        return fixedCount >= diffCount ? ALL_FIXED : UNFIXED;
    }

}
