package com.buildcloud.material.enums.requisition;

import cn.hutool.core.util.ArrayUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 申领链路模式枚举（spec v1.2 §6.1「V1/V2 用配置开关切换（同一接口两实现），保证压测可比」）
 *
 * 两个模式的本质差异：
 * <pre>
 * V1（W4 交付，压测对照组）
 *   提交 → 同一本地事务内 SELECT ... FOR UPDATE 锁库存行 → 校验并扣减 → 写单据与流水
 *   特点：正确但串行，热点库存行成为瓶颈，吞吐随并发骤降
 *
 * V2（W5 交付，目标架构）
 *   提交 → Sentinel 限流 → 布隆/两级缓存 → Redis Lua 原子预扣 → RocketMQ 事务消息
 *        → 消费者按 version 乐观锁落库 + 流水 + 状态推进
 *   特点：预扣在内存里完成，DB 只承接最终一致性写入
 * </pre>
 *
 * 为什么开关值用字符串而不是数字：压测脚本/JMeter/运维都按环境变量或 Nacos 配置覆盖，
 * `V1`/`V2` 自解释，比 `1`/`2` 更难写错；解析失败时给出可读错误码（见
 * {@code REQUISITION_MODE_NOT_IMPLEMENTED}），而不是静默落到某个默认值。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum RequisitionModeEnum {

    /**
     * V1：行锁同步扣减（W4-P4 落地，spec §6.1 明确「是压测对照组，不是要保留的方案」）
     */
    V1("V1", "行锁同步扣减（对照组）"),

    /**
     * V2：Redis 预扣 + MQ 异步落库（W5 落地；当前仅占位，选到即抛未实现）
     */
    V2("V2", "Redis 预扣 + MQ 异步");

    /**
     * 配置值（同时是枚举名，便于 Spring 直接按名字绑定）
     */
    private final String mode;

    /**
     * 模式说明（日志与报错文案用）
     */
    private final String desc;

    public static final String[] MODES = Arrays.stream(values())
            .map(RequisitionModeEnum::getMode).toArray(String[]::new);

    /**
     * 按配置值解析模式（大小写不敏感，容忍首尾空格，便于环境变量传 `v1`）
     *
     * @param mode 配置值
     * @return 匹配到的模式；无法匹配返回 {@code null}（由调用方转成业务错误码，不在此处抛异常）
     */
    public static RequisitionModeEnum parse(String mode) {
        if (mode == null) {
            return null;
        }
        String normalized = mode.trim();
        return ArrayUtil.firstMatch(item -> item.getMode().equalsIgnoreCase(normalized), values());
    }

}
