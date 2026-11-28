package com.buildcloud.material.service.requisition.mq;

import cn.iocoder.yudao.framework.test.core.ut.BaseDbUnitTest;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import jakarta.annotation.Resource;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequisitionDeductLocalTransactionService} 的**事务回查单测**（H2）
 *
 * ========== 本类钉住的两条协议语义（任务书第⑥条的前两项） ==========
 *
 * 1. **本地事务成功 → 回查应返回 COMMIT**：单据处于已提交(1) 及之后；
 * 2. **单据不存在 → 回查应返回 ROLLBACK**：half 消息必须被丢弃。
 *
 * 另加三条边界：
 * 3. 单据仍是草稿(0)（本地事务确实没执行成功）→ ROLLBACK；
 * 4. 单据已被消费到已扣减(2) → **仍然 COMMIT**（回查晚到，不能把已生效的消息标记成丢弃）；
 * 5. 发消息失败的补偿 {@code revertToDraft}：1→0 允许，2→0 **拒绝**（禁止账目倒退）。
 *
 * ========== 为什么不用真 Broker 也信任这个结论 ==========
 *
 * Broker 的回查协议（多少秒后回查、回查几次）是 RocketMQ 自己的事，不是本项目的逻辑；
 * 本项目要保证的是**回调函数的返回值正确**——它是纯函数（输入 reqNo / 库状态 → 输出状态），
 * 用 H2 直接测足够。真 Broker 的端到端由 {@code RocketMQEndToEndIntegrationTest} 覆盖
 * （那一个测"链路通"，这一个测"判断对"）。
 *
 * @author buildcloud
 */
@Import(RequisitionDeductLocalTransactionService.class)
@Sql(scripts = "/sql/requisition-test-tables.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public class RequisitionDeductLocalTransactionServiceTest extends BaseDbUnitTest {

    @Resource
    private RequisitionDeductLocalTransactionService localTransactionService;

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private MaterialStockMapper materialStockMapper;

    // =================================================================================
    // 一、本地事务：草稿 → 已提交
    // =================================================================================

    @Test
    public void testMarkSubmitted_fromDraft_commitAndStatusBecomesSubmitted() {
        Long id = insertRequisition("REQ-LT-1", RequisitionStatusEnum.DRAFT);

        RocketMQLocalTransactionState state = localTransactionService.markSubmitted("REQ-LT-1");

        assertEquals(RocketMQLocalTransactionState.COMMIT, state, "草稿单的本地事务应成功 → COMMIT");
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(),
                requisitionMapper.selectById(id).getStatus(), "单据应落库为已提交(1)");
    }

    @Test
    public void testMarkSubmitted_notExists_rollback() {
        // 单据不存在：生产者发了别人/不存在的单号 → 必须 ROLLBACK，否则消费者会处理一张不存在的单
        RocketMQLocalTransactionState state = localTransactionService.markSubmitted("REQ-NOT-EXISTS");

        assertEquals(RocketMQLocalTransactionState.ROLLBACK, state, "单据不存在必须 ROLLBACK（half 消息丢弃）");
    }

    @Test
    public void testMarkSubmitted_alreadySubmitted_idempotentCommit() {
        // 已提交(1)：说明本地事务之前已成过（回查重入 / 生产者重发）→ 幂等 COMMIT，不得重复改动
        Long id = insertRequisition("REQ-LT-2", RequisitionStatusEnum.SUBMITTED);

        RocketMQLocalTransactionState state = localTransactionService.markSubmitted("REQ-LT-2");

        assertEquals(RocketMQLocalTransactionState.COMMIT, state, "已提交态应幂等返回 COMMIT");
        assertEquals(RequisitionStatusEnum.SUBMITTED.getStatus(), requisitionMapper.selectById(id).getStatus());
    }

    @Test
    public void testMarkSubmitted_canceledStatus_rollback() {
        // 已取消(9) 的终态单：不能把它拉回已提交（那会让一张已回滚预扣的单据被扣库存）
        Long id = insertRequisition("REQ-LT-3", RequisitionStatusEnum.CANCELED);

        RocketMQLocalTransactionState state = localTransactionService.markSubmitted("REQ-LT-3");

        assertEquals(RocketMQLocalTransactionState.ROLLBACK, state, "终态单的本地事务必须 ROLLBACK");
        assertEquals(RequisitionStatusEnum.CANCELED.getStatus(), requisitionMapper.selectById(id).getStatus(),
                "状态不得被改动");
    }

    @Test
    public void testMarkSubmitted_blankReqNo_rollback() {
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, localTransactionService.markSubmitted(null));
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, localTransactionService.markSubmitted("  "));
    }

    // =================================================================================
    // 二、回查：COMMIT / ROLLBACK 的判定
    // =================================================================================

    @Test
    public void testCheckSubmitted_submitted_commit() {
        insertRequisition("REQ-CHECK-1", RequisitionStatusEnum.SUBMITTED);

        assertEquals(RocketMQLocalTransactionState.COMMIT,
                localTransactionService.checkSubmitted("REQ-CHECK-1"),
                "已提交(1) 说明本地事务成过 → COMMIT");
    }

    @Test
    public void testCheckSubmitted_notExists_rollback() {
        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                localTransactionService.checkSubmitted("REQ-CHECK-NONE"),
                "单据不存在 → ROLLBACK（half 消息丢弃）");
    }

    @Test
    public void testCheckSubmitted_draft_rollback() {
        insertRequisition("REQ-CHECK-2", RequisitionStatusEnum.DRAFT);

        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                localTransactionService.checkSubmitted("REQ-CHECK-2"),
                "仍是草稿说明本地事务没成 → ROLLBACK");
    }

    @Test
    public void testCheckSubmitted_alreadyDeducted_stillCommit() {
        // 回查晚到（消息已被消费、单据已是 2）：本地事务确实成过，必须 COMMIT。
        // 若这里回 ROLLBACK，会给一条**已生效**的消息打上"丢弃"标记，制造矛盾证据。
        insertRequisition("REQ-CHECK-3", RequisitionStatusEnum.DEDUCTED);

        assertEquals(RocketMQLocalTransactionState.COMMIT,
                localTransactionService.checkSubmitted("REQ-CHECK-3"),
                "已扣减(2) 说明本地事务成过且已消费，回查应 COMMIT");
    }

    @Test
    public void testCheckSubmitted_canceled_rollback() {
        insertRequisition("REQ-CHECK-4", RequisitionStatusEnum.CANCELED);

        assertEquals(RocketMQLocalTransactionState.ROLLBACK,
                localTransactionService.checkSubmitted("REQ-CHECK-4"),
                "已取消(9) 说明预扣已回滚，不能 COMMIT 让消费者去扣一个没预留的库存");
    }

    // =================================================================================
    // 三、补偿：发消息失败时退回草稿（禁止账目倒退）
    // =================================================================================

    @Test
    public void testRevertToDraft_fromSubmitted_success() {
        Long id = insertRequisition("REQ-REVERT-1", RequisitionStatusEnum.SUBMITTED);

        assertTrue(localTransactionService.revertToDraft("REQ-REVERT-1"), "1→0 应允许");
        assertEquals(RequisitionStatusEnum.DRAFT.getStatus(), requisitionMapper.selectById(id).getStatus());
    }

    @Test
    public void testRevertToDraft_alreadyDraft_idempotentTrue() {
        insertRequisition("REQ-REVERT-2", RequisitionStatusEnum.DRAFT);

        assertTrue(localTransactionService.revertToDraft("REQ-REVERT-2"), "已经是草稿应幂等返回 true");
    }

    @Test
    public void testRevertToDraft_fromDeducted_refused() {
        // 已扣减(2) 说明消息已被消费、DB 已扣：改回草稿就是账目倒退（库扣了、单据说没提交）
        Long id = insertRequisition("REQ-REVERT-3", RequisitionStatusEnum.DEDUCTED);

        assertTrue(!localTransactionService.revertToDraft("REQ-REVERT-3"), "2→0 必须拒绝");
        assertEquals(RequisitionStatusEnum.DEDUCTED.getStatus(), requisitionMapper.selectById(id).getStatus(),
                "状态不得被改回草稿");
    }

    @Test
    public void testRevertToDraft_notExists_false() {
        assertTrue(!localTransactionService.revertToDraft("REQ-REVERT-NONE"));
    }

    // =================================================================================
    // 四、工具方法（消费者与 V2 处理器共用）
    // =================================================================================

    @Test
    public void testSumQuantity_nullSafe() {
        assertEquals(0, BigDecimal.ZERO.compareTo(
                RequisitionDeductLocalTransactionService.sumQuantity(null)), "null 应返回 0 而不是抛 NPE");
        assertEquals(0, BigDecimal.ZERO.compareTo(
                RequisitionDeductLocalTransactionService.sumQuantity(java.util.List.of())), "空集合返回 0");
        assertEquals(0, new BigDecimal("3.5").compareTo(
                RequisitionDeductLocalTransactionService.sumQuantity(
                        // Arrays.asList（不是 List.of）：List.of 不允许 null 元素，
                        // 而本用例专门要验证"集合里混了 null 也不能 NPE"
                        java.util.Arrays.asList(new BigDecimal("1.5"), null, new BigDecimal("2")))),
                "忽略 null 后求和");
    }

    @Test
    public void testSortByMaterialId_ascending() {
        var sorted = RequisitionDeductLocalTransactionService.sortByMaterialId(java.util.List.of(
                RequisitionDeductMessage.Item.builder().materialId(3L).quantity(BigDecimal.ONE).build(),
                RequisitionDeductMessage.Item.builder().materialId(1L).quantity(BigDecimal.ONE).build(),
                RequisitionDeductMessage.Item.builder().materialId(2L).quantity(BigDecimal.ONE).build()));
        assertEquals(java.util.List.of(1L, 2L, 3L),
                sorted.stream().map(RequisitionDeductMessage.Item::getMaterialId).toList(),
                "必须按 materialId 升序（跨版本的死锁规避约定）");
    }

    @Test
    public void testBuildDeductLog_negatesQuantity() {
        var log = RequisitionDeductLocalTransactionService.buildDeductLog(
                "REQ-LOG", 1L, 2L, new BigDecimal("7.5"), new BigDecimal("42.5"));
        assertEquals(0, new BigDecimal("-7.5").compareTo(log.getQuantity()), "quantity 必须取负");
        assertEquals(0, new BigDecimal("42.5").compareTo(log.getBalanceAfter()));
        assertEquals("REQ-LOG", log.getBizNo());
        assertEquals(2, log.getBizType(), "biz_type 必须是 2 申领扣减");
    }

    @Test
    public void testCountDeductLog_zeroWhenNoLog() {
        insertStock(1L, 1L, "100");
        assertEquals(0L, localTransactionService.countDeductLog("REQ-NONE", 1L, 1L));
    }

    // =================================================================================
    // helper
    // =================================================================================

    private Long insertRequisition(String reqNo, RequisitionStatusEnum status) {
        RequisitionDO requisition = RequisitionDO.builder()
                .reqNo(reqNo).projectId(1L).applicantUserId(1L)
                .status(status.getStatus()).approvalStatus(-1)
                .build();
        requisitionMapper.insert(requisition);
        return requisition.getId();
    }

    private void insertStock(Long projectId, Long materialId, String qty) {
        materialStockMapper.insert(MaterialStockDO.builder()
                .projectId(projectId).materialId(materialId)
                .availableQty(new BigDecimal(qty)).lockedQty(BigDecimal.ZERO).version(0)
                .build());
    }

}
