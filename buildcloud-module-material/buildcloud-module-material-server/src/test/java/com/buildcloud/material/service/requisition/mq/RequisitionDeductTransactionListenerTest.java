package com.buildcloud.material.service.requisition.mq;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils.HEADER_TENANT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequisitionDeductTransactionListener} 的**纯单测**（不起 Spring、不起 MQ）
 *
 * ========== 被测的是什么 ==========
 *
 * 本类的全部价值在「**把业务结果翻译成 MQ 协议状态**」和「**补齐消费/回查线程的租户上下文**」。
 * 前者用 Mockito-free 的手写 Stub（{@code StubLocalTransactionService}）验证，
 * 因为要断言的恰恰是"监听器有没有把异常吞成 ROLLBACK"这种行为，用 Stub 最直白；
 * 后者（租户上下文）用一个"记录收到的 reqNo、其余行为可配置"的 Stub 就能观察。
 *
 * 为什么不用 Mockito：本模块的 mockito-inline 版本对 `final`/`static` 有约束，
 * 而这里的依赖面很窄（一个 service、一个静态方法），手写 Stub 比 mock 更易读、也没有版本风险。
 *
 * ========== 关键断言（对应「TransactionListener 绝不能把异常漏给 RocketMQ」） ==========
 *
 * RocketMQ Spring 的 `TransactionMQProducerImpl` 会把 `executeLocalTransaction` 抛出的异常
 * 当作 UNKNOWN（→ 60s 一次回查 × 15 次），而真正原因只剩日志里一行堆栈。
 * 所以**任何异常都必须被翻译成 ROLLBACK**：用例 {@link #testExecute_exceptionFromService_rollback}。
 *
 * @author buildcloud
 */
public class RequisitionDeductTransactionListenerTest {

    private RequisitionDeductTransactionListener listener;

    private StubLocalTransactionService stub;

    @BeforeEach
    public void setUp() throws Exception {
        listener = new RequisitionDeductTransactionListener();
        stub = new StubLocalTransactionService();
        Field field = RequisitionDeductTransactionListener.class
                .getDeclaredField("localTransactionService");
        field.setAccessible(true);
        field.set(listener, stub);
        TenantContextHolder.clear();
    }

    @AfterEach
    public void tearDown() {
        TenantContextHolder.clear();
    }

    // ================= 一、executeLocalTransaction =================

    @Test
    public void testExecute_stringArg_passesReqNoAndReturnsServiceState() {
        stub.executeResult = RocketMQLocalTransactionState.COMMIT;

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(halfMessage("REQ-1"), "REQ-1");

        assertEquals(RocketMQLocalTransactionState.COMMIT, state);
        assertEquals("REQ-1", stub.lastExecuteReqNo, "事务参数里的 reqNo 必须传给本地事务服务");
    }

    @Test
    public void testExecute_serviceReturnsRollback_propagates() {
        stub.executeResult = RocketMQLocalTransactionState.ROLLBACK;

        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                listener.executeLocalTransaction(halfMessage("REQ-2"), "REQ-2"));
    }

    @Test
    public void testExecute_exceptionFromService_rollback() {
        // 本地事务抛异常（DB 挂了 / 唯一键冲突…）：必须 ROLLBACK，绝不能漏给 RocketMQ 变 UNKNOWN
        stub.executeThrows = new IllegalStateException("模拟本地事务异常");

        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                listener.executeLocalTransaction(halfMessage("REQ-3"), "REQ-3"),
                "本地事务异常必须翻译成 ROLLBACK（而不是抛出 → UNKNOWN → 15 次回查）");
    }

    @Test
    public void testExecute_illegalArgType_rollbackWithoutCallingService() {
        // 生产者接错监听器（比如传了 Long/Map）：不能盲猜，直接 ROLLBACK
        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                listener.executeLocalTransaction(halfMessage("REQ-4"), 12345L));
        assertNull(stub.lastExecuteReqNo, "参数非法时不得调用本地事务服务");
    }

    @Test
    public void testExecute_blankArg_rollback() {
        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                listener.executeLocalTransaction(halfMessage("REQ-5"), "   "));
        assertNull(stub.lastExecuteReqNo);
    }

    // ================= 二、checkLocalTransaction =================

    @Test
    public void testCheck_reqNoFromPayload_commit() {
        stub.checkResult = RocketMQLocalTransactionState.COMMIT;

        // 回查时**只有消息体可用**（arg 不参与），reqNo 必须从 payload 里解析
        assertEquals(RocketMQLocalTransactionState.COMMIT,
                listener.checkLocalTransaction(halfMessage("REQ-6")));
        assertEquals("REQ-6", stub.lastCheckReqNo, "回查的 reqNo 必须来自消息体");
    }

    @Test
    public void testCheck_payloadNotExpectedType_rollback() {
        // 消息体不是本模块的 DTO（串味/被别的生产者投递）：无法判断本地事务状态 → ROLLBACK
        Message<String> foreign = MessageBuilder.withPayload("some-other-payload").build();

        assertEquals(RocketMQLocalTransactionState.ROLLBACK, listener.checkLocalTransaction(foreign));
        assertNull(stub.lastCheckReqNo);
    }

    @Test
    public void testCheck_exception_unknown() {
        // 回查期间 DB 不可用属瞬时故障：返回 UNKNOWN 让 Broker 下一轮再问，
        // 绝不能 ROLLBACK（那会误杀一条本地事务其实已提交的消息）
        stub.checkThrows = new IllegalStateException("模拟 DB 不可用");

        assertEquals(RocketMQLocalTransactionState.UNKNOWN,
                listener.checkLocalTransaction(halfMessage("REQ-7")),
                "回查异常必须返回 UNKNOWN（交给 Broker 下一轮回查）");
    }

    // ================= 三、租户上下文 =================

    @Test
    public void testExecute_tenantHeader_appliedThenRestored() {
        // 基座发送钩子把 tenantId 写进 userProperty；事务监听器不是 Consumer，基座不会替它设上下文
        stub.executeResult = RocketMQLocalTransactionState.COMMIT;
        TenantContextHolder.setTenantId(99L);

        Message<?> message = MessageBuilder.withPayload(payload("REQ-8"))
                .setHeader(HEADER_TENANT_ID, "1").build();
        listener.executeLocalTransaction(message, "REQ-8");

        assertEquals(1L, stub.tenantIdInsideService, "本地事务执行期间 tenantId 应为消息头里的 1");
        // 执行完必须恢复到调用前的值（这里是 99），不能清空——RocketMQ 线程是复用的
        assertEquals(99L, TenantContextHolder.getTenantId(),
                "执行完毕后必须恢复原租户上下文（不得清空复用线程的上下文）");
    }

    @Test
    public void testExecute_noTenantHeader_contextClearedWhenPreviouslyEmpty() {
        // 线程原本没有上下文、消息也没有租户头：执行完必须回到"没有上下文"，不能残留
        stub.executeResult = RocketMQLocalTransactionState.COMMIT;

        listener.executeLocalTransaction(MessageBuilder.withPayload(payload("REQ-9")).build(), "REQ-9");

        assertNull(stub.tenantIdInsideService, "无消息头时不应设置租户上下文");
        assertNull(TenantContextHolder.getTenantId(), "线程原本为空，执行后仍应为空");
    }

    @Test
    public void testExecute_illegalTenantHeader_ignored() {
        // 消息头被写坏（非数字）：按"没有上下文"处理，不能因为一个坏头把事务判死
        stub.executeResult = RocketMQLocalTransactionState.COMMIT;
        Message<?> message = MessageBuilder.withPayload(payload("REQ-10"))
                .setHeader(HEADER_TENANT_ID, "not-a-number").build();

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(message, "REQ-10");

        assertEquals(RocketMQLocalTransactionState.COMMIT, state);
        assertNull(stub.tenantIdInsideService);
    }

    // ================= 四、契约 =================

    @Test
    public void testProducerGroup_matchesConstants() {
        assertEquals(com.buildcloud.material.enums.stock.RequisitionMqConstants
                .PRODUCER_GROUP_REQUISITION_DEDUCT, RequisitionDeductTransactionListener.producerGroup());
    }

    @Test
    public void testListener_implementsRocketMQLocalTransactionListener() {
        assertTrue(listener instanceof org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener);
    }

    // ================= helpers =================

    private Message<?> halfMessage(String reqNo) {
        return MessageBuilder.withPayload(payload(reqNo)).build();
    }

    private RequisitionDeductMessage payload(String reqNo) {
        return RequisitionDeductMessage.builder()
                .reqNo(reqNo).requisitionId(1L).projectId(1L)
                .items(java.util.List.of(RequisitionDeductMessage.Item.builder()
                        .materialId(1L).quantity(new BigDecimal("10")).build()))
                .build();
    }

    /**
     * 手写 Stub：记录收到的 reqNo / 执行期间观察到的 tenantId，行为可配置
     */
    static class StubLocalTransactionService extends RequisitionDeductLocalTransactionService {

        RocketMQLocalTransactionState executeResult = RocketMQLocalTransactionState.COMMIT;

        RocketMQLocalTransactionState checkResult = RocketMQLocalTransactionState.COMMIT;

        RuntimeException executeThrows;

        RuntimeException checkThrows;

        String lastExecuteReqNo;

        String lastCheckReqNo;

        Long tenantIdInsideService;

        @Override
        public RocketMQLocalTransactionState markSubmitted(String reqNo) {
            this.lastExecuteReqNo = reqNo;
            this.tenantIdInsideService = TenantContextHolder.getTenantId();
            if (executeThrows != null) {
                throw executeThrows;
            }
            return executeResult;
        }

        @Override
        public RocketMQLocalTransactionState checkSubmitted(String reqNo) {
            this.lastCheckReqNo = reqNo;
            this.tenantIdInsideService = TenantContextHolder.getTenantId();
            if (checkThrows != null) {
                throw checkThrows;
            }
            return checkResult;
        }

    }

}
