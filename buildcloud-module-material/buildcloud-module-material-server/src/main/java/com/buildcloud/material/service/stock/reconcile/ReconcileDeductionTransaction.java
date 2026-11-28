package com.buildcloud.material.service.stock.reconcile;

import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.dal.mysql.stock.MaterialStockMapper;
import com.buildcloud.material.dal.mysql.stock.StockLogMapper;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 对账修复的**事务承载组件**（D1 补扣减 / D2 删脏流水 + 回补）
 *
 * ========== 为什么必须单独成 bean（不是"为了好看"） ==========
 *
 * `@Transactional` 靠 Spring AOP 代理生效，**同一个类内部 `this.xxx()` 调用不经过代理**，
 * 事务会静默失效——代码里明明有注解，实际每条 SQL 各自自动提交，"整笔原子"变成空话。
 * 这个坑在 W5-P2 已经踩过并写进 {@code RequisitionDeductTransactionService} 的类注释，
 * 这里用**同样的结构**（独立 bean + 调用方注入代理对象）从结构上消除它，而不是靠读者记得。
 *
 * ========== 与消费者的关系：同一套动作、同一套判据 ==========
 *
 * {@link #deduct} 与 {@code RequisitionDeductTransactionService#deductOneItem} 是**等价**的：
 * 行级流水判据 → DB 条件扣减 → 写 biz_type=2 流水 → 状态 CAS 1→2。
 * 之所以不直接调用消费者那边的实现：那个方法的幂等闸门读的是**消息里的状态**（消息快照），
 * 而对账拿到的是**库里的当前状态**；两者的入口契约不同（一个收消息、一个收库内行），
 * 强行复用会让那个类背两个来源的契约。这里复制的是**判据的顺序**，不是代码。
 * 保持"两侧判据一致"由单测钉（见 {@code StockReconcileServiceTest} 的幂等用例）。
 *
 * ========== 报错策略：失败一律回滚（不吞异常） ==========
 *
 * 与消费者的"抛异常 → MQ 重投"不同，这里的失败**不回抛到对账主流程**：
 * 本类方法返回 boolean（false = 本次未生效），由调用方把它记成"未修复"进快照与告警。
 * 理由：对账是批量任务，一条失败不该中断一轮；而"未修复"必须显式出现在结果里，
 * 所以**不能吞掉异常假装成功**——返回 false 与抛异常的区别只是"谁来终止这次循环"。
 *
 * @author buildcloud
 */
@Service
@Slf4j
public class ReconcileDeductionTransaction {

    @Resource
    private MaterialStockMapper materialStockMapper;

    @Resource
    private StockLogMapper stockLogMapper;

    /**
     * **D1 补扣减**：DB 条件扣减 + 写 biz_type=2 流水 + 状态 CAS（1→2）
     *
     * 幂等三道（顺序与消费者一致，缺一不可）：
     * 1. 行级流水判据：本单本物料已有 biz_type=2 流水 → 已扣过，返回 true（视为已对齐）；
     * 2. `available_qty >= qty` 条件扣减：影响 0 行 = Redis 与 DB 已漂移 → 返回 false（不修，留给人工）；
     * 3. 状态 CAS `WHERE status = 1`：影响 0 行 = 单据已被别人推进/终止 → 抛异常回滚本次扣减。
     *
     * @param requisitionId 申领单编号（状态 CAS 用）
     * @param projectId     项目编号
     * @param materialId    物料编号
     * @param reqNo         申领单号（流水 biz_no）
     * @param quantity      扣减量（> 0，调用方保证取自库内明细）
     * @return true = 已扣（含"本来就扣过"）；false = 未生效（DB 条件扣减 0 行或状态 CAS 0 行）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deduct(Long requisitionId, Long projectId, Long materialId, String reqNo,
                          BigDecimal quantity) {
        if (requisitionId == null || projectId == null || materialId == null
                || reqNo == null || reqNo.isBlank() || quantity == null || quantity.signum() <= 0) {
            log.error("[deduct][补扣减入参非法，拒绝执行 requisitionId={} projectId={} materialId={} reqNo={} qty={}]",
                    requisitionId, projectId, materialId, reqNo, quantity);
            return false;
        }

        // ① 行级幂等：已有扣减流水 → 已扣过（与消费者同一判据）
        Long existing = stockLogMapper.selectCountByBizNoAndType(projectId, materialId, reqNo,
                StockBizTypeEnum.REQUISITION_OUT.getBizType());
        if (existing != null && existing > 0) {
            log.info("[deduct][幂等：本行已有 {} 条扣减流水，跳过补扣 reqNo={} materialId={}]",
                    existing, reqNo, materialId);
            return true;
        }

        // ② DB 条件扣减（防超卖最后一道实时防线）
        int rows = materialStockMapper.reduceAvailableQtyByBusinessKey(projectId, materialId, quantity);
        if (rows != 1) {
            // 影响 0 行：库存行不存在，或 available_qty < qty（账已经严重不平）。
            // 不能"尽力而为"地扣到 0 或负数——那会制造一个更难看懂的账。
            log.error("[deduct][DB 条件扣减影响 0 行（可用量不足或库存行不存在），不补扣 reqNo={} "
                    + "projectId={} materialId={} qty={}]", reqNo, projectId, materialId, quantity);
            return false;
        }

        // ③ 写流水：quantity 取负、balance_after 重读（并发下"读+算"会拿到被别人插队的值）
        MaterialStockDO after = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        BigDecimal balanceAfter = after == null || after.getAvailableQty() == null
                ? BigDecimal.ZERO : after.getAvailableQty();
        stockLogMapper.insert(StockLogDO.builder()
                .projectId(projectId)
                .materialId(materialId)
                .bizType(StockBizTypeEnum.REQUISITION_OUT.getBizType())
                .bizNo(reqNo)
                .quantity(quantity.negate())
                .balanceAfter(balanceAfter)
                .remark("对账补扣减（D1）：" + reqNo)
                .build());

        // ④ 状态 CAS：已提交(1) → 已扣减(2)
        //    影响 0 行 = 单据已不是已提交（被取消/关闭/或已被并发推进到 2）：
        //    此时本事务必须**整体回滚**，否则会出现"单子已取消、库存却被扣了"。
        int cas = requisitionMapperDeductCas(requisitionId);
        if (cas != 1) {
            log.error("[deduct][状态 CAS 未命中（单据已不是已提交1），回滚本次补扣 requisitionId={} reqNo={}]",
                    requisitionId, reqNo);
            throw new IllegalStateException("补扣减时单据状态已变化，本次补扣回滚：requisitionId=" + requisitionId
                    + ", reqNo=" + reqNo);
        }

        log.info("[deduct][对账补扣减成功 reqNo={} projectId={} materialId={} qty={} balanceAfter={}]",
                reqNo, projectId, materialId, quantity, balanceAfter);
        return true;
    }

    /**
     * **D2 删脏流水 + 回补 DB**（同一本地事务）
     *
     * 步骤：
     * 1. 逻辑删除被判定为"多余"的 biz_type=2 流水（`deleted = 0` 条件，影响 0 行 = 已被并发处理）；
     * 2. `available_qty += qty` 回补。
     * **就这两步**——不要"顺手"再写一条 biz_type=4 冲正流水（历史实现写了，账当场不平，见下）。
     *
     * ========== 不变量 `SUM(quantity) == available_qty` 必须**在这一步之内**闭合（W5-P4 实测踩坑） ==========
     *
     * 直觉上"删掉一笔 -30 的流水（SUM 少 30）+ 回补 30（available 多 30）+ 写一笔 +30 的冲正（SUM 多 30）"
     * 应该是平的。但把三个动作按**符号**写出来就露馅了：
     *
     * <pre>
     *   初始：           available = 70,  SUM = 100（+100 入库、-30 脏扣减）
     *   ① 删 -30：       available = 70,  SUM = 100 - (-30) = 130   ← SUM 反而变大了
     *   ② available+30： available = 100, SUM = 130
     *   ③ 写 +30 冲正：  available = 100, SUM = 160                 ← 越修越不平
     * </pre>
     *
     * 原因：逻辑删除掉的是**一条负数流水**，把它移出 SUM 等价于给 SUM **加** 30；
     * 而"回补"在 DB 侧也是加 30。两个 +30 已经互相抵消完，再写一笔 +30 的冲正就多出了一份。
     *
     * 所以**第 3 步不该存在**：回补动作本身已经由 available_qty 的变化记账，
     * 它在流水层面的等价物就是"被删掉的那笔负数"，把负数删掉即等于冲正。
     * 正确的两段式是「删掉脏流水 + available_qty 加回」，SUM 与 available 同时回到 100。
     *
     * 那为什么还要保留冲正这个 biz_type？——**给真正需要"写一笔正数"的场景用**
     * （V1 取消/驳回的冲正：DB 侧 `available += qty` 的同时必须留下一条正数流水）。
     * 本方法不属于那个场景：它的"正数"是"删掉一个负数"制造的，再补一笔就是重复记账。
     *
     * **教训的一般化**：动流水表之后，"账平不平"不能靠直觉或"三笔互为逆运算"的叙述，
     * 必须**把每一步代进一个具体数字算一遍**（本注释顶部的四行演算就是那个过程）。
     *
     * ========== 为什么步骤 1 用逻辑删除而不是物理删除 ==========
     * 原始行是"这笔扣减确实发生过"的唯一证据，事故复盘/人工复核要看它。逻辑删除后它不再参与 SUM
     * （注解 SQL 显式 `deleted = 0`），但仍在表里可查。物理删除等于抹掉证据——这正是对账最不该做的事。
     *
     * @param logId      被删除的扣减流水 id（条件删除的 CAS 期望值）
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param reqNo      业务单号（写进日志便于排查）
     * @param quantity   回补量（= 被删流水的绝对值，> 0）
     * @return true = 已处理（含"流水已被并发删除"的幂等情形）；false = 库存行不存在无法回补
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean reverseDirtyDeduction(Long logId, Long projectId, Long materialId,
                                         String reqNo, BigDecimal quantity) {
        if (logId == null || projectId == null || materialId == null
                || quantity == null || quantity.signum() <= 0) {
            log.error("[reverseDirtyDeduction][入参非法 logId={} projectId={} materialId={} qty={}]",
                    logId, projectId, materialId, quantity);
            return false;
        }

        // ① 条件逻辑删除（CAS）：只有仍在（deleted=0）才删，影响 0 行说明已被并发处理
        int deleted = stockLogMapper.deleteById(logId);
        if (deleted != 1) {
            log.info("[reverseDirtyDeduction][扣减流水已被并发删除/不存在，视为已对齐 logId={} reqNo={}]",
                    logId, reqNo);
            return true;
        }

        // ② 回补 DB（行不存在则无法回补 → 整体回滚，把刚才的删除也撤掉）
        int rows = materialStockMapper.increaseAvailableQtyUnderLockForReconcile(projectId, materialId, quantity);
        if (rows != 1) {
            log.error("[reverseDirtyDeduction][库存行不存在，无法回补，回滚本次删除 logId={} projectId={} materialId={}]",
                    logId, projectId, materialId);
            throw new IllegalStateException("D2 回补失败：库存行不存在 projectId=" + projectId
                    + ", materialId=" + materialId);
        }

        // ③ **不再写 biz_type=4 冲正流水**：删掉一笔负数流水（SUM += qty）与 DB 回补（available += qty）
        //    已经互相抵消，再写一笔 +qty 会让 SUM 比 available 多出一个 qty（详见方法注释的演算）。
        MaterialStockDO after = materialStockMapper.selectByProjectIdAndMaterialId(projectId, materialId);
        log.info("[reverseDirtyDeduction][D2 修复完成 logId={} reqNo={} projectId={} materialId={} "
                + "qty={} availableAfter={}]", logId, reqNo, projectId, materialId, quantity,
                after == null ? null : after.getAvailableQty());
        return true;
    }

    /**
     * 注入 RequisitionMapper（**延迟到方法里取**，避免与 Mapper 的循环依赖风险）
     *
     * 为什么不在类字段直接 `@Resource`：本类只被对账链路使用，而 {@code RequisitionMapper}
     * 是 material 域最核心的 Mapper；写成字段注入时，任何"对账 Service ↔ 申领 Service"
     * 的 Bean 关系变化都会在这里表现为启动期循环依赖（基座已开 allow-circular-references，
     * 会让问题**静默**通过而不是报错——更难查）。方法内取一次不影响可测性（单测照样注入）。
     */
    @Resource
    private com.buildcloud.material.dal.mysql.requisition.RequisitionMapper requisitionMapper;

    /**
     * 状态 CAS 的薄封装（调用 {@code RequisitionMapper#deductCas}）
     *
     * 抽成方法只为在调用点保留"这条 SQL 就是整单闸门"的注释位置——
     * 直接内联的话，那段说明会跑到 if 语句里，日后很容易被重构掉。
     *
     * @param requisitionId 申领单编号
     * @return 影响行数（1 = 本次首次推进成功）
     */
    private int requisitionMapperDeductCas(Long requisitionId) {
        return requisitionMapper.deductCas(requisitionId);
    }

}
