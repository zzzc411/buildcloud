package com.buildcloud.material.service.requisition.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 申领扣减消息体（RocketMQ 事务消息的业务载荷）
 *
 * ========== 为什么消息里带"全部明细"而不是只带 reqNo ==========
 *
 * 消费者要做的三件事：DB 条件扣减、写流水、推进状态。若消息只带 `reqNo`，消费者必须
 * 反查 `bc_requisition_item`——这会让「消息」与「库」之间多一次读依赖：
 * 一旦明细在消息投递期间被改动（管理端改单/删行），消费者扣的就是**改变后的数量**，
 * 而 Redis 预扣是**改变前**的数量，账立刻不平。
 *
 * 因此消息体是「**本地事务的产物快照**」：明细在建单时就固定下来，本地事务只负责把
 * 「这批数量」的一半消息变成已提交流水，消费者完全按消息里的数量扣减，不反查库。
 * 这也让消费逻辑变成纯函数式（消息 → 固定 SQL 序列），单测无需构造明细表。
 *
 * ========== 字段口径（与 db 字段一一对应，不另起命名） ==========
 *
 * <pre>
 *   reqNo        申领单号（= bc_stock_log.biz_no，也是消费幂等键）
 *   requisitionId 申领单主键（消费者推进状态用；允许为 null：单测/纯扣减场景）
 *   projectId    项目（= 分片键，W6 分片后缺它路由不到）
 *   items        明细快照（materialId + quantity，quantity 恒为正数）
 *   operator     发起人（写流水 creator 不用它——那是登录态；这里只用于日志排障）
 * </pre>
 *
 * 实现 {@link Serializable}：RocketMQ Spring 默认用 `MessageConverter` 序列化，
 * 留一个可序列化标记，方便将来换 JMS/Redis 队列复用同一 DTO。
 *
 * @author buildcloud
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequisitionDeductMessage implements Serializable {

    /**
     * 申领单号（业务单号 = 流水 biz_no = 消费幂等键）
     */
    private String reqNo;

    /**
     * 申领单主键（消费者据此把单据从 已提交(1) 推进到 已扣减(2)）
     */
    private Long requisitionId;

    /**
     * 项目编号（流水分片键）
     */
    private Long projectId;

    /**
     * 明细快照（顺序不保证，消费者按 materialId 升序处理以规避 AB-BA 死锁）
     */
    private List<Item> items;

    /**
     * 操作人（仅日志/排障用，不参与业务判定）
     */
    private String operator;

    /**
     * 明细项：物料 + 数量
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item implements Serializable {

        /**
         * 物料编号
         */
        private Long materialId;

        /**
         * 申领数量（**恒为正数**；负号由消费者在写流水时翻转，见 StockBizTypeEnum 符号约定）
         */
        private BigDecimal quantity;

    }

}
