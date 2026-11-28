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
 *
 *   TOPIC   buildcloud-material-requisition-timeout-close   申领单超时关单消息（延迟消息，W5-P3）
 *   TAG     timeout-close
 *   GROUP   buildcloud-material-requisition-timeout-close-consumer
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

    // ========== 延迟关单消息（W5-P3：spec §6.1 步骤⑥「延迟消息 30 分钟」） ==========

    /**
     * 申领单超时关单消息 Topic（延迟消息：生产时带 delay level，到期才投递）
     *
     * 与扣减消息**分开成两个 Topic**（而不是同一个 Topic 用不同 Tag）：
     * 它们走的是两套 Producer（普通 producer vs 事务 producer），消费组、重试口径、死信队列
     * 也都不同。合并到一个 Topic 会带来两个具体麻烦：
     * ① 同一个 Topic 上的消息延迟属性只对**单独一条**生效，但控制台上"这个 Topic 里混着
     *    立即投递与 30 分钟后投递"的消息，排查延迟问题时极易误判；
     * ② 扣减消息的事务回查监听器按消息体类型判定（`instanceof RequisitionDeductMessage`），
     *    混投另一种消息体时会被判成 ROLLBACK——语义上就是错的。
     */
    public static final String TOPIC_REQUISITION_TIMEOUT_CLOSE = "buildcloud-material-requisition-timeout-close";

    /**
     * 申领单超时关单消息 Tag
     */
    public static final String TAG_REQUISITION_TIMEOUT_CLOSE = "timeout-close";

    /**
     * 超时关单消费者组
     */
    public static final String CONSUMER_GROUP_REQUISITION_TIMEOUT_CLOSE =
            "buildcloud-material-requisition-timeout-close-consumer";

    /**
     * 超时关单的 **RocketMQ 4.x/5.x 固定 delay level**：30 分钟 = 16
     *
     * ========== 这张表必须与 Broker 实际配置一致（本项目的实测依据） ==========
     *
     * <pre>
     *   level :  1    2    3     4     5    6    7    8    9    10   11   12   13   14   15    16    17   18
     *   时长  :  1s   5s   10s   30s   1m   2m   3m   4m   5m   6m   7m   8m   9m   10m  20m   30m   1h   2h
     * </pre>
     *
     * **本项目怎么确认的（不是凭记忆，AGENTS §6.3）**：
     * 1. `docker exec bc-rmq-broker cat conf/broker.conf` → 里面**没有** `messageDelayLevel` 配置项；
     * 2. 从运行中的容器拷出 `rocketmq-store-5.3.1.jar`，`javap -c MessageStoreConfig` →
     *    `ldc // String 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h`
     *    即 Broker 用的是默认 18 级表，第 16 级 = 30m（与客户端 `rocketmq-client 5.3.2` 版本表一致）。
     *
     * 因此 30 分钟 = **16**。若将来运维改了 Broker 的 `messageDelayLevel`（比如换成 5.x 自定义
     * 时间戳级别），这个常量必须同步改，否则消息会按错误的级别投递（不会报错，只会"早到/晚到"）
     * ——`RequisitionTimeoutCloseConsumer` 里还有一次 `timeout_time <= now()` 的兜底核对。
     *
     * 联调时把延迟调短用 {@link #TIMEOUT_CLOSE_DELAY_SECONDS}（走 5.x 的秒级定时消息），
     * 生产默认走 level 16（4.x/5.x 都支持的固定级别）。
     */
    public static final int DELAY_LEVEL_30_MINUTES = 16;

    /**
     * 延迟关单的**默认秒级延迟**（30 分钟 = 1800 秒）
     *
     * 只在秒级模式（`buildcloud.material.requisition-timeout-close.delay-level` &lt;= 0）下生效：
     * 联调/集成测试把它配成 30 秒来验证「关单真的会触发」，否则一次验证要等半小时。
     * 生产默认走固定级别（{@link #DELAY_LEVEL_30_MINUTES}），本常量只作为秒级模式的默认值。
     *
     * **默认值必须是 1800**：任何演示/联调之后都要改回来（配置项而非代码常量，
     * 用 `-D` 覆盖即可，不需要改文件、也不需要重启别的服务）。
     */
    public static final long TIMEOUT_CLOSE_DELAY_SECONDS = 30L * 60L;

    /**
     * 延迟关单消息的最大重投次数（3 次后进死信 `%DLQ%buildcloud-material-requisition-timeout-close-consumer`）
     *
     * 与扣减消费者同口径（{@link #MAX_RECONSUME_TIMES}）：回补失败是**确定性**的账目问题，
     * 重投只是给瞬时故障（锁等待/连接抖动）一次机会，更多的重试只会推迟人工介入。
     * 注意：重投期间消息的延迟属性**不会再生效**，重投是立即投递的（RocketMQ 语义），
     * 所以关单本身是幂等 + 有 `timeout_time` 二次核对，重复投递不会误关。
     */
    public static final int MAX_RECONSUME_TIMES_TIMEOUT_CLOSE = 3;

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
