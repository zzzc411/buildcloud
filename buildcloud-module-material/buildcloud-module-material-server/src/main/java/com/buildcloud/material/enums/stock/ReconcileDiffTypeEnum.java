package com.buildcloud.material.enums.stock;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 库存对账差异类型枚举（W5-P4，spec v1.2 §6.1 步骤⑦）
 *
 * 三类差异的定义（**与 {@code StockReconcileService} 的判定口径一一对应**）：
 *
 * <pre>
 *   D1  Redis 有预扣、DB 无扣减流水
 *       → Redis 明细里有 reqNo，但 bc_stock_log 没有该 reqNo 的 biz_type=2 行
 *       → 含义：V2 扣减消息丢失 / 消费失败进死信 / 消费者事务回滚后没重投
 *
 *   D2  DB 有扣减、Redis 无预扣
 *       → bc_stock_log 有该 reqNo 的 biz_type=2 行，但 Redis 明细里没有该 reqNo
 *       → 含义：消费成功后 Redis 明细被回滚脚本删掉（不该发生，因为回滚只删在途）、
 *              重复消费、或人工清过 Redis 明细 / 人工改过库
 *
 *   D3  可用量不一致
 *       → Redis `pm:stock:{pid}:{mid}` != DB `bc_material_stock.available_qty`
 *       → 含义：上面两类没覆盖到的"量"上的不一致（例如入库时 Redis 同步失败、
 *              或 Redis 被清后靠入库 ACCUMULATE 重建导致偏移）
 * </pre>
 *
 * ========== 为什么 D3 必须排在 D1/D2 之后处理（本枚举的 ordinal 不是巧合） ==========
 *
 * D3 很可能是 D1/D2 的**衍生结果**：Redis 有笔预扣却没落库时，
 * `Redis.available` 天生就比 `DB.available_qty` 少那么一笔——此时若先修 D3，
 * 会把 Redis 的可用量**凭空加上去**（`initAvailable(RESET, DB值)`），
 * 然后 D1 又去回滚那笔预扣（再 INCRBY 一次）→ **同一笔量被加两次**，库存虚高。
 * 所以顺序固定为：先 D1（清掉不该存在的预扣）→ 再 D2（补回丢失的明细）→ 最后 D3（对总账）。
 * 这个顺序由 {@code StockReconcileService} 的编排保证，枚举里按此顺序声明只是为了**让顺序可见**。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ReconcileDiffTypeEnum implements ArrayValuable<Integer> {

    /**
     * D1：Redis 有预扣、DB 无扣减流水
     */
    REDIS_WITHOUT_DB_LOG(1, "D1", "Redis 有预扣但 DB 无扣减流水"),

    /**
     * D2：DB 有扣减、Redis 无预扣
     */
    DB_LOG_WITHOUT_REDIS(2, "D2", "DB 有扣减流水但 Redis 无预扣"),

    /**
     * D3：可用量不一致
     */
    AVAILABLE_MISMATCH(3, "D3", "Redis 可用量与 DB 可用量不一致");

    /**
     * 类型值（落库值，对应 bc_reconcile_log.check_type）
     */
    private final Integer type;

    /**
     * 简称（日志/告警里的机读标签，如 "D1"）
     */
    private final String code;

    /**
     * 类型名（日志/告警的人类可读说明）
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(ReconcileDiffTypeEnum::getType).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAY;
    }

    /**
     * 校验类型值是否为合法枚举值
     *
     * @param type 类型值
     * @return 是否合法
     */
    public static boolean isValid(Integer type) {
        return ArrayUtil.contains(ARRAY, type);
    }

    /**
     * 按类型值查枚举
     *
     * @param type 类型值
     * @return 枚举；取值不在范围内返回 {@code null}
     */
    public static ReconcileDiffTypeEnum valueOf(Integer type) {
        return ArrayUtil.firstMatch(item -> item.getType().equals(type), values());
    }

}
