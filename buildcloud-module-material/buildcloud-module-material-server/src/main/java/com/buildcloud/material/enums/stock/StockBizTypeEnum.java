package com.buildcloud.material.enums.stock;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 库存流水的业务类型枚举
 *
 * 对应 bc_stock_log.biz_type（spec v1.2 §5.2：`biz_type` 入库/申领/退回/冲正 + `biz_no` + 数量± + 变动后余额）。
 *
 * 与 quantity 符号的约定（DDL 注释同口径）：
 * - 1 入库 / 3 退回 / 4 冲正 → quantity 记**正数**（库存净增）；
 * - 2 申领扣减 → quantity 记**负数**（库存净减）。
 * 这样「对账一行聚合 SUM(quantity)」即得净变动，不需要 CASE WHEN 按 biz_type 分类求和（spec §6.1 第 ⑦ 步）。
 *
 * 本 Phase（W4-P3）只产生 1-入库；2/3/4 由 P4（申领扣减）、P5（退料/驳回冲正）写入，
 * 枚举在这里一次定义完整，避免后续各 Phase 各写一份魔法数字。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum StockBizTypeEnum implements ArrayValuable<Integer> {

    INBOUND(1, "入库"),
    REQUISITION_OUT(2, "申领扣减"),
    RETURN(3, "退回"),
    REVERSAL(4, "冲正");

    /**
     * 业务类型值（落库值）
     */
    private final Integer bizType;

    /**
     * 业务类型名（前端展示）
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(StockBizTypeEnum::getBizType).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAY;
    }

    /**
     * 校验业务类型值是否合法
     *
     * @param bizType 业务类型值
     * @return 是否合法
     */
    public static boolean isValid(Integer bizType) {
        return ArrayUtil.contains(ARRAY, bizType);
    }

}
