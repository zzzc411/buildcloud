package com.buildcloud.material.service.requisition.mq;

import com.buildcloud.material.enums.stock.RequisitionMqConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * V2 申领扣减消费者（**薄适配层**：只负责"从 MQ 接住消息 → 交给事务服务"，不做业务）
 *
 * <pre>
 *   commit → 消费者：DB 真实库存扣减（乐观锁 version）
 *               + stock_log 落库（分片表）+ 状态→已扣减
 * </pre>
 *
 * ========== 为什么消费者里几乎没有业务代码 ==========
 *
 * 1. **事务必须跨 bean**：{@code @Transactional} 靠 AOP 代理生效，同类自调用不经过代理，
 *    事务会静默失效。业务放在 {@link RequisitionDeductTransactionService}（独立 bean），
 *    由本类注入**代理对象**调用，从结构上保证「整单原子」而不是靠读者记得别写 this.xxx()；
 * 2. **可测性**：MQ 的 ListenerContainer 在 H2 单测里不起（也不想为它拉一套 Broker）。
 *    业务逻辑剥到独立 bean 后，幂等/回滚/兜底三类路径都能用真事务直接测；
 *    本类只剩「畸形消息 ACK」这一条判断，由 {@code RequisitionDeductConsumerTest} 直接覆盖。
 *
 * ========== 抛异常 = 重投 = 死信（唯一的失败信号） ==========
 *
 * RocketMQ 的消费重试只有两种结局：方法正常返回 → ACK；抛 RuntimeException → 重投。
 * 达到 {@link RequisitionMqConstants#MAX_RECONSUME_TIMES} 次仍失败 → Broker 把消息投进
 * `%DLQ%{consumerGroup}`，业务侧不再被调用，必须靠日志告警 + 对账 Job（P4）发现。
 * 因此本类**绝不 catch 业务异常**——吞掉任何异常都等于把"账不平"变成"静默通过"。
 *
 * ========== 为什么用 CONCURRENTLY 而不是 ORDERLY ==========
 *
 * 顺序消费模式下 `maxReconsumeTimes` 不生效（固定 16 次、且单条失败会阻塞整个队列），
 * 与"超过次数进死信"的验收标准冲突；而幂等已由「单据状态 + 行级流水计数」两道判据保证，
 * 并发消费不会重复扣。同一单据的多条消息也天然被这两道判据串行化（后到的那条会看到状态已是 2）。
 *
 * ========== 多租户 ==========
 *
 * 本类是真正的 **Consumer**，基座 {@code TenantRocketMQConsumeMessageHook} 会从消息
 * userProperty 取 `tenant-id` 设进 {@code TenantContextHolder}，消费后自动 clear。
 * 因此这里不需要自己设上下文（事务监听器不是 Consumer，才需要自己处理，见该类的说明）。
 *
 * @author buildcloud
 */
@Component
@RocketMQMessageListener(
        topic = RequisitionMqConstants.TOPIC_REQUISITION_DEDUCT,
        consumerGroup = RequisitionMqConstants.CONSUMER_GROUP_REQUISITION_DEDUCT,
        selectorExpression = RequisitionMqConstants.TAG_REQUISITION_DEDUCT,
        consumeMode = ConsumeMode.CONCURRENTLY,
        maxReconsumeTimes = RequisitionMqConstants.MAX_RECONSUME_TIMES)
@Slf4j
public class RequisitionDeductConsumer implements RocketMQListener<RequisitionDeductMessage> {

    @Resource
    private RequisitionDeductTransactionService transactionService;

    /**
     * 消费入口（抛异常 = 让 RocketMQ 重投；这是重试与死信的唯一触发方式）
     *
     * @param message 扣减消息
     */
    @Override
    public void onMessage(RequisitionDeductMessage message) {
        // 畸形消息重投一万次也不会变好：ACK 掉，保留 error 日志供人工回捞
        if (message == null || message.getReqNo() == null || message.getReqNo().isBlank()
                || message.getProjectId() == null || message.getItems() == null
                || message.getItems().isEmpty()) {
            log.error("[onMessage][畸形扣减消息已丢弃 message={}]", message);
            return;
        }
        // 委托给独立 bean 的代理对象（不开事务、不吞异常，见类注释）
        transactionService.deduct(message);
    }

}
