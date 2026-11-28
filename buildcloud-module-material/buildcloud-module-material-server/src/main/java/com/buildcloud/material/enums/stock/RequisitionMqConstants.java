package com.buildcloud.material.enums.stock;

/**
 * V2 申领链路（Redis 预扣 + RocketMQ 事务消息）用到的 MQ 常量
 *
 * ========== 为什么做成常量类而不是散在各处写字符串 ==========
 *
 * Topic / Tag / 消费组是有 **跨进程契约** 的：生产者发到 `buildcloud-material-requisition-deduct`，
 * 消费者的 `@RocketMQMessageListener(topic = ..., consumerGroup = ...)` 必须完全一致；
 * 写错一个字母的表现是「消息发成功、就是没人消费」——没有异常、没有日志，只有库存永远停在
 * 「已提交(1)」。这类"静默断链"在 W3-P4 的 BPM 转发器上已经吃过一次（lessons L2-12），
 * 所以这里用**同一个常量**同时喂给生产者和消费者，用编译器堵住手抄错误。
 *
 * 单测 `RequisitionDeductMqConstantsTest` 会断言消费组名/主题名不被意外改动
 * （改了就等于线上新旧实例互相看不见，属于发布事故）。
 *
 * ========== 三个值的口径 ==========
 *
 * <pre>
 *   TOPIC   buildcloud-material-requisition-deduct  申领扣减消息（事务消息）
 *   TAG     deduct                                  同一次业务动作只有这一个标签，留给将来按 tag 过滤
 *   GROUP   buildcloud-material-requisition-deduct-consumer
 * </pre>
 *
 * Topic 里带模块名前缀（`buildcloud-material-`）而不是裸 `deduct`：
 * 本地/联调共用一套 Namesrv 时，裸 topic 极易与其他项目撞名，撞名会导致
 * 「别人的消息被你消费」这种极难排查的串味问题。
 *
 * @author buildcloud
 */
public final class RequisitionMqConstants {

    /**
     * 申领扣减消息 Topic（事务消息的一半：half 消息先落这里）
     */
    public static final String TOPIC_REQUISITION_DEDUCT = "buildcloud-material-requisition-deduct";

    /**
     * 申领扣减消息 Tag
     */
    public static final String TAG_REQUISITION_DEDUCT = "deduct";

    /**
     * 申领扣减消费者组
     *
     * 命名带上 `-consumer` 后缀：RocketMQ 里 ProducerGroup 与 ConsumerGroup 同名虽不致命，
     * 但排查时无法从日志/控制台一眼区分是"谁发的"还是"谁收的"。
     */
    public static final String CONSUMER_GROUP_REQUISITION_DEDUCT =
            "buildcloud-material-requisition-deduct-consumer";

    /**
     * 事务生产者组（事务消息必须显式指定 group）
     */
    public static final String PRODUCER_GROUP_REQUISITION_DEDUCT =
            "buildcloud-material-requisition-deduct-producer";

    /**
     * 消费失败后 Broker 的最大重投次数（超过即进死信队列 %DLQ%{consumerGroup}）
     *
     * 取 3：数据库条件更新失败（`available_qty >= qty` 影响 0 行）是**确定性失败**——
     * Redis 与 DB 已经漂移，重投再多次也不会自己变好。重试 3 次只为覆盖「瞬时故障」
     * （连接抖动、锁等待超时）；再多的重试只会在死信前反复打日志，并把真正的事故拖延到人发现。
     *
     * 注意：RocketMQ Spring 的 `maxReconsumeTimes` 只在 `ConsumeMode.CONCURRENTLY` 下生效，
     * 顺序消费（ORDERLY）固定重试 16 次且与业务拒绝无关，因此本消费者必须用并发模式。
     */
    public static final int MAX_RECONSUME_TIMES = 3;

    private RequisitionMqConstants() {
    }

}
