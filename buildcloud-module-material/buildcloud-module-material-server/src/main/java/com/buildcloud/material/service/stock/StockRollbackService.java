package com.buildcloud.material.service.stock;

import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.service.stock.redis.RequisitionRollbackResult;

/**
 * 申领单**统一回滚组件**（spec §6.1 步骤⑤⑥：审批驳回 / 超时关单 / 取消 → 恢复库存 + 冲正流水）
 *
 * ========== 为什么必须有这么一个组件（本 Phase 的核心交付） ==========
 *
 * 「这张单不批了」有三种触发者：
 * <pre>
 *   ① 审批驳回      （W4-P5，人驱动：审批人点驳回）        → 已取消(9)
 *   ② 主动取消      （W4-P4，人驱动：申请人点取消）        → 已取消(9)
 *   ③ 超时未审完    （W5-P3，系统驱动：延迟消息到期）      → 已关闭(8)
 * </pre>
 *
 * 三者的**库存事实完全相同**：提交时已经扣过（V1 扣 DB）或预扣过（V2 扣 Redis），现在都要还回去，
 * 且都必须写一条 `biz_type=4` 的冲正流水。差别只在「谁触发」与「落哪个终态」。
 *
 * 如果各写一份，最典型的后果（本项目实际踩过的方向）：
 * - ① 写了冲正流水、③ 忘了写 → 对账 `SUM(quantity)` 与 `available_qty` 对不上，且**只有超时的那批单不平**；
 * - ① 用「快照明细」、③ 用「库里明细」→ 改单后两处回补量不一致；
 * - ② 只删了 Redis 预扣没回补 DB（W5-P2 的 V2 cancel 当时就是这么留的口子）。
 *
 * 所以本组件是**唯一实现**：三条路径都调它，谁调谁只是「决定终态 + 做 CAS」，
 * 库存与流水的动作只有这一份代码。单测用 Mockito `verify` 钉住「两条路径都调到了本组件的同一个方法」。
 *
 * ========== 与 V1 的关系（**不改 V1**，但复用它的行锁实现） ==========
 *
 * AGENTS 约束「不改 V1 代码」，V1 的「撤销申领」实现是
 * {@code RequisitionV1SubmitHandler#cancel}（按 materialId 升序 `SELECT ... FOR UPDATE` 锁行
 * → `available_qty += qty` → 写 `biz_type=4` 流水）。
 * 本组件**在调用方事务内复用这段实现**，而不是重写一遍回补逻辑——
 * 重写就会出现「两份冲正口径」（备注文案、流水符号、balance_after 算法各一套），
 * 正是任务书里"不是两份实现"要防的东西。
 *
 * 也就是说：V1 的行锁代码**一行没改**，但"回补库存 + 写冲正流水"这条路径从此只有本组件一个**入口**。
 *
 * ========== 事务边界（调用方责任，写在这里防止误用） ==========
 *
 * 本类的方法**不开事务**：它必须在调用方的事务里执行，原因有两个
 * 1. 回补（行锁 UPDATE）与冲正流水必须与「单据终态 CAS」同成同败——
 *    若本类自己开事务，关单 CAS 成功而回补失败就会留下「单已关闭、库存没还」的黑洞；
 * 2. 行锁 `SELECT ... FOR UPDATE` 在无事务上下文里会在语句结束即释放锁（等于没锁）。
 *
 * 对应地，调用方必须已经**用 CAS 拿到「这次是我该处理」的结论**再调本组件（见各调用点注释）；
 * 本组件自己再做一次「本单是否还有 DB 扣减未冲正」的判据（流水存在性），形成第二道幂等。
 *
 * @author buildcloud
 */
public interface StockRollbackService {

    /**
     * **申领单回滚**：按单据明细回补 DB 可用量 + 写冲正流水 + 还原 Redis 预扣（幂等）
     *
     * 单行处理顺序（与 V1 的扣减/回补加锁顺序一致，避免与新申领形成 AB-BA 死锁）：
     * <pre>
     *   按 materialId 升序，逐行：
     *     ① 幂等判据：本行是否已有 biz_type=4 冲正流水？有 → 跳过（本次不重复回补）
     *     ② 本行是否真的扣过 DB？没有 biz_type=2 扣减流水 → 跳过（V2 在途预扣 / 已被别处回补）
     *     ③ DB：SELECT ... FOR UPDATE 锁库存行 → available_qty += quantity → 写 biz_type=4 流水
     *     ④ Redis：StockPredeductService#rollback（明细存在才还；不存在返回 NOT_FOUND，属正常）
     * </pre>
     *
     * @param requisitionId 申领单编号（明细从这里取：**以库为准**，不信任调用方的内存快照）
     * @param reqNo         申领单号（流水 biz_no 与 Redis 预扣明细键）
     * @param reason        回滚原因（写进流水备注；如「审批驳回」「超时关单」），可为 null
     * @return 回滚结果（回补行数/数量、Redis 还原计数、是否完全回补）
     */
    RequisitionRollbackResult rollbackForRequisition(Long requisitionId, String reqNo, String reason);

    /**
     * 同 {@link #rollbackForRequisition(Long, String, String)}，但允许调用方传入已读到的单据与明细快照
     *
     * 为什么保留这个重载：超时关单路径在 CAS 前已经读过单据与明细，再查一次库是多余的往返；
     * 但**明细仍以传参为准做校验**（调用方传空则回退成查库），避免调用方漏传导致"什么都不回补"。
     *
     * @param requisition 申领单（至少要有 id / reqNo / projectId）
     * @param items       明细快照（可为 null / 空：此时从库重查）
     * @param reason      回滚原因
     * @return 回滚结果
     */
    RequisitionRollbackResult rollbackForRequisition(RequisitionDO requisition,
                                                     java.util.List<RequisitionItemDO> items, String reason);

}
