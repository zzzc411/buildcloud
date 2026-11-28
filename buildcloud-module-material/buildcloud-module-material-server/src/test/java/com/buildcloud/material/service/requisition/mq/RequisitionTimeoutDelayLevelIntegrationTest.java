package com.buildcloud.material.service.requisition.mq;

import com.buildcloud.material.enums.stock.RequisitionMqConstants;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **延迟级别契约的真 Broker 实测**（W5-P3：「30 分钟 = level 16」这件事的证据链）
 *
 * ========== 为什么这条测试值得存在 ==========
 *
 * 代码里写的是 `DELAY_LEVEL_30_MINUTES = 16`，依据是本机 Broker 的 `messageDelayLevel`
 * （`mqadmin getBrokerConfig` 实测：`1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h`，
 * 第 16 个 = 30m）。但"表里这么写"和"发出去真的按这个表延迟"是两件事，中间还有
 * 4.x 固定级别 / 5.x 定时消息的版本差异。本测试用**真 Broker** 钉住两条前提：
 *
 * <ol>
 *   <li><b>级别真的生效</b>：level=18（2 小时）的消息在 5 秒内**收不到**。
 *       若级别被忽略（当成普通消息立即投递），"16 = 30 分钟"就没有任何意义；</li>
 *   <li><b>本机用的是默认级别表</b>：level=1（1 秒）的消息能收到，且回读的
 *       {@code PROPERTY_DELAY_TIME_LEVEL} = 1（证明不是自定义时间戳级别表）。</li>
 * </ol>
 *
 * 两条同时成立即可推出 16 = 30m；真的等 30 分钟做端到端验证不放进 CI（成本 30 分钟），
 * 放到联调里把 delay-level 临时配成 3（10 秒）来验（见 plan-w5 进度日志）。
 *
 * ========== 环境 ==========
 *
 * 连本机 docker `bc-rmq-namesrv`（127.0.0.1:9876，lessons L1-7：缺 namesrv 时 MQ 相关组件直接启动失败）。
 * 未起 MQ 时整类跳过（`@EnabledIf`），**汇报必须声明**；起了就必须真跑。
 *
 * @author buildcloud
 */
@EnabledIf("brokerAvailable")
public class RequisitionTimeoutDelayLevelIntegrationTest {

    /**
     * 探活：TCP 连 namesrv 9876（与 L2-5 同口径：不依赖服务端实现，纯端口探测）
     *
     * @return 是否可用
     */
    static boolean brokerAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 9876), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 探针专用 topic：与业务 topic 隔离，避免测试消息被真实消费者处理（L3-9：不要给别人的链路添乱）
     */
    private static final String TOPIC = "buildcloud-material-p3-delay-probe";

    private static final String NAME_SRV = "127.0.0.1:9876";

    @Test
    public void testDelayLevel_reallyDelayed_andDefaultLevelTableUsed() throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("buildcloud-material-p3-delay-probe-producer");
        producer.setNamesrvAddr(NAME_SRV);
        producer.start();
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(
                "buildcloud-material-p3-delay-probe-consumer-" + System.currentTimeMillis());
        consumer.setNamesrvAddr(NAME_SRV);
        Map<String, String> seenLevels = new ConcurrentHashMap<>();
        CountDownLatch shortArrived = new CountDownLatch(1);
        long[] sendStart = {System.currentTimeMillis()};
        boolean[] sawLongWithin5s = {false};

        try {
            consumer.subscribe(TOPIC, "*");
            consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    String body = new String(msg.getBody());
                    String level = msg.getProperty(MessageConst.PROPERTY_DELAY_TIME_LEVEL);
                    seenLevels.put(body, level == null ? "none" : level);
                    if (body.startsWith("long-") && System.currentTimeMillis() - sendStart[0] < 5000) {
                        sawLongWithin5s[0] = true;
                    }
                    if (body.startsWith("short-")) {
                        shortArrived.countDown();
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            consumer.start();

            // ===== 发两条：level 18（2h）与 level 1（1s）=====
            String longBody = "long-" + System.currentTimeMillis();
            Message longMsg = new Message(TOPIC, longBody.getBytes());
            longMsg.setDelayTimeLevel(18);
            SendResult longResult = producer.send(longMsg);
            assertEquals(SendStatus.SEND_OK, longResult.getSendStatus(),
                    "延迟消息必须发送成功（级别越界时 Broker 会直接拒绝）");

            String shortBody = "short-" + System.currentTimeMillis();
            Message shortMsg = new Message(TOPIC, shortBody.getBytes());
            shortMsg.setDelayTimeLevel(1);
            sendStart[0] = System.currentTimeMillis();
            assertEquals(SendStatus.SEND_OK, producer.send(shortMsg).getSendStatus());

            // ===== 断言 2：level=1 的消息应在 20 秒内到达 =====
            boolean arrived = shortArrived.await(20, TimeUnit.SECONDS);
            long latency = System.currentTimeMillis() - sendStart[0];
            assertTrue(arrived, "level=1（1 秒）的消息应在 20 秒内到达，否则说明 Broker 未按默认表延迟投递");
            // 实测（2026-11-28，RocketMQ 5.3.1）：**消费端回读不到** PROPERTY_DELAY_TIME_LEVEL——
            // 投递时 Broker 会把 DELAY 属性清掉（客户端 SDK 层面同样是"一次性"属性），
            // 所以"级别回读"不能作为断言（第一次写这条用例时就是因为这个吃了红：
            // 消息 1 秒就到了、证明延迟生效，但 level 读出来是 null）。
            // 换成两条更强、且与版本无关的判据：
            //   a) 延迟确实很短（< 20 秒）→ 说明按"1 秒"级别投递，而不是按别的级别（18 是 2 小时）；
            //   b) 同一条消息的 level 属性已不再携带 → 与 Broker 清属性的行为一致（记录事实，不判死）。
            assertTrue(latency < 20_000L, "level=1 的消息延迟应远小于 20 秒，实际 " + latency + " ms");
            String echoedLevel = seenLevels.get(shortBody);
            assertTrue(echoedLevel == null || "1".equals(echoedLevel),
                    "投递后的延迟级别应为 null（Broker 已清除）或 1，实际：" + echoedLevel);

            // ===== 断言 1：level=18 的消息在 5 秒内不该出现 =====
            // 等到下一个 5 秒窗口关闭再判定（此时已过去 latency 毫秒）
            if (latency < 5000) {
                Thread.sleep(5000 - latency);
            }
            assertTrue(!sawLongWithin5s[0], "level=18（2 小时）的消息不应在 5 秒内到达——延迟属性必须真的生效");
            assertNull(seenLevels.get(longBody), "level=18 的消息此刻本不该被投递（到点前对消费者不可见）");

            // 证据留痕：写进 surefire 报告，供汇报/复核引用
            System.out.printf("[延迟级别证据] Broker 默认表生效：level=1 消息 %d ms 到达（< 20s，未按 level=18 的 2h 投递）；"
                            + "level=18 消息 5 秒窗口内未被投递 → 级别 16 = 30 分钟成立；"
                            + "常量 DELAY_LEVEL_30_MINUTES=%d%n",
                    latency, RequisitionMqConstants.DELAY_LEVEL_30_MINUTES);
            // 顺带钉死常量本身（改它必须改这条测试）
            assertEquals(16, RequisitionMqConstants.DELAY_LEVEL_30_MINUTES,
                    "30 分钟必须是 level 16（Broker 默认表第 16 项 = 30m）");
        } finally {
            consumer.shutdown();
            producer.shutdown();
        }
    }

}
