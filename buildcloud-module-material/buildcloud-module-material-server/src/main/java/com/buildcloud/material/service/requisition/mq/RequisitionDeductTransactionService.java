package com.buildcloud.material.service.requisition.mq;

import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.mysql.requisition.RequisitionMapper;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.requisition.RequisitionStatusEnum;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * V2 申领扣减的**事务承载类**：DB 真实扣减 + 流水落库 + 状态推进
 *
 * ========== 为什么单独成类（而不是写在 {@code RequisitionDeductConsumer} 里） ==========
 *
 * 两个原因，都来自本项目踩过的坑：
 * 1. **自调用陷阱**：`@Transactional` 靠 Spring AOP 代理生效，**同类内部 this.xxx() 调用不经过代理**，
 *    事务静默失效——代码看着有注解、实际每条 SQL 自动提交，"整单原子"变成空话。
 *    把事务方法放到独立 bean、由消费者**注入代理对象**调用，从结构上消除这个隐患
 *    （单测里还会断言"第 2 行失败时第 1 行也必须回滚"，专门盯这条）。
 * 2. **可测性**：事务语义（回滚）必须在有真实事务的上下文里验证，而 MQ 的 ListenerContainer
 *    在 H2 单测里不起（也不想为它起一套 Broker）。独立 bean 让单测能直接调
 *    {@link #deduct} 验证全部幂等/回滚/兜底路径，MQ 只负责"把消息递过来"这一薄层。
 *
 * ========== 为什么影响 0 行必须抛错、而不是"优雅返回" ==========
 *
 * `available_qty >= qty` 是 spec §6.1「防超卖三道防线」的**第二道（最后一道实时防线）**。
 * 影响 0 行只有两种可能：
 * 1. 库存行不存在/已删除 → 数据事故；
 * 2. **Redis 说够、DB 不够** → 漂移已经发生（Redis 被清、回滚丢失、人工改库）。
 * 两种都必须让人知道。若静默 ACK，表现是"申领成功了、库存没扣"，账面永久少一块且无人知晓
 * ——lessons 里反复强调的"最难查的一类账不平"。
 * 所以：抛异常 → RocketMQ 按 {@code MAX_RECONSUME_TIMES} 重投 → 仍失败进死信
 * `%DLQ%buildcloud-material-requisition-deduct-consumer` → 对账 Job（P4）兜底。
 * 重投在这里不是"期待运气变好"，而是给瞬时故障（连接抖动 / 行锁等待超时）一次机会；
 * 确定性漂移一定会在死信里现身。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class RequisitionDeductTransactionService {

    @Resource
    private RequisitionMapper requisitionMapper;

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    /**
     * 整单扣减（**一个本地事务**：要么整单生效，要么一行都不动）
     *
     * @param message 扣减消息
     */
    @Transactional(rollbackFor = Exception.class)
    public void deduct(RequisitionDeductMessage message) {
        String reqNo = message.getReqNo();

        // ===== ② 整单幂等闸门：先看单据状态 =====
        RequisitionDO requisition = message.getRequisitionId() == null
                ? null : requisitionMapper.selectById(message.getRequisitionId());
        if (requisition == null && message.getRequisitionId() != null) {
            // 带了 id 却查不到：单据被物理删除或租户上下文不对，重投也不会好转 → ACK 并告警
            log.error("[deduct][申领单不存在，丢弃消息 reqNo={} requisitionId={}]",
                    reqNo, message.getRequisitionId());
            return;
        }
        if (requisition == null) {
            log.warn("[deduct][消息未携带 requisitionId，跳过整单状态闸门，仅按行级幂等处理 reqNo={}]", reqNo);
        } else if (isAlreadyDeducted(requisition.getStatus())) {
            // 幂等命中：直接返回（不抛异常 = ACK），不重复扣减
            log.info("[deduct][幂等：单据已处于状态 {}，跳过扣减 reqNo={}]", requisition.getStatus(), reqNo);
            return;
        }

        // ===== ③ 逐行扣减（materialId 升序，避免多行交叉加锁的 AB-BA 死锁） =====
        List<RequisitionDeductMessage.Item> items = RequisitionDeductLocalTransactionService
                .sortByMaterialId(message.getItems());
        for (RequisitionDeductMessage.Item item : items) {
            deductOneItem(reqNo, message.getProjectId(), item);
        }

        // ===== ④ 状态 CAS：已提交(1) → 已扣减(2) =====
        if (requisition != null) {
            int rows = requisitionMapper.deductCas(requisition.getId());
            if (rows != 1) {
                // 影响 0 行 = 单据已不是已提交(1)：要么被并发消费抢先推进到 2（此时本事务里
                // 刚才的扣减必须一起回滚，否则就是重复扣减），要么被人工取消/关闭。
                // 抛出 → 整笔回滚；RocketMQ 重投时 ② 会发现状态已是 2 而直接 ACK。
                log.error("[deduct][状态 CAS 未命中 reqNo={} status={}，回滚本次扣减]",
                        reqNo, requisition.getStatus());
                throw new IllegalStateException("申领单状态已变化，本次扣减回滚：reqNo=" + reqNo
                        + ", status=" + requisition.getStatus());
            }
        }

        log.info("[deduct][V2 扣减完成 reqNo={} projectId={} itemCount={}]",
                reqNo, message.getProjectId(), items.size());
    }

    /**
     * 单行明细：幂等判重 → 条件扣减 → 写流水
     *
     * @param reqNo      申领单号（幂等键 / 流水 biz_no）
     * @param projectId  项目编号
     * @param item       明细（materialId + quantity，quantity 恒为正）
     */
    private void deductOneItem(String reqNo, Long projectId, RequisitionDeductMessage.Item item) {
        BigDecimal quantity = item.getQuantity();
        if (quantity == null || quantity.signum() <= 0) {
            // 数量非正的消息没有业务含义（0 扣 / 负扣都会破坏流水符号约定）
            log.error("[deductOneItem][明细数量非法，整条消息回滚 reqNo={} materialId={} qty={}]",
                    reqNo, item.getMaterialId(), quantity);
            throw new IllegalArgumentException("申领明细数量必须大于 0：reqNo=" + reqNo
                    + ", materialId=" + item.getMaterialId() + ", qty=" + quantity);
        }

        // 3.1 行级幂等：本单本物料是否已有申领扣减流水
        // 与 ② 的关系：② 是整单闸门（快），这里是行级兜底（精确到物料）。
        // 真正的价值场景：单据被人工改回 1（数据修复）后消息重投，行级判据能防止重复扣。
        Long existing = stockLogMapper.selectCountByBizNoAndType(projectId, item.getMaterialId(), reqNo,
                StockBizTypeEnum.REQUISITION_OUT.getBizType());
        if (existing != null && existing > 0) {
            MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialId(projectId, item.getMaterialId());
            log.warn("[deductOneItem][幂等：本行已有 {} 条扣减流水，跳过 reqNo={} materialId={} 当前DB可用量={}]",
                    existing, reqNo, item.getMaterialId(),
                    stock == null ? null : stock.getAvailableQty());
            return;
        }

        // 3.2 DB 条件扣减（防超卖第二道防线）
        int rows = materialStockMapper.reduceAvailableQtyByBusinessKey(projectId, item.getMaterialId(), quantity);
        if (rows != 1) {
            // 见类注释「为什么影响 0 行必须抛错」：这是 Redis 与 DB 漂移的唯一可观测点
            log.error("[deductOneItem][DB 兜底防线触发：Redis 预扣成功但 DB 可用量不足 reqNo={} projectId={} materialId={} qty={}]",
                    reqNo, projectId, item.getMaterialId(), quantity);
            throw new IllegalStateException(String.format(
                    "DB 条件扣减影响 0 行（Redis 与 DB 漂移）：reqNo=%s projectId=%s materialId=%s qty=%s",
                    reqNo, projectId, item.getMaterialId(), quantity));
        }

        // 3.3 写流水：quantity 取负、balance_after = 扣减后余额
        // balance_after 用「条件更新成功后重读」而不是「读+算」：
        // 并发扣减下"读到的值 - qty"可能被别人插队改掉，重读库才是这条流水真正对应的余额。
        MaterialStockDO after = materialStockMapper.selectByProjectIdAndMaterialId(projectId, item.getMaterialId());
        BigDecimal balanceAfter = after == null ? BigDecimal.ZERO : after.getAvailableQty();
        stockLogMapper.insert(RequisitionDeductLocalTransactionService.buildDeductLog(
                reqNo, projectId, item.getMaterialId(), quantity, balanceAfter));

        log.debug("[deductOneItem][扣减成功 reqNo={} materialId={} qty={} balanceAfter={}]",
                reqNo, item.getMaterialId(), quantity, balanceAfter);
    }

    /**
     * 判断单据是否已扣减（或更靠后的状态）——整单幂等判据
     *
     * 只把「已扣减(2) 及之后」视为已处理；草稿(0)/已提交(1) 都要继续处理。
     * 已关闭(8)/已取消(9) **不算**已处理，会继续走扣减逻辑，最终被 DB 条件更新或状态 CAS 拦住 →
     * 抛错进重试/死信，由对账 Job（P4）兜底。**不在这里静默 ACK**：终态单据收到扣减消息
     * 说明 P3 的回滚与消息投递发生了竞态，属于必须让人看见的异常。
     *
     * @param status 单据状态
     * @return 是否已扣减
     */
    private boolean isAlreadyDeducted(Integer status) {
        if (status == null) {
            return false;
        }
        return RequisitionStatusEnum.DEDUCTED.getStatus().equals(status)
                || RequisitionStatusEnum.OUTBOUND.getStatus().equals(status)
                || RequisitionStatusEnum.COMPLETED.getStatus().equals(status)
                || RequisitionStatusEnum.PARTIAL_RETURNED.getStatus().equals(status);
    }

}
