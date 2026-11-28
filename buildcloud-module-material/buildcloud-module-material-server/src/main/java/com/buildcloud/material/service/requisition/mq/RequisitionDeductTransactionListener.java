package com.buildcloud.material.service.requisition.mq;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import com.buildcloud.material.enums.stock.RequisitionMqConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;

import static cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils.HEADER_TENANT_ID;

/**
 * 申领扣减的 **RocketMQ 事务消息监听器**（half 消息的 commit/rollback 决策点）
 *
 * ========== 三个回调的分工（spec §6.1 步骤④） ==========
 *
 * | 回调 | 谁触发 | 做什么 | 返回什么 |
 * | --- | --- | --- | --- |
 * | {@link #executeLocalTransaction} | 生产者在 half 消息发送成功**之后**同步调用 | 执行本地事务（单据 → 已提交(1)） | COMMIT / ROLLBACK |
 * | {@link #checkLocalTransaction} | Broker 在**没收到上述结果**时回查（默认 60s 后开始，最多 15 次） | 查库判断本地事务成没成 | COMMIT / ROLLBACK / UNKNOWN |
 * | | | 未知一律返回 ROLLBACK 会误杀，返回 UNKNOWN 才让 Broker 继续回查 | |
 *
 * ========== 为什么 `arg` 用「类型判定」而不是强转 ==========
 *
 * {@code RocketMQTemplate#sendMessageInTransaction(topic, message, arg)} 的第三个参数是
 * **任意对象**，由调用方决定塞什么。本类约定塞 **申领单号 String**（而不是整个 DTO）：
 * - 本地事务只需要 `reqNo` 就能定位单据，传 DTO 会让"消息体"和"事务参数"出现两份数量字段，
 *   一旦不一致（改单后重发）就以谁为准变得不清楚；
 * - 回查时 Broker 给的是**原始消息**（重新反序列化），那时候拿不到 `arg`。
 *   所以 `checkLocalTransaction` 必须能**只从消息体**里取出 reqNo——这也是把 reqNo 同时写进
 *   消息体（`RequisitionDeductMessage#reqNo`）而非只靠 `arg` 的原因。
 *
 * 对不认识的 `arg` 类型，直接 ROLLBACK 并 error 日志：这是"生产者接错了事务监听器"的编程错误，
 * COMMIT 会让消费者去处理一条语义不明的消息，比丢弃危险得多。
 *
 * ========== 多租户上下文（R-8 的运行时面） ==========
 *
 * 本地事务与回查都跑在 **RocketMQ 的线程**上，那里没有 HTTP 请求，`TenantContextHolder` 是空的；
 * 而 `bc_requisition` 是 `TenantBaseDO`，多租户插件会往 SQL 里拼 `tenant_id = ?`，
 * 拿不到 tenantId 时 MyBatis-Plus 插件会因 `null` 参数报错（或退化成跨租户查询）。
 * 因此：
 * - 生产侧：基座 {@code TenantRocketMQSendMessageHook} 已把 tenantId 写进消息的 userProperty；
 * - 消费侧：基座 {@code TenantRocketMQConsumeMessageHook} 会为 **Consumer** 设好上下文；
 * - **事务监听器不是 Consumer**，基座没有为它做任何事 → 本类必须自己从消息头取、取完清理。
 *
 * 这条是本次实测踩到的：不补这段，事务消息在 local profile（tenant_id 恒为 1）以外的场景会静默查不到单据。
 *
 * @author buildcloud
 */
@RocketMQTransactionListener
@Slf4j
public class RequisitionDeductTransactionListener implements RocketMQLocalTransactionListener {

    @Resource
    private RequisitionDeductLocalTransactionService localTransactionService;

    /**
     * 执行本地事务（生产者线程）
     *
     * **本方法绝不向外抛异常**：RocketMQ Spring 的
     * {@code TransactionMQProducerImpl} 会把异常吞掉并当作 UNKNOWN 处理，
     * 结果是 Broker 每 60 秒回查一次、最长 15 次，而真正的原因只剩日志里一行堆栈。
     * 这里显式 catch → ROLLBACK，让 half 消息**立刻**被丢弃，问题第一时间暴露在日志里。
     *
     * @param message half 消息（Spring Message 形态，payload 在 {@code getPayload()}）
     * @param arg     生产者传入的事务参数（本类约定为申领单号 String）
     * @return COMMIT = 消息可投递；ROLLBACK = 丢弃 half 消息
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        String reqNo = resolveArgReqNo(arg);
        Long tenantId = readTenantId(message);
        Long previousTenantId = TenantContextHolder.getTenantId();
        try {
            if (reqNo == null) {
                log.error("[executeLocalTransaction][事务参数非法 arg={}，回滚 half 消息]", arg);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            applyTenantContext(tenantId);
            return localTransactionService.markSubmitted(reqNo);
        } catch (Exception e) {
            // 本地事务任何异常（含 @Transactional 回滚）都必须翻译成 ROLLBACK，
            // 而不是让 RocketMQ 判为 UNKNOWN（那会触发 15 次回查，且掩盖原因）
            log.error("[executeLocalTransaction][本地事务异常，回滚 half 消息 reqNo={}]", reqNo, e);
            return RocketMQLocalTransactionState.ROLLBACK;
        } finally {
            restoreTenantContext(previousTenantId);
        }
    }

    /**
     * 回查本地事务（Broker 线程）
     *
     * 回查时**只有消息体**可用（`arg` 不参与），因此 reqNo 从 payload 里取。
     * 取不到（消息体格式不对/被别的生产者混投）→ ROLLBACK：
     * 一条无法判断本地事务状态的消息，投递出去只会让消费者报错重试到死信，不如丢弃。
     *
     * @param message half 消息
     * @return COMMIT / ROLLBACK
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        Long tenantId = readTenantId(message);
        Long previousTenantId = TenantContextHolder.getTenantId();
        try {
            String reqNo = resolvePayloadReqNo(message);
            if (reqNo == null) {
                log.error("[checkLocalTransaction][回查时无法从消息体解析 reqNo，判定 ROLLBACK]");
                return RocketMQLocalTransactionState.ROLLBACK;
            }
            applyTenantContext(tenantId);
            return localTransactionService.checkSubmitted(reqNo);
        } catch (Exception e) {
            // 回查期间数据库不可用等**瞬时故障**：不能直接判 ROLLBACK（会误杀一条本地事务其实已提交的消息），
            // 返回 UNKNOWN 让 Broker 下一轮（60s 后）接着问，最多 15 次后才由 Broker 判 ROLLBACK。
            log.error("[checkLocalTransaction][回查异常，返回 UNKNOWN 等待下一轮回查]", e);
            return RocketMQLocalTransactionState.UNKNOWN;
        } finally {
            restoreTenantContext(previousTenantId);
        }
    }

    // ========== 内部方法 ==========

    /**
     * 从事务参数解析申领单号
     *
     * @param arg 生产者传入的参数
     * @return 申领单号；类型不符或为空返回 null
     */
    private String resolveArgReqNo(Object arg) {
        if (arg instanceof String reqNo && StrUtil.isNotBlank(reqNo)) {
            return reqNo;
        }
        return null;
    }

    /**
     * 从消息体解析申领单号（回查路径唯一可用的来源）
     *
     * 只用 `instanceof` 判定类型：消息体由本模块自己的生产者产生，
     * 若出现别的类型说明有别的服务往本 topic 投消息（串味），此时 ROLLBACK 是对的。
     *
     * @param message 消息
     * @return 申领单号；无法解析返回 null
     */
    private String resolvePayloadReqNo(Message<?> message) {
        if (message == null) {
            return null;
        }
        Object payload = message.getPayload();
        if (payload instanceof RequisitionDeductMessage deductMessage) {
            return StrUtil.isBlank(deductMessage.getReqNo()) ? null : deductMessage.getReqNo();
        }
        return null;
    }

    /**
     * 从消息的 userProperty 读取租户编号（基座发送钩子写入，键名见 {@code WebFrameworkUtils.HEADER_TENANT_ID}）
     *
     * @param message 消息
     * @return 租户编号；未携带返回 null
     */
    private Long readTenantId(Message<?> message) {
        if (message == null) {
            return null;
        }
        Object value = message.getHeaders().get(HEADER_TENANT_ID);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            log.warn("[readTenantId][消息头 tenant-id 非法 value={}，按空处理]", value);
            return null;
        }
    }

    /**
     * 设置租户上下文（null 时不覆盖：保留原值，避免把已有的上下文清掉）
     *
     * @param tenantId 租户编号
     */
    private void applyTenantContext(Long tenantId) {
        if (tenantId != null) {
            TenantContextHolder.setTenantId(tenantId);
        }
    }

    /**
     * 恢复租户上下文（必须回到调用前的值，而不是简单 clear）
     *
     * 为什么不是 clear：RocketMQ 的生产者线程是**线程池复用**的，
     * 直接 clear 可能把外层（比如恰好在请求线程里同步调用生产者）的上下文清掉，
     * 导致后续同一条调用链上的 DB 操作失去租户隔离。
     *
     * @param previousTenantId 调用前的租户编号
     */
    private void restoreTenantContext(Long previousTenantId) {
        if (previousTenantId == null) {
            TenantContextHolder.clear();
        } else {
            TenantContextHolder.setTenantId(previousTenantId);
        }
    }

    /**
     * 供单测/日志使用：本监听器支持的消费组（与常量同源，防手抄漂移）
     *
     * @return 事务生产者组名
     */
    public static String producerGroup() {
        return RequisitionMqConstants.PRODUCER_GROUP_REQUISITION_DEDUCT;
    }

}
