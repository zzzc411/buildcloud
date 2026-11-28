package com.buildcloud.material.service.requisition;

import com.buildcloud.material.controller.admin.requisition.vo.RequisitionApprovalStatusRespVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionReturnReqVO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;

import java.util.List;

/**
 * 材料申领审批 / 出库 / 退料 Service 接口（W4-P5）
 *
 * ========== 一、审批流与 V1 扣减的衔接（**本接口最重要的口径，改代码前必读**） ==========
 *
 * spec §5.2 状态机把「提交」与「审批通过」拆成两步：
 * <pre>
 * 草稿(0) ──提交──▶ 已提交(1，已预扣) ──审批通过──▶ 已扣减(2) ──出库──▶ 已出库(3)
 *                        ├──驳回──▶ 已取消(9) + 回滚预扣 + 冲正流水
 *                        └──超时──▶ 已关闭(8) + 回滚预扣 + 冲正流水
 * </pre>
 * 而 W4-P4 交付的 V1 同步链路（{@link RequisitionService#submitRequisition}）是**一步到位**的：
 * 草稿 →（同一事务内）→ 已扣减(2)，中间态「已提交(1)」只存在于事务内、不落库。
 *
 * **本接口走的是另一条路径**，两条路径的库存动作如下表（这张表是「审批通过绝不重复扣库存」的书面依据）：
 *
 * | 时点 | V1 同步链路（submit） | 审批链路（approval/submit 起） |
 * | --- | --- | --- |
 * | 建单 | 不动库存 | 不动库存 |
 * | 提交 | **扣库存**（行锁）→ 2 | **扣库存**（复用 V1 的行锁逻辑）→ 1（已提交/已预扣） |
 * | 审批通过 | 无（已结束） | **不动库存**，仅 1→2 |
 * | 审批驳回 | 无 | **回补库存**（available += qty）+ biz_type=4 冲正 → 9 |
 * | 出库 | 2→3，不动库存 | 2→3，不动库存 |
 * | 退料 | 3/5 → 5/4，**回补库存** + biz_type=3 退回 | 同左 |
 *
 * 一句话：**库存在「提交」时扣、在「驳回」或「退料」时补；审批通过、出库都不碰库存。**
 * 为什么审批通过不重复扣：V1 的扣减已经发生（在 approval/submit 里），审批通过只是
 * 「给已扣减的单据放行」，再扣一次就是双倍扣减——这是本 Phase 最容易写错、也最贵的一个 bug
 * （单测 {@code testHandleApprovalResult_approve_stockUnchanged} 专门钉住它）。
 *
 * ========== 二、为什么审批链路的「提交」也要扣库存（而不是等审批通过再扣） ==========
 *
 * spec §5.2 的「已提交(已预扣)」明确写了**已预扣**：集中申领的目的是「锁定库存」——
 * 若等审批通过才扣，同一批料在审批期间会被别的申领单抢走，审批通过时无料可发，
 * 也就是「审批通过却发不出货」。先扣后审批是「宁可占用库存，也要保证批下来就有料」的业务取舍。
 * 代价是驳回/超时必须**可靠地回补**（本接口的驳回路径 + W5 的超时关单）。
 *
 * ========== 三、事务与并发（与 V1 同一套约定） ==========
 *
 * 所有写方法各自一个本地事务（`@Transactional(rollbackFor = Exception.class)`），
 * 加锁顺序统一为：**先锁单据行（CAS UPDATE）→ 再按 materialId 升序锁库存行**，
 * 与 {@link RequisitionService} 的 submit/cancel 完全一致，避免 AB-BA 死锁。
 *
 * @author buildcloud
 */
public interface RequisitionApprovalService {

    /**
     * 提交申领审批：**扣减库存（复用 V1 行锁逻辑）→ 状态→已提交(1) → 发起会签流程**
     *
     * 同一事务内依次：
     * 1. 校验：单据存在、状态=草稿(0)、没有审批中的流程、审批人列表合法（1..{@link com.buildcloud.material.controller.admin.requisition.vo.RequisitionApprovalSubmitReqVO#MAX_APPROVERS} 人）；
     * 2. CAS 推进 草稿(0) → 已提交(1)，同时落 `approval_status=1(审批中)`、`approvers`、`timeout_time`；
     * 3. **复用 {@link com.buildcloud.material.service.requisition.strategy.RequisitionV1SubmitHandler}**
     *    的行锁扣减（`SELECT ... FOR UPDATE` → 校验 → `available_qty -= qty` → 写 biz_type=2 流水），
     *    与 V1 的 submit 是**同一段代码**，不另写一份扣减逻辑（两处口径必然分叉）；
     * 4. Feign 发起 `requisition-approve` 流程，流程变量含 businessKey=申领单 id、reqNo、
     *    明细摘要 items、会签审批人 approvers；
     * 5. 回写 `process_instance_id`。
     *
     * 任一失败整体回滚：库存不减少、流水不产生、单据回到草稿、不会留下「审批中但没有流程」的死状态。
     *
     * @param requisitionId   申领单编号
     * @param approverUserIds 会签审批人用户编号列表（全部通过才算通过）
     * @return 流程实例编号
     */
    String submitApproval(Long requisitionId, List<Long> approverUserIds);

    /**
     * 获得申领审批状态（含业务状态与明细退料进度）
     *
     * @param requisitionId 申领单编号
     * @return 审批状态 VO
     */
    RequisitionApprovalStatusRespVO getApprovalStatus(Long requisitionId);

    /**
     * 处理审批结果回调（**幂等**，重复回调不重复回补）
     *
     * - {@code APPROVE(2)}：状态 已提交(1) → 已扣减(2)，**库存不动**（提交时已扣）；
     * - {@code REJECT(3)}：状态 已提交(1) → 已取消(9) + **回补库存** + 写 biz_type=4 冲正流水；
     * - {@code CANCEL(4)}：与驳回同路（发起人撤回/管理员终止都意味着「这张单不批了」，库存必须回补）。
     *
     * 幂等三件套（与 W3 的 ContractApprovalServiceImpl 同构，但主闸门是数据库 CAS）：
     * 1. 串号校验：回调实例号与库里的不一致 → 旧流程的迟到通知，丢弃；
     * 2. 终态短路：已是同一终态 → 直接返回；
     * 3. **CAS 主闸门**：`WHERE approval_status = 1` 的条件更新，影响行数 0 → 幂等返回，
     *    **不执行任何库存动作**（两个并发回调也不会把库存补两次）。
     *
     * @param requisitionId     申领单编号（来自回调 businessKey）
     * @param processInstanceId 流程实例编号
     * @param approvalStatus    审批状态值（2 通过 / 3 不通过 / 4 已取消，对齐 BpmProcessInstanceStatusEnum）
     * @param reason            审批意见（仅记录日志，不落库）
     */
    void handleApprovalResult(Long requisitionId, String processInstanceId, Integer approvalStatus, String reason);

    /**
     * 出库：已扣减(2) → 已出库(3)，写 `actual_out_time`，**库存不动**
     *
     * 出库是「线下把料发出去」的登记动作：V1 的库存在提交审批时就已扣减，
     * 所以这里既不再扣、也不回补，只推进状态与时间戳。
     * 一次性动作由 CAS（`WHERE status = 2`）保证，重复出库影响行数 0 直接报错。
     *
     * @param requisitionId 申领单编号
     * @return 出库后的申领单（status / actualOutTime 已回填，返回值与库值一致）
     */
    RequisitionDO outbound(Long requisitionId);

    /**
     * 退料：已出库(3)/部分回退(5) → 部分回退(5)/已完成(4)，**回补库存** + 写 biz_type=3 退回流水
     *
     * 逐行：
     * 1. 在本单明细里定位该物料（退没领过的料 → {@code REQUISITION_RETURN_ITEM_NOT_IN_REQUISITION}）；
     * 2. 原子条件更新 `returned_qty += qty WHERE returned_qty + qty <= quantity`
     *    （影响行数 0 → {@code REQUISITION_RETURN_QUANTITY_EXCEEDED}，并发重复退料不会退两次）；
     * 3. 行锁回补 `available_qty += qty`，写 biz_type=3 流水（quantity 记**正数**，balance_after=回补后余额）。
     *
     * 全部退完（每行 `returned_qty == quantity`）→ 已完成(4)；否则 → 部分回退(5)。
     *
     * @param reqVO 退料请求
     * @return 退料后的申领单（status / actualReturnTime 已回填，返回值与库值一致）
     */
    RequisitionDO returnMaterials(RequisitionReturnReqVO reqVO);

}
