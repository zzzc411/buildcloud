package com.buildcloud.material.enums.stock;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequisitionMqConstants} 的**契约钉死**单测（不依赖任何中间件）
 *
 * ========== 为什么要给常量写单测 ==========
 *
 * Topic / ConsumerGroup / ProducerGroup 是**跨进程、跨发布批次**的契约：
 * 线上新旧实例同时存在时，它们必须看到同一个 Group 才会"互相分担"而不是"各消费一份"。
 * 改一个字母的后果是：新实例把老实例的消息也消费一遍或相反，排障时表现为
 * "偶尔扣两次 / 偶尔不扣"，而代码评审看不出问题（两边都自洽）。
 *
 * 所以这里把三个值**钉死**：改它就必须改测试，改测试就必须在 PR 里解释
 * （"这次是灰度新 Topic，老实例不下线"之类）。同理，`MAX_RECONSUME_TIMES` 钉死是因为
 * 它直接决定"多久进死信"——调大调小都是运维口径决策，不该顺手改。
 *
 * @author buildcloud
 */
public class RequisitionMqConstantsTest {

    @Test
    public void testTopicNames_pinned() {
        // 值本身是契约：带模块前缀避免与本地其他项目撞名（见常量类注释）
        assertEquals("buildcloud-material-requisition-deduct",
                RequisitionMqConstants.TOPIC_REQUISITION_DEDUCT);
        assertEquals("deduct", RequisitionMqConstants.TAG_REQUISITION_DEDUCT);
    }

    @Test
    public void testGroups_pinned() {
        assertEquals("buildcloud-material-requisition-deduct-consumer",
                RequisitionMqConstants.CONSUMER_GROUP_REQUISITION_DEDUCT);
        assertEquals("buildcloud-material-requisition-deduct-producer",
                RequisitionMqConstants.PRODUCER_GROUP_REQUISITION_DEDUCT);
    }

    @Test
    public void testGroups_areDistinct() {
        // Producer 与 Consumer 组名必须不同：同名时 Broker 侧的自检/监控会把它们混在一起，
        // 排查"谁发的、谁收的"要翻日志猜（见常量类注释）
        assertNotEquals(RequisitionMqConstants.PRODUCER_GROUP_REQUISITION_DEDUCT,
                RequisitionMqConstants.CONSUMER_GROUP_REQUISITION_DEDUCT,
                "生产者组与消费者组不得同名");
    }

    @Test
    public void testConsumerGroup_followsProjectNamingConvention() {
        // 基座约定：所有资源名带 buildcloud- 前缀（AGENTS R-9 同一口径）。
        // RocketMQ 的 Group 有合法字符集限制：只允许 % _ - 与字母数字，不能有空格与点
        String group = RequisitionMqConstants.CONSUMER_GROUP_REQUISITION_DEDUCT;
        assertTrue(group.startsWith("buildcloud-"), "消费组必须以 buildcloud- 开头：" + group);
        assertTrue(group.matches("[%_\\-a-zA-Z0-9]+"), "消费组只能包含 % _ - 与字母数字：" + group);
        assertTrue(RequisitionMqConstants.TOPIC_REQUISITION_DEDUCT.matches("[%_\\-a-zA-Z0-9]+"),
                "Topic 只能包含 % _ - 与字母数字");
    }

    @Test
    public void testMaxReconsumeTimes_pinned() {
        // 3：覆盖瞬时故障，同时避免确定性失败（DB 漂移）在死信前反复重试几十次
        assertEquals(3, RequisitionMqConstants.MAX_RECONSUME_TIMES);
    }

    @Test
    public void testClass_isFinalWithPrivateConstructor() throws Exception {
        assertTrue(Modifier.isFinal(RequisitionMqConstants.class.getModifiers()),
                "常量类必须是 final（禁止继承扩出第二份口径）");
        var constructor = RequisitionMqConstants.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()), "常量类构造器必须私有");
        constructor.setAccessible(true);
        constructor.newInstance(); // 只为覆盖构造器的私有分支，不产生副作用

        // 所有字段都必须是 public static final String/int：防止有人往里塞可变静态状态
        for (Field field : RequisitionMqConstants.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers),
                    "字段 " + field.getName() + " 必须是 static final");
        }
    }

    @Test
    public void testNoDuplicateValuesBetweenTopicAndTag() {
        Set<String> values = new HashSet<>();
        assertTrue(values.add(RequisitionMqConstants.TOPIC_REQUISITION_DEDUCT));
        // tag 与 topic 不必不同，但 Group 必须与 Topic 不同（同名会让 Broker 的鉴权规则打架）
        assertFalse(values.contains(RequisitionMqConstants.CONSUMER_GROUP_REQUISITION_DEDUCT),
                "消费组不得与 Topic 同名");
    }

}
