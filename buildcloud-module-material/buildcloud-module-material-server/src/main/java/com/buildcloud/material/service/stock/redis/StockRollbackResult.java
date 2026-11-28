package com.buildcloud.material.service.stock.redis;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 回滚脚本的**返回码**（与 {@code scripts/lua/stock-prededuct-rollback.lua} 一一对应）
 *
 * 码值定义在脚本头注释里，这里只做映射；语义要点：
 * - {@link #ROLLED_BACK}：确实还原了一笔预扣（INCRBY + HDEL 完成）；
 * - {@link #NOT_FOUND}：没有这笔预扣 → **无副作用**（驳回/关单与对账 Job 并发时的正常结果，
 *   不是错误，调用方必须按"已回滚/无需回滚"继续往下走）；
 * - {@link #NEED_INIT} / {@link #QTY_MISMATCH}：账目对不上，脚本**拒绝改动**。
 *   这两种情况必须暴露给调用方（抛错或告警），绝不能当作"回滚成功"静默吞掉 ——
 *   否则库存少了/多了都没人知道，只能等对账 Job 事后发现。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum StockRollbackResult {

    /**
     * 已还原：available 加回、明细删除
     */
    ROLLED_BACK(1L, "已回滚"),

    /**
     * 无该 reqNo 的预扣明细：没有发生任何写操作
     */
    NOT_FOUND(0L, "无预扣记录，未做任何改动"),

    /**
     * 明细存在但可用库存 key 丢了：本次不还原（基线不可信），交由对账 Job 重建后处理
     */
    NEED_INIT(-1L, "可用库存 key 不存在，无法安全还原"),

    /**
     * 传入数量与明细记录不一致：拒绝还原（防止把错误的量加回去）
     */
    QTY_MISMATCH(-2L, "回滚数量与预扣明细不一致");

    /**
     * 脚本返回的原始整数码
     */
    private final Long code;

    private final String desc;

    /**
     * 按脚本返回码查枚举
     *
     * @param code 脚本返回的整数
     * @return 匹配的枚举；无法识别返回 {@code null}
     */
    public static StockRollbackResult parse(Long code) {
        if (code == null) {
            return null;
        }
        for (StockRollbackResult result : values()) {
            if (result.code.equals(code)) {
                return result;
            }
        }
        return null;
    }

}
