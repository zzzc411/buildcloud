package com.buildcloud.material.service.stock.redis;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 申领单**统一回滚**（{@code StockRollbackService#rollbackForRequisition}）的执行结果
 *
 * ========== 为什么不让回滚方法返回 void ==========
 *
 * 回滚有两个调用方（审批驳回、超时关单），二者的**事务边界与幂等前提不同**：
 * - 超时关单：先在事务外「预检 + CAS 关单」，再调回滚；此时回滚必须是幂等的，
 *   且"该不该回补"取决于**单据当时有没有动过库存**，而不是调用方以为的样子；
 * - 审批驳回：CAS 之后回滚，若明细行数/素材快照对不上，需要把差异带出来。
 *
 * 返回"回补了几行、回补多少量、该单在本项目下是否仍有库存占用"能让调用方
 * **用自己的话把结论写进日志与单据备注**，而不是靠翻两处日志拼事实。
 *
 * 特别地 {@link #isAcquired()} 区分了两种"没做任何写入"的情形：
 * - {@link #SKIPPED_NO_STOCK_IMPACT}：这部单据压根没扣过 DB（V2 预扣在途/已被别的路径回补）
 *   → 这是**正常**的幂等命中，调用方继续推进状态即可；
 * - {@link #PARTIAL_RESTORED}：还有物料被本单占用（如 V2 预扣仍在 Redis 里）
 *   → 这是**需要人看见**的残留，调用方必须保留错误日志。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public final class RequisitionRollbackResult {

    /**
     * 执行结果类型
     */
    public enum Type {

        /**
         * 本次真正回补了 DB（至少一行），并写了 {@code biz_type=4} 冲正流水
         */
        RESTORED,

        /**
         * 本单没有需要回补的 DB 扣减（明细为空 / 本单从未扣过 DB），未写任何流水
         */
        SKIPPED_NO_STOCK_IMPACT,

        /**
         * 已结算但未动 DB：本单**从未扣过 DB**（V2 的 Redis 预扣在途、或本来就扣减过），
         * 且 Redis 预扣已还原（或本来就没预扣过）→ 该单对库存的占用为 0，关闭它是安全的。
         *
         * 与 {@link #RESTORED} 的差别只在"这次有没有动 DB"；对调用方（确定终态）的意义相同。
         * 与 {@link #PARTIAL_RESTORED} 的差别是本质的：后者**还有预扣没还回去**，关单会造成
         * "Redis 里永久少一块、DB 却全须全尾"的账不平。
         */
        SETTLED_AT_REDIS_ONLY,

        /**
         * 只有部分物料回补成功：剩余物料仍被本单占用（Redis 预扣未还 / 明细被并发改动），
         * 调用方必须告警，不能当作"回滚完成"
         */
        PARTIAL_RESTORED

    }

    private final Type type;

    /**
     * 回补成功的明细行数
     */
    private final int restoredItemCount;

    /**
     * 回补的 DB 可用量合计（= 各回补行 quantity 之和）
     */
    private final BigDecimal restoredQuantity;

    /**
     * Redis 预扣被还原的行数（{@link StockRollbackResult#ROLLED_BACK} 的条数）
     */
    private final int redisRolledBackCount;

    /**
     * Redis 侧无预扣记录的行数（{@link StockRollbackResult#NOT_FOUND}，属于正常幂等）
     *
     * Redis 侧的另外两种结果（NEED_INIT / QTY_MISMATCH）不会出现在这里：
     * `StockPredeductService#rollback` 对它们是**抛业务异常**，整个回滚事务直接回滚。
     */
    private final int redisNotFoundCount;

    /**
     * 该单是否还占用着库存（决定能不能安全地推进到终态）
     *
     * <p>只有 {@link Type#PARTIAL_RESTORED} 是"还占着"：它意味着至少一行明细的 DB 扣减/Redis 预扣
     * 都没能收回。其余三种都表示<span>本单对库存的净影响已归零</span>，可以安全关单/取消。</p>
     *
     * @return true = 已经没有任何库存被本单占用
     */
    public boolean isAcquired() {
        return type != Type.PARTIAL_RESTORED;
    }

    @Override
    public String toString() {
        return "RequisitionRollbackResult{type=" + type
                + ", restoredItemCount=" + restoredItemCount
                + ", restoredQuantity=" + restoredQuantity.toPlainString()
                + ", redisRolledBack=" + redisRolledBackCount
                + ", redisNotFound=" + redisNotFoundCount + '}';
    }

}
