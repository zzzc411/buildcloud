package com.buildcloud.material.service.requisition.strategy;

import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.requisition.RequisitionModeEnum;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_ENOUGH;
import static com.buildcloud.material.enums.ErrorCodeConstants.STOCK_NOT_EXISTS;

/**
 * V1 申领提交策略：**悲观行锁同步扣减**（spec §6.1 V1 原文实现）
 *
 * spec §6.1 原文：「V1（基线，W4 实现）：提交申领 → 同一事务内 `SELECT ... FOR UPDATE` 锁库存行
 * → 校验并扣减 → 写申领单与流水。问题：行锁串行化，热点库存记录成为瓶颈，吞吐随并发骤降
 * ——**这是压测对照组，不是要保留的方案**。」
 *
 * ========== 本类的核心就是「串行」二字（W5 对照组的价值所在，禁止偷偷优化） ==========
 *
 * 逐条明示「为什么故意这样做」：
 * 1. **`FOR UPDATE` 而不是 `version` CAS**：热点行（同项目同物料）上所有扣减请求在这里排队，
 *    吞吐被单行锁的持锁时间上限锁死——这正是 W5 要在 V2 里用 Redis Lua 预扣解决的问题。
 *    若改成 CAS + 重试，两组压测就都变成了乐观锁，差异被抹平，对照实验失效。
 * 2. **不做任何缓存/合并/批量预扣**：不查 Redis、不合并请求、不预热库存。
 * 3. **逐条明细顺序遍历**：没有并发（同一事务内天然串行），排序只为死锁规避（见下）。
 * 4. **失败即抛、整单回滚**：不"部分成功"，因为申领是业务原子事件
 *    （要么这批材料全批，要么不批；部分扣减会让现场不知道该领多少）。
 *
 * ========== 死锁规避：按 materialId 升序加锁 ==========
 *
 * 一张单含多行物料时，若线程 A 先锁物料 2 再锁 1、线程 B 先锁 1 再锁 2，就会经典 AB-BA 死锁。
 * 明细按 materialId 升序处理即消除该可能（所有事务以相同顺序申请锁）。
 * 这是**正确性**需要，不是性能优化，故在 V1 里也保留。
 *
 * ========== balance_after 的取值 ==========
 *
 * 用「锁内读到的 available_qty - 本次扣减量」，**不回查**：
 * 本事务已持有该行排他锁直到提交，期间别的写入进不来，所以算出来的值就是库里的真值
 * （与 P3 入库的 CAS 思路一致，只是那边靠版本号、这边靠行锁）。
 *
 * @author buildcloud
 */
@Component
@Slf4j
public class RequisitionV1SubmitHandler implements RequisitionSubmitHandler {

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    @Override
    public RequisitionModeEnum getMode() {
        return RequisitionModeEnum.V1;
    }

    @Override
    public void submit(RequisitionDO requisition, List<RequisitionItemDO> items) {
        // 按 materialId 升序：消除「多行明细交叉加锁」的 AB-BA 死锁（见类注释）
        List<RequisitionItemDO> orderedItems = items.stream()
                .sorted(Comparator.comparing(RequisitionItemDO::getMaterialId))
                .toList();

        for (RequisitionItemDO item : orderedItems) {
            deductOneItem(requisition, item);
        }
        log.info("[submit][V1 行锁扣减完成 reqNo={} projectId={} itemCount={}]",
                requisition.getReqNo(), requisition.getProjectId(), orderedItems.size());
    }

    /**
     * 单行明细的「锁行 → 校验 → 扣减 → 写流水」四步（V1 的核心动作，spec §6.1 原文逐字对应）
     *
     * @param requisition 申领单（提供 projectId / reqNo）
     * @param item        明细行（提供 materialId / quantity）
     */
    private void deductOneItem(RequisitionDO requisition, RequisitionItemDO item) {
        Long projectId = requisition.getProjectId();
        Long materialId = item.getMaterialId();
        BigDecimal quantity = item.getQuantity();

        // ① SELECT ... FOR UPDATE 锁库存行（V1 的关键：悲观行锁串行化）
        //    必须在事务内——调用链 RequisitionServiceImpl#submitRequisition → 本方法 都在
        //    @Transactional 里，若有人去掉那个注解，这里的锁会随语句结束立即释放，
        //    V1 就退化成无保护的读-改-写（并发用例会立刻红，见 RequisitionServiceImplTest）。
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialIdForUpdate(projectId, materialId);
        if (stock == null) {
            // 没有库存行 = 该项目从未给该物料入过库。用 STOCK_NOT_EXISTS 而不是 STOCK_NOT_ENOUGH：
            // 「库存不足」暗示有库存但不够，而这里根本没有这一行库存，前端提示完全不同
            throw exception(STOCK_NOT_EXISTS);
        }

        // ② 锁内校验可用量（不足 → 整单回滚，由调用方事务兜住）
        BigDecimal available = stock.getAvailableQty() == null ? BigDecimal.ZERO : stock.getAvailableQty();
        if (available.compareTo(quantity) < 0) {
            log.warn("[deductOneItem][库存不足 reqNo={} projectId={} materialId={} available={} need={}]",
                    requisition.getReqNo(), projectId, materialId, available, quantity);
            throw exception(STOCK_NOT_ENOUGH, available.toPlainString(), quantity.toPlainString());
        }

        // ③ available_qty -= qty —— **行锁下的直接更新，不用 CAS 重试**（这就是 V1 本身）
        //    影响行数 0 属于「锁内竟然更新不到」的异常：要么行在锁后被删（不可能，逻辑删除也要抢锁），
        //    要么 available_qty >= quantity 条件不满足（同一事务内刚校验过，不可能）。
        //    因此这里直接报库存不足，不做重试——重试属于 V2 的语义。
        int updatedRows = materialStockMapper.reduceAvailableQtyUnderLock(stock.getId(), quantity);
        if (updatedRows != 1) {
            log.error("[deductOneItem][锁内扣减未命中 reqNo={} stockId={} quantity={}]",
                    requisition.getReqNo(), stock.getId(), quantity);
            throw exception(STOCK_NOT_ENOUGH, available.toPlainString(), quantity.toPlainString());
        }
        BigDecimal balanceAfter = available.subtract(quantity);

        // ④ 写流水：biz_type=2 申领扣减、quantity 记负数、balance_after=扣减后余额、biz_no=req_no
        //    负号语义见 StockBizTypeEnum：对账一行 SUM(quantity) 即得净变动（spec §6.1 第 ⑦ 步）
        StockLogDO stockLog = StockLogDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .bizType(StockBizTypeEnum.REQUISITION_OUT.getBizType())
                .bizNo(requisition.getReqNo())
                .quantity(quantity.negate())
                .balanceAfter(balanceAfter)
                .remark("申领扣减：" + requisition.getReqNo())
                .build();
        stockLogMapper.insert(stockLog);

        log.debug("[deductOneItem][扣减成功 reqNo={} materialId={} qty={} balanceAfter={}]",
                requisition.getReqNo(), materialId, quantity, balanceAfter);
    }

    @Override
    public void cancel(RequisitionDO requisition, List<RequisitionItemDO> items) {
        // 取消 = 撤销申领：把已扣的数量按原明细回补，并写 biz_type=4 冲正流水。
        // 加锁顺序与 submit 一致（materialId 升序），保证「取消」与「新申领」并发时不产生死锁。
        List<RequisitionItemDO> orderedItems = items.stream()
                .sorted(Comparator.comparing(RequisitionItemDO::getMaterialId))
                .toList();

        for (RequisitionItemDO item : orderedItems) {
            revertOneItem(requisition, item);
        }
        log.info("[cancel][V1 行锁回补完成 reqNo={} projectId={} itemCount={}]",
                requisition.getReqNo(), requisition.getProjectId(), orderedItems.size());
    }

    /**
     * 单行明细的回补（与 {@link #deductOneItem} 完全对称的四步）
     *
     * @param requisition 申领单
     * @param item        明细行
     */
    private void revertOneItem(RequisitionDO requisition, RequisitionItemDO item) {
        Long projectId = requisition.getProjectId();
        Long materialId = item.getMaterialId();
        BigDecimal quantity = item.getQuantity();

        // ① 锁库存行（与扣减同一把锁：取消与新申领在热点行上自然串行）
        MaterialStockDO stock = materialStockMapper.selectByProjectIdAndMaterialIdForUpdate(projectId, materialId);
        if (stock == null) {
            // 取消时库存行不见了：说明库存在业务链路外被删除（管理动作），
            // 此时「回补」无处可补。抛错让整体回滚，由运维介入——静默跳过会造成
            // 「单据已取消但库存永久少一块」且无人知晓，是最难查的一类账不平。
            throw exception(STOCK_NOT_EXISTS);
        }

        // ② available_qty += qty（行锁下直接累加，无 CAS）
        int updatedRows = materialStockMapper.increaseAvailableQtyUnderLock(stock.getId(), quantity);
        if (updatedRows != 1) {
            throw exception(STOCK_NOT_EXISTS);
        }
        BigDecimal available = stock.getAvailableQty() == null ? BigDecimal.ZERO : stock.getAvailableQty();
        BigDecimal balanceAfter = available.add(quantity);

        // ③ 写冲正流水：biz_type=4、quantity 记正数（库存净增）
        //    备注里写明「取消冲正」以便与 P5 的「审批驳回冲正」在流水列表上可区分（biz_no 相同）
        StockLogDO stockLog = StockLogDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .bizType(StockBizTypeEnum.REVERSAL.getBizType())
                .bizNo(requisition.getReqNo())
                .quantity(quantity)
                .balanceAfter(balanceAfter)
                .remark("取消冲正：" + requisition.getReqNo())
                .build();
        stockLogMapper.insert(stockLog);

        log.debug("[revertOneItem][回补成功 reqNo={} materialId={} qty={} balanceAfter={}]",
                requisition.getReqNo(), materialId, quantity, balanceAfter);
    }

}
