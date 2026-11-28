package com.buildcloud.material.service.stock;

import com.buildcloud.material.service.stock.redis.StockPredeductResult;
import com.buildcloud.material.service.stock.redis.StockRollbackResult;

import java.math.BigDecimal;

/**
 * Redis 预扣 Service（W5 高并发 V2 的基础组件，spec v1.2 §6.1 步骤③）
 *
 * ========== 与 V1 的关系（隔离，不是替代） ==========
 *
 * V1 是 {@code RequisitionV1SubmitHandler}：`SELECT ... FOR UPDATE` 行锁同步扣减，
 * spec §6.1 明确「**这是压测对照组，不是要保留的方案**」。本组件是 V2 的第一道防线，
 * **不修改 V1 的任何代码**，两者由 {@code RequisitionModeEnum} 开关分派，
 * 以便 W5 的 V1/V2 压测对照成立（动了 V1 就没有对照基线了）。
 *
 * ========== 本 Phase（W5-P1）的边界 ==========
 *
 * 只交付「Lua 组件」本身：脚本 + 封装 + 单测。**不接入任何业务链路**：
 * - 入库时同步写 Redis（{@link #initAvailable}）的方法已提供，但 {@code StockServiceImpl#inbound}
 *   在本 Phase **不调用**它（是否接入由 W5-P2 事务消息链路一并决定，避免半接入状态下的
 *   双写不一致）；
 * - DB 的真实扣减 / 流水 / 状态推进由 W5-P2 的 MQ 消费者完成，本组件**不做 DB 操作**。
 *
 * ========== 术语口径 ==========
 *
 * 「可用量」在 Redis 里是 `pm:stock:{projectId}:{materialId}` 的一个数，语义 = DB 的
 * `bc_material_stock.available_qty` **减去所有未落库的在途预扣**。因此：
 * <pre>
 *   Redis.available = DB.available_qty - Σ(在途预扣明细)
 * </pre>
 * 这是对账 Job（W5-P4）的核对公式，也是本组件 {@link #queryPrededuct} 提供明细查询的原因。
 *
 * @author buildcloud
 */
public interface StockPredeductService {

    /**
     * 初始化 / 重置 Redis 可用量（**入库时**调用；本 Phase 只提供方法，不接入入库流程）
     *
     * 两种语义，由 mode 决定：
     * - {@link InitMode#RESET}：用 qty 直接覆盖，并**清空**该「项目+物料」的预扣明细。
     *   用于：首笔入库（缓存里没有该 key）、缓存重建（对账 Job 从 DB 重载）；
     * - {@link InitMode#ACCUMULATE}：在现有值上累加 qty，**保留**明细。
     *   用于：日常追加入库（DB 侧 available_qty += qty，Redis 同步同幅累加，
     *   在途预扣不受影响）。
     *
     * 为什么日常入库不能一律用 RESET：RESET 会把在途单据的预扣明细删掉，
     * 那些单据再次提交时会命中"幂等"（返回已预扣）而实际无扣减记录，账立刻不平。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param qty        数量（>= 0，RESET 时是基线值、ACCUMULATE 时是增量）
     * @param mode       初始化模式
     */
    void initAvailable(Long projectId, Long materialId, BigDecimal qty, InitMode mode);

    /**
     * 初始化 / 重置 Redis 可用量（RESET 语义的快捷方法）
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param qty        基线可用量
     */
    default void initAvailable(Long projectId, Long materialId, BigDecimal qty) {
        initAvailable(projectId, materialId, qty, InitMode.RESET);
    }

    /**
     * **原子预扣**（spec §6.1 步骤③：available >= qty ? decr + 记明细 : 返回失败）
     *
     * 整个「判重 → 校验 → 扣减 → 记账」在 Redis 单线程内一次完成（Lua），
     * 并发下不存在交错，因此**不会超卖**。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param reqNo      申领单号（幂等键）
     * @param qty        预扣数量（> 0）
     * @return 结果枚举：SUCCESS / NOT_ENOUGH / NEED_INIT / IDEMPOTENT（含义见 {@link StockPredeductResult}）
     */
    StockPredeductResult tryPrededuct(Long projectId, Long materialId, String reqNo, BigDecimal qty);

    /**
     * 回滚预扣（驳回 / 超时关单 / 本地事务失败时调用）
     *
     * 语义：明细存在 → INCRBY 还原 + HDEL 明细；明细不存在 → 无副作用返回 NOT_FOUND。
     * 数量以**明细里记录的值**为准做一致性校验（不信任入参），不一致时拒绝改动。
     *
     * @param reqNo      申领单号
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param qty        期望数量（与预扣时一致；不一致返回 QTY_MISMATCH）
     * @return 回滚结果
     */
    StockRollbackResult rollback(String reqNo, Long projectId, Long materialId, BigDecimal qty);

    /**
     * 查询 Redis 中的可用量
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 可用量；key 不存在返回 {@code null}（**不是 0**——0 是合法的"没库存"，
     *         null 才是"未初始化"，两者对调用方的处置完全不同）
     */
    BigDecimal queryAvailable(Long projectId, Long materialId);

    /**
     * 查询某张申领单在某「项目+物料」上的预扣数量（对账用）
     *
     * @param reqNo      申领单号
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 预扣数量；未预扣返回 {@code null}
     */
    BigDecimal queryPrededuct(String reqNo, Long projectId, Long materialId);

    /**
     * 查询某「项目+物料」的全部在途预扣明细（对账 Job 用：与 DB 流水逐笔比对）
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return reqNo → 数量（按 reqNo 字典序；无明细返回空 Map，不返回 null）
     */
    java.util.Map<String, BigDecimal> queryPredeductDetails(Long projectId, Long materialId);

    /**
     * 初始化模式
     */
    enum InitMode {

        /**
         * 覆盖基线并清空明细（首笔入库 / 缓存重建）
         */
        RESET,

        /**
         * 在现有值上累加，保留明细（日常追加入库）
         */
        ACCUMULATE

    }

}
