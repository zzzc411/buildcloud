package com.buildcloud.material.service.requisition.mq;

import cn.hutool.core.util.StrUtil;
import com.buildcloud.material.enums.stock.RequisitionMqConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 申领单**超时关单消费者**（spec §6.1 步骤⑥：延迟消息 30 分钟到期 → 未审完则关闭 + 回滚）
 *
 * ========== 薄适配层（与 {@link RequisitionDeductConsumer} 同构） ==========
 *
 * 本类只做三件事：接住消息 → 校验非畸形 → 委托给独立 bean 的事务方法。
 * 业务（状态白名单、超时核对、CAS 关单、回补库存）全在
 * {@link RequisitionTimeoutCloseService}，理由与扣减消费者完全相同：
 * 1. **事务必须跨 bean**：`@Transactional` 靠 AOP 代理生效，同类自调用会静默失效；
 * 2. **可测性**：MQ 的 ListenerContainer 在 H2 单测里不起（也不想为它拉一套 Broker），
 *    业务剥到独立 bean 后，全部幂等/误关/回补路径都能用真事务直接测。
 *
 * ========== 抛异常 = 重投 = 死信（唯一的失败信号） ==========
 *
 * 方法正常返回 → ACK；抛 RuntimeException → 按 `maxReconsumeTimes` 重投，超过进
 * `%DLQ%buildcloud-material-requisition-timeout-close-consumer`。
 * **重投期间延迟属性不再生效**（立即投递），所以关单逻辑本身必须幂等 —— 已经幂等：
 * 状态 CAS + 超时核对 + 回滚组件的行级冲正流水判据，三道闸都挡重复。
 *
 * 注意 `maxReconsumeTimes` 只在 {@link ConsumeMode#CONCURRENTLY} 下生效（顺序消费固定 16 次），
 * 与扣减消费者选同一模式的理由见 {@code RequisitionMqConstants#MAX_RECONSUME_TIMES} 注释。
 *
 * ========== 多租户 ==========
 *
 * 真正的 Consumer：基座 {@code TenantRocketMQConsumeMessageHook} 会从消息 userProperty
 * 取 `tenant-id` 设进 {@code TenantContextHolder}，消费完自动 clear。
 * 因此这里不需要自己处理上下文（对照：事务消息监听器不是 Consumer，必须自己处理，见
 * {@code RequisitionDeductTransactionListener}）。
 *
 * @author buildcloud
 */
@Component
@RocketMQMessageListener(
        topic = RequisitionMqConstants.TOPIC_REQUISITION_TIMEOUT_CLOSE,
        consumerGroup = RequisitionMqConstants.CONSUMER_GROUP_REQUISITION_TIMEOUT_CLOSE,
        selectorExpression = RequisitionMqConstants.TAG_REQUISITION_TIMEOUT_CLOSE,
        consumeMode = ConsumeMode.CONCURRENTLY,
        maxReconsumeTimes = RequisitionMqConstants.MAX_RECONSUME_TIMES_TIMEOUT_CLOSE)
@Slf4j
public class RequisitionTimeoutCloseConsumer implements RocketMQListener<RequisitionTimeoutMessage> {

    @Resource
    private RequisitionTimeoutCloseService timeoutCloseService;

    /**
     * 消费入口
     *
     * @param message 超时关单消息
     */
    @Override
    public void onMessage(RequisitionTimeoutMessage message) {
        // 畸形消息（既无 id 也无单号）重投一万次也不会变好：ACK 掉，保留 error 日志供人工回捞
        if (message == null || (message.getRequisitionId() == null && StrUtil.isBlank(message.getReqNo()))) {
            log.error("[onMessage][畸形超时关单消息已丢弃（requisitionId 与 reqNo 均为空）message={}]", message);
            return;
        }
        // 委托给独立 bean 的代理对象（事务边界在那边；不吞异常 = 失败会重投/进死信）
        RequisitionTimeoutCloseService.TimeoutCloseOutcome outcome = timeoutCloseService.closeIfTimedOut(message);
        log.info("[onMessage][超时关单消息处理完毕 reqNo={} requisitionId={} outcome={}]",
                message.getReqNo(), message.getRequisitionId(), outcome);
    }

}
