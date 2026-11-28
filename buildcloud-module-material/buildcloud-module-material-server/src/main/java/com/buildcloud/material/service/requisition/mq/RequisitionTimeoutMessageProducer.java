package com.buildcloud.material.service.requisition.mq;

import cn.hutool.core.util.StrUtil;
import com.buildcloud.material.enums.stock.RequisitionMqConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 申领单**超时关单延迟消息**发送器（spec §6.1 步骤⑥）
 *
 * ========== 延迟级别表与「30 分钟 = level 16」的实测依据 ==========
 *
 * RocketMQ 4.x/5.x 的**固定**延迟级别共 18 级（Broker 端 `messageDelayLevel` 决定）：
 * <pre>
 *   level :  1    2    3     4     5    6    7    8    9    10   11   12   13   14   15    16    17   18
 *   时长  :  1s   5s   10s   30s   1m   2m   3m   4m   5m   6m   7m   8m   9m   10m  20m   30m   1h   2h
 * </pre>
 *
 * **这不是凭记忆抄的**（AGENTS §6.3），本项目 2026-11-28 实测确认：
 * 1. `docker exec bc-rmq-broker cat conf/broker.conf` → **没有** `messageDelayLevel` 配置项；
 * 2. 从运行中的容器拷出 `rocketmq-store-5.3.1.jar`，`javap -c MessageStoreConfig`
 *    → `ldc // String 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h`
 *    （即 Broker 用的是默认 18 级表，第 16 级 = 30m）；
 * 3. 客户端 classpath 上是 `rocketmq-client 5.3.2`（版本表与上面一致）。
 *
 * 因此 30 分钟 = **level 16** = {@link RequisitionMqConstants#DELAY_LEVEL_30_MINUTES}。
 * 若运维改了 Broker 的 `messageDelayLevel`，这个常量必须同步改——**改了不会报错，只会"早到/晚到"**，
 * 所以消费端还有一次 `timeout_time <= now()` 的二次核对兜底（见 {@code RequisitionTimeoutCloseService}）。
 *
 * ========== 两种发送姿势（用哪一种由配置决定，不是随手选的） ==========
 *
 * | 姿势 | API（Spring 封装） | 消息属性 | 支持面 |
 * | --- | --- | --- | --- |
 * | **固定级别**（生产默认） | `syncSend(dest, msg, timeout, delayLevel)` | `DELAY = 16` | 4.x / 5.x 都支持 |
 * | **秒级定时**（联调/集成测试） | `syncSendDelayTimeSeconds(dest, msg, seconds)` | `TIMER_DELAY_SEC` | 5.x 定时消息（本机 Broker 5.3.1 支持） |
 *
 * **为什么默认用固定级别**：它是 4.x 与 5.x 的**共同子集**，"30 分钟"这件事不依赖 Broker 是否开了
 * 定时消息能力；而秒级能力只为了把 30 分钟调成 30 秒做验证（否则一次联调要等半小时）。
 * 两条路径的字节码都用 `javap` 核对过（2.3.6）：
 * - 秒级：私有 `syncSend(dest, msg, timeout, delaySeconds, DelayMode.DELAY_SECONDS)` → `Message#setDelayTimeSec`
 *   → `producer.send(msg, timeout)`；
 * - 级别：4 参 `syncSend(dest, msg, timeout, delayLevel)` → `Message#setDelayTimeLevel`
 *   → 同样 `producer.send(msg, timeout)`。
 * 差别只在消息属性上，**由 Broker 决定怎么解释**。
 *
 * 选择开关：`buildcloud.material.requisition-timeout-close.delay-level`
 * （默认 16；联调时可配成 0 并配 `delay-seconds` 走秒级，或直接配更小的 level 如 3 = 10 秒）。
 *
 * ========== 失败语义：不抛异常，返回 false ==========
 *
 * 关单提醒发不出去**不能**让用户的"提交"整体失败：
 * - 提交本身（预扣 + 状态推进 + 流程实例）已经成功了，回滚它会把一条合法业务变成失败；
 * - 漏发的后果只是"这张单不会自动关"——单据停在待审批，**保守方向**（少卖，不超卖），
 *   且 `timeout_time` 列本身就是兜底扫描（对账 Job / 运维可补发或直接关闭）。
 *
 * 因此本类把异常收敛成 false + error 日志，由调用方决定（见
 * `RequisitionApprovalServiceImpl#sendTimeoutCloseMessage`）。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class RequisitionTimeoutMessageProducer {

    /**
     * RocketMQ destination：`topic:tag`
     */
    static final String DESTINATION = RequisitionMqConstants.TOPIC_REQUISITION_TIMEOUT_CLOSE
            + ":" + RequisitionMqConstants.TAG_REQUISITION_TIMEOUT_CLOSE;

    /**
     * 发送超时（毫秒）——延迟属性不影响发送本身的耗时，与扣减消息同量级即可
     */
    private static final long SEND_TIMEOUT_MILLIS = 3000L;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 固定延迟级别（默认 16 = 30 分钟；配 0 或负数则改走秒级)
     */
    @Value("${buildcloud.material.requisition-timeout-close.delay-level:"
            + RequisitionMqConstants.DELAY_LEVEL_30_MINUTES + "}")
    private int delayLevel;

    /**
     * 秒级延迟（仅在 {@link #delayLevel} <= 0 时生效；默认 1800 秒 = 30 分钟）
     *
     * 联调/集成测试把它配成 30（秒）：`-Dbuildcloud.material.requisition-timeout-close.delay-level=0`
     * + `-Dbuildcloud.material.requisition-timeout-close.delay-seconds=30`。**演示完必须改回默认**。
     */
    @Value("${buildcloud.material.requisition-timeout-close.delay-seconds:"
            + RequisitionMqConstants.TIMEOUT_CLOSE_DELAY_SECONDS + "}")
    private long delaySeconds;

    /**
     * 发送超时关单延迟消息
     *
     * @param message 消息体
     * @return true = Broker 已接收（到点会投递）；false = 发送失败/异常（**不抛**，见类注释）
     */
    public boolean send(RequisitionTimeoutMessage message) {
        validate(message);
        int level = delayLevel;
        if (level > 0) {
            return sendWithDelayLevel(message, level);
        }
        long seconds = delaySeconds > 0 ? delaySeconds : RequisitionMqConstants.TIMEOUT_CLOSE_DELAY_SECONDS;
        try {
            SendResult result = rocketMQTemplate.syncSendDelayTimeSeconds(DESTINATION,
                    MessageBuilder.withPayload(message).build(), seconds);
            if (!isSendOk(result)) {
                log.error("[send][超时关单延迟消息发送失败 reqNo={} delaySeconds={} sendStatus={} msgId={}]",
                        message.getReqNo(), seconds, statusOf(result), result == null ? null : result.getMsgId());
                return false;
            }
            log.info("[send][超时关单延迟消息已投递（秒级定时消息）reqNo={} requisitionId={} delaySeconds={} msgId={}]",
                    message.getReqNo(), message.getRequisitionId(), seconds, result.getMsgId());
            return true;
        } catch (Exception e) {
            log.error("[send][超时关单延迟消息发送异常 reqNo={} delaySeconds={}]", message.getReqNo(), seconds, e);
            return false;
        }
    }

    /**
     * 用**固定 delay level** 发送（RocketMQ 4.x/5.x 通用姿势，生产默认）
     *
     * @param message    消息体
     * @param level      delay level（1..18；本项目 30 分钟 = 16）
     * @return 是否发送成功
     */
    public boolean sendWithDelayLevel(RequisitionTimeoutMessage message, int level) {
        validate(message);
        if (level <= 0) {
            throw new IllegalArgumentException("delay level 必须为正数（当前 " + level + "）");
        }
        try {
            SendResult result = rocketMQTemplate.syncSend(DESTINATION,
                    MessageBuilder.withPayload(message).build(), SEND_TIMEOUT_MILLIS, level);
            if (!isSendOk(result)) {
                log.error("[sendWithDelayLevel][发送失败 reqNo={} delayLevel={} sendStatus={} msgId={}]",
                        message.getReqNo(), level, statusOf(result), result == null ? null : result.getMsgId());
                return false;
            }
            log.info("[sendWithDelayLevel][超时关单延迟消息已投递 reqNo={} requisitionId={} delayLevel={} msgId={}]",
                    message.getReqNo(), message.getRequisitionId(), level, result.getMsgId());
            return true;
        } catch (Exception e) {
            log.error("[sendWithDelayLevel][发送异常 reqNo={} delayLevel={}]", message.getReqNo(), level, e);
            return false;
        }
    }

    /**
     * 当前生效的延迟表达式（日志/单测用，配置改了立即生效）
     *
     * @return 如 "level=16（30 分钟）" 或 "seconds=30"
     */
    public String getDelayDescription() {
        return delayLevel > 0
                ? "level=" + delayLevel
                : "seconds=" + (delaySeconds > 0 ? delaySeconds : RequisitionMqConstants.TIMEOUT_CLOSE_DELAY_SECONDS);
    }

    /**
     * 构造超时关单消息
     *
     * @param requisitionId 申领单编号
     * @param reqNo         申领单号
     * @param projectId     项目编号
     * @param timeoutTime   提交时写入的超时截止时间（消费者用它做二次核对）
     * @return 消息体
     */
    public static RequisitionTimeoutMessage buildMessage(Long requisitionId, String reqNo, Long projectId,
                                                         LocalDateTime timeoutTime) {
        return RequisitionTimeoutMessage.builder()
                .requisitionId(requisitionId)
                .reqNo(reqNo)
                .projectId(projectId)
                .timeoutTime(timeoutTime)
                .submitTime(LocalDateTime.now())
                .build();
    }

    private static void validate(RequisitionTimeoutMessage message) {
        if (message == null || StrUtil.isBlank(message.getReqNo()) || message.getRequisitionId() == null) {
            // 消息体缺关键字段是**编程错误**（调用方刚构造完就发），必须立刻暴露，不能静默丢
            throw new IllegalArgumentException("超时关单消息的 reqNo / requisitionId 不能为空");
        }
    }

    private static boolean isSendOk(SendResult result) {
        return result != null && result.getSendStatus() == SendStatus.SEND_OK;
    }

    private static SendStatus statusOf(SendResult result) {
        return result == null ? null : result.getSendStatus();
    }

}
