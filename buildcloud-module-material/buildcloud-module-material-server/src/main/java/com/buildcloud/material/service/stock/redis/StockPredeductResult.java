package com.buildcloud.material.service.stock.redis;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Lua 预扣脚本的**返回码**（与 {@code scripts/lua/stock-prededuct.lua} 一一对应）
 *
 * ========== 为什么用数字码传回、再在 Java 侧映射成枚举 ==========
 *
 * 1. **Lua → Redis → Redisson 的类型转换只对整数最稳**：脚本 `return 1` 回来是整型，
 *    `return 'SUCCESS'` 回来是字节串（需要 StringCodec 才能读，容易被 json 编解码搞乱）；
 *    `return true` 在 RESP2 下是 1、RESP3 下是布尔，客户端版本差异会直接改变语义。
 *    用整数码是唯一不踩编解码坑的选择；
 * 2. **错误信息不能进码**：脚本里用 {@code redis.error_reply} 返回的会变成异常，
 *    不该用异常表达"库存不足"这种正常业务结果（会污染日志、触发无谓告警）。
 *
 * ========== 码值约定（改值必须同步改脚本，并在同一提交里改单测） ==========
 *
 * <pre>
 *   1  SUCCESS     预扣成功
 *   0  NOT_ENOUGH  库存不足
 *  -1  NEED_INIT   可用库存 key 不存在，需初始化
 *   2  IDEMPOTENT  同一 reqNo 重复提交，幂等成功（未重复扣减）
 * </pre>
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum StockPredeductResult {

    /**
     * 预扣成功：available 已扣减、明细已写入
     */
    SUCCESS(1L, "预扣成功"),

    /**
     * 库存不足：available 未变、明细未写（spec §6.1「失败 → 友好降级，不打 DB」）
     */
    NOT_ENOUGH(0L, "库存不足"),

    /**
     * 可用库存未初始化（key 不存在或值非法）
     *
     * 独立成码而不是并入 NOT_ENOUGH：前者是需要立刻修复的缓存事故，后者是正常业务结果，
     * 告警与运维口径完全不同（见 Lua 脚本头注释）。
     */
    NEED_INIT(-1L, "可用库存未初始化"),

    /**
     * 同一 reqNo 已预扣过：本次没有重复扣减（幂等成功）
     */
    IDEMPOTENT(2L, "重复提交（幂等）");

    /**
     * 脚本返回的原始整数码
     */
    private final Long code;

    /**
     * 中文说明（日志用）
     */
    private final String desc;

    /**
     * 按脚本返回码查枚举
     *
     * @param code 脚本返回的整数（可能为 null：脚本被 Redis 返回了 null）
     * @return 匹配的枚举；**无法识别**的码返回 {@code null}（调用方按故障处理，不能当成成功）
     */
    public static StockPredeductResult parse(Long code) {
        if (code == null) {
            return null;
        }
        for (StockPredeductResult result : values()) {
            if (result.code.equals(code)) {
                return result;
            }
        }
        return null;
    }

}
