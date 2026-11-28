package com.buildcloud.material.service.requisition.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 申领单**超时关单**延迟消息体（spec §6.1 步骤⑥「延迟消息 30 分钟」）
 *
 * ========== 字段口径 ==========
 *
 * <pre>
 *   reqNo       申领单号（业务单号，日志/排障主键）
 *   requisitionId 申领单主键（消费者据此定位单据；**必填**，否则只能按单号兜底查）
 *   projectId   项目编号（日志/排障用；回补走 DB 明细，不依赖本项目编号做运算）
 *   timeoutTime 提交时写入的**超时截止时间**（= 提交时刻 + 30 分钟）
 *              消费者据此做二次核对：`timeout_time <= now()` 才关单。
 *   submitTime  提交时刻（消息发出时间，仅排障用；两边都是「生产侧时钟」）
 * </pre>
 *
 * ========== 为什么消息里要带 timeoutTime（而不是只带 id） ==========
 *
 * 延迟消息**不保证精确到期**：Broker 定时器是秒级扫描，堆积时可能晚到；生产者重发/重投
 * 也可能让同一条消息提前或重复到达。仅凭「收到消息」就关单，会在
 * 「消息早到 + 审批刚好在 30 分钟边界上通过」这类竞态里**误关已通过的单**
 * （状态若是已提交(1) 就更危险：它可能是刚被重新发起审批的另一张单）。
 *
 * 带上 `timeout_time` 做二次核对是 spec §6.1 的语义：**到点才关**。
 * 核对用的是库里的 `timeout_time`（不是消息里的），消息里的值只用于日志对比与排障。
 *
 * 实现 {@link Serializable} 与 {@link RequisitionDeductMessage} 同口径（可复用同一套消息转换器）。
 *
 * @author buildcloud
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequisitionTimeoutMessage implements Serializable {

    /**
     * 申领单号（业务单号；日志与排障主键）
     */
    private String reqNo;

    /**
     * 申领单主键（消费者据此定位单据）
     */
    private Long requisitionId;

    /**
     * 项目编号（仅日志/排障用）
     */
    private Long projectId;

    /**
     * 提交时写入的超时截止时间（消息里的快照；真实判定用库里的值）
     */
    private LocalDateTime timeoutTime;

    /**
     * 提交时刻（消息发出时间，仅排障用）
     */
    private LocalDateTime submitTime;

}
