package com.buildcloud.material.service.requisition.mq;

import cn.hutool.core.util.StrUtil;
import com.buildcloud.material.enums.stock.RequisitionMqConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * 申领扣减**事务消息发送器**（spec §6.1 步骤④ 的前半段：发 half 消息）
 *
 * ========== 为什么单独一个类，而不是在 V2 处理器里直接调 RocketMQTemplate ==========
 *
 * 1. **可测性**：单测要把"发消息成功/失败"变成可控输入（用 Mock 替换本类），
 *    而 `RocketMQTemplate` 是第三方 final-ish 的重类，直接 mock 它会连"事务监听器有没有注册"一起 mock 掉，
 *    测出来的东西没有意义；
 * 2. **失败语义集中**：发消息失败（Namesrv 不可达 / Broker 拒绝 / 超时）必须触发
 *    「回滚 Redis 预扣 + 单据回退草稿」，这套补偿只应有一处实现；
 * 3. **与基座封装对齐**：基座的 MQ starter 只提供 `RocketMQTemplate` 这个入口（AGENTS §6.3 不臆造 API），
 *    本类是它在本域的唯一用法出口，将来换成延迟消息/其他 topic 时改一处。
 *
 * ========== sendMessageInTransaction 的返回语义（javap 核对过 2.3.6，不猜） ==========
 *
 * <pre>
 *   TransactionSendResult sendMessageInTransaction(String destination, Message&lt;?&gt; message, Object arg)
 *     destination = "topic:tag"
 *     arg         = 传给 TransactionListener#executeLocalTransaction 的第二个参数
 *   TransactionSendResult.getLocalTransactionState() // 本地事务结果（COMMIT/ROLLBACK/UNKNOWN）
 *   TransactionSendResult.getSendStatus()            // half 消息是否发到了 Broker
 * </pre>
 *
 * 注意两个"状态"不是一回事：
 * - `sendStatus != SEND_OK` → **half 消息都没发出去**，本地事务根本没执行，此时本地事务返回的
 *   COMMIT/ROLLBACK 不代表任何事；必须按"发送失败"处理（回滚预扣 + 回退单据）；
 * - `localTransactionState == ROLLBACK` → half 消息发出去了，但本地事务失败 → Broker 会丢弃它，
 *   对业务来说同样是"提交失败"，但**不能**再回退单据（监听器已经回滚了本地事务）。
 *
 * ========== 多租户 ==========
 *
 * 基座 {@code TenantRocketMQSendMessageHook} 在发送前把 `TenantContextHolder` 的 tenantId
 * 写进消息 userProperty（键 `tenant-id`），消费端与事务回查端据此恢复上下文。
 * 本类不需要也不应该自己拼这个头——重复设置会让两处口径分叉。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class RequisitionDeductMessageProducer {

    /**
     * RocketMQ destination 形式：`topic:tag`（RocketMQ Spring 的约定，分隔符必须是半角冒号）
     */
    static final String DESTINATION = RequisitionMqConstants.TOPIC_REQUISITION_DEDUCT
            + ":" + RequisitionMqConstants.TAG_REQUISITION_DEDUCT;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    /**
     * 发送事务消息（half → 本地事务 → commit/rollback 全由 RocketMQ 驱动）
     *
     * @param message 扣减消息
     * @param reqNo   事务参数（交给 TransactionListener 定位单据；同时已在 message 里冗余一份，
     *                因为回查路径只能拿到消息体）
     * @return true = half 消息已发出**且**本地事务 COMMIT（消息将投递）；false = 没发出去或本地事务回滚
     */
    public boolean send(RequisitionDeductMessage message, String reqNo) {
        if (message == null || StrUtil.isBlank(reqNo)) {
            throw new IllegalArgumentException("事务消息的 message/reqNo 不能为空");
        }
        TransactionSendResult result = rocketMQTemplate.sendMessageInTransaction(DESTINATION,
                MessageBuilder.withPayload(message).build(), reqNo);

        SendStatus sendStatus = result == null ? null : result.getSendStatus();
        if (sendStatus != SendStatus.SEND_OK) {
            // half 消息没到 Broker：本地事务根本没执行，必须让调用方走"发送失败"补偿
            log.error("[send][half 消息发送失败 reqNo={} sendStatus={} msgId={}]", reqNo, sendStatus,
                    result == null ? null : result.getMsgId());
            return false;
        }
        // 走到这里 half 消息已落盘，本地事务刚执行完
        //
        // **实测踩到（W5-P2）**：`TransactionSendResult#getLocalTransactionState()` 返回的是
        // 客户端侧的 `org.apache.rocketmq.client.producer.LocalTransactionState`，
        // 而 TransactionListener 里返回的是 RocketMQ-Spring 的
        // `org.apache.rocketmq.spring.core.RocketMQLocalTransactionState` —— **两个同名不同包的枚举**，
        // 直接 == 比较编译报「不可比较的类型」。Spring 封装只是把二者做了一层映射，
        // 这里必须用客户端枚举常量比较。这正是 AGENTS §6.3「不猜接口」的又一实证。
        if (result.getLocalTransactionState() != LocalTransactionState.COMMIT_MESSAGE) {
            // 本地事务回滚/未知：Broker 会丢弃 half 消息（UNKNOWN 会先回查若干轮）。
            // 本地事务自己已经回滚过，调用方不要再回退单据，只需要把 Redis 预扣还回去。
            log.error("[send][本地事务未提交 reqNo={} localTransactionState={}]",
                    reqNo, result.getLocalTransactionState());
            return false;
        }
        log.info("[send][事务消息已提交 reqNo={} msgId={} queue={}]",
                reqNo, result.getMsgId(), result.getMessageQueue());
        return true;
    }

}
