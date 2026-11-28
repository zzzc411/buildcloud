package com.buildcloud.material.service.stock.reconcile;

/**
 * 库存对账 Service（W5-P4，spec v1.2 §6.1 步骤⑦）
 *
 * <pre>
 *   ⑦ 对账兜底：XXL-Job 定时比对 Redis 预扣 vs DB 流水 → 差异告警（站内信）+ 修复
 * </pre>
 *
 * ========== 为什么以 DB 为准（这是本组件最重要的一条设计判断） ==========
 *
 * 两侧数据的性质完全不同：
 *
 * | | `bc_material_stock.available_qty`（DB） | `pm:stock:{pid}:{mid}`（Redis） |
 * | --- | --- | --- |
 * | 权威性 | **唯一事实来源**：扣减/回补都在本地事务里、有行锁或条件更新保护 | 派生缓存 |
 * | 持久性 | 持久化、有 binlog、可回放 | 内存；重启/AOF 载入失败/被清 = 归零或丢失 |
 * | 可审计 | 每一笔变动都有 `bc_stock_log` 流水，`SUM(quantity) == available_qty` 可自证 | 只有"当前值"，历史无从追溯 |
 * | 并发保护 | InnoDB 行锁 / `available_qty >= qty` 条件更新（**防超卖最后一道实时防线**） | Lua 原子性只保证脚本内不交错，不保证"该扣的都扣了" |
 * | 出问题时的方向 | 若以 Redis 为准写 DB → **可能凭空增加库存 = 超卖方向** | 若以 DB 为准写 Redis → 只是少卖（预扣更易被拒），用户可重试 |
 *
 * 结论：**任何"Redis 与 DB 不一致"的场景，一律以 DB 校正 Redis**。
 * 唯一例外是 D1/单据仍在途——那里 Redis 那一笔代表"一张真实存在、状态为已提交的单"，
 * 不能简单当作脏数据删掉，而要按单据状态决定「补齐 DB 扣减」还是「回滚 Redis」。
 * 注意即便在 D1 的补扣分支，写 DB 的依据也是**库里的明细数量**（`bc_requisition_item.quantity`），
 * 仍然以 DB 为准；Redis 只是告诉我们"这笔预扣发生在哪张单上"。
 *
 * ========== 三类差异与修复策略 ==========
 *
 * <pre>
 *   D1 Redis 有预扣、DB 无扣减流水（reqNo 在 Redis 明细里，bc_stock_log 无 biz_type=2 行）
 *      单据已取消(9)/已关闭(8) → **回滚 Redis**（只还 Redis，DB 一行不动，不写冲正流水）
 *      单据仍在途(1)/已扣减(2)…等 → **补扣减**（DB 条件扣减 + 写 biz_type=2 流水 + 状态 CAS 1→2）
 *      单据不存在/草稿(0)/明细缺失 → **不修，只告警**（无从判断该扣多少，人工介入）
 *
 *   D2 DB 有扣减流水、Redis 无预扣（bc_stock_log 有 biz_type=2 行，Redis 明细里没有该 reqNo）
 *      → **删除脏流水 + 回补 DB**（`quantity` 取流水的绝对值，逻辑删除原文、**不写冲正流水**）
 *      → 删除负数流水（SUM 增一个 qty）与回补（available 增一个 qty）互相抵消，再写正数冲正会重复记账
 *      → 回补完**同一轮内**把 Redis 对齐到新的 DB 值（否则本维度必然差出一个 qty，而它已被排除出 D3 判定）
 *      → 选择"删流水"而不是"补 Redis 明细"的理由见 {@code StockReconcileServiceImpl} 的 D2 注释
 *
 *   D3 Redis 可用量 != DB 可用量
 *      → **以 DB 为准校正 Redis**（`initAvailable(RESET, DB.available_qty)` + 按明细重建在途预扣）
 *      → 放在 D1/D2 之后执行（D3 往往是它们的衍生结果，先修前者再对总账，否则会重复修复）
 * </pre>
 *
 * ========== 幂等（能连跑）的保证来自哪里 ==========
 *
 * **不靠"修过就不修"的记忆，而靠"看现状"**：每一类修复前都重新读当前的 Redis 与 DB 状态，
 * 已经对齐的维度直接跳过。因此：
 * - 同一批差异连跑两次对账 → 第二次一切已对齐 → diff = 0；
 * - 修复中途进程被杀 → 下一次对账看到的是"半修"的现状，继续把它修完；
 * - 不需要额外的"已修复"标记表（那种标记迟早会与实际状态不同步，成为第三份真相）。
 *
 * @author buildcloud
 */
public interface StockReconcileService {

    /**
     * 执行一轮对账（检测 + 修复 + 落库快照 + 告警）
     *
     * @param shardIndex 分片序号（0..shardTotal-1；单机手动触发传 0）
     * @param shardTotal 分片总数（单机手动触发传 1）
     * @return 对账结果（含三类差异的检出/修复统计与明细）
     */
    ReconcileResult reconcile(int shardIndex, int shardTotal);

    /**
     * 执行一轮对账（单机全量语义的快捷方法，等价于 {@code reconcile(0, 1)}）
     *
     * @return 对账结果
     */
    default ReconcileResult reconcile() {
        return reconcile(0, 1);
    }

}
