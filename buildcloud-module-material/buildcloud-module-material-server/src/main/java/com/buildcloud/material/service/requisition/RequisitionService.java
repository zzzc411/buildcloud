package com.buildcloud.material.service.requisition;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionPageReqVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionSaveReqVO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 材料申领 Service 接口
 *
 * 业务边界（spec v1.2 §5.2 状态机 + §6.1 V1 定义）：
 * <pre>
 * 草稿0 ──提交──▶ 已提交1(已预扣) ──审批通过──▶ 已扣减2 ──出库──▶ 已出库3 ──核销──▶ 已完成4
 *                     │                                              │
 *                     ├──审批驳回──▶ 已取消9(回滚预扣+冲正流水)          └──退料──▶ 部分回退5
 *                     └──超时未审完──▶ 已关闭8(回滚预扣+冲正流水)
 * </pre>
 *
 * **本 Phase（W4-P4，V1）只实现四段**：建草稿(0) → 提交并同步扣减(1→2) → 取消(1/2→9) → 查询。
 * 审批回流（1→2 的真实触发者从"同步扣减"换成"审批通过"）、出库(2→3)、核销(3→4)、退料(→5)、
 * 超时关单(→8) 全部留给 W4-P5 / W5。
 *
 * ========== 库存与状态的一致性契约（V1 的核心不变量） ==========
 *
 * 「单据状态为已扣减(2)」⟺「该单的 biz_type=2 流水之和 == 明细 quantity 之和」
 * ⟺「库存 available_qty 已按该单扣减」。
 * 三条断言在单测里逐条验证（`RequisitionServiceImplTest#testSubmit_success_deductAndLog`）。
 * 取消后则再加一条：`SUM(quantity) 中 biz_type=2 + biz_type=4 == 0`（净额归零）。
 *
 * @author buildcloud
 */
public interface RequisitionService {

    /**
     * 创建申领单（**草稿态**，不动库存）
     *
     * 为什么建单不直接扣减：现场经常先录单、后确认数量；草稿态允许反复核对。
     * 扣减只发生在显式 submit——这也是 V1/V2 的公共入口（W5 换成 Lua 预扣）。
     *
     * @param createReqVO 建单请求（项目 + 明细）
     * @return 申领单编号
     */
    Long createRequisition(@Valid RequisitionSaveReqVO createReqVO);

    /**
     * 提交申领单：**V1 同步扣减**（spec §6.1 V1 原文）
     *
     * 同一 `@Transactional` 内：
     * 1. 校验单据存在且状态=草稿；
     * 2. 推状态 草稿(0) → 已提交(1)，生成唯一 req_no，写 timeout_time（+30 分钟，W5 延迟消息用）；
     * 3. 逐条明细：`SELECT ... FOR UPDATE` 锁库存行 → 校验 available_qty >= quantity →
     *    `available_qty -= qty` → 写 bc_stock_log（biz_type=2、quantity=-qty、balance_after=扣减后余额、
     *    biz_no=req_no）；
     * 4. 推状态 已提交(1) → 已扣减(2)。
     *
     * 任一步失败 → 抛业务异常，**整体回滚不留脏数据**（库存不减少、流水不产生、单据仍为草稿）。
     *
     * 模式切换：由 `buildcloud.material.requisition-mode` 决定走 V1（本实现）还是 V2（W5，当前抛未实现）。
     *
     * @param id 申领单编号
     * @return 提交后的申领单（状态=已扣减，reqNo / timeoutTime 已填）
     */
    RequisitionDO submitRequisition(Long id);

    /**
     * 取消申领单：**回补库存 + 写冲正流水**
     *
     * 只允许「已提交(1) / 已扣减(2)」两种状态取消（任务书口径）。
     * V1 里 1 是事务内瞬时态，实际能取消的只有 2；保留对 1 的支持是给 V2 用
     * （V2 的已提交是持久态，取消时 Redis 回补 + 冲正流水）。
     *
     * 回补的数学口径：把明细 quantity **按原样**加回 available_qty，
     * 并写 biz_type=4（冲正）流水、quantity 记正数。这样 `biz_type=2 + biz_type=4` 之和为 0，
     * 与「该单未发生过库存变动」净额一致（spec §6.1 第 ⑦ 步的对账口径）。
     *
     * @param id 申领单编号
     * @return 取消后的申领单（状态=已取消）
     */
    RequisitionDO cancelRequisition(Long id);

    /**
     * 获得申领单（不含明细）
     *
     * @param id 申领单编号
     * @return 申领单；不存在返回 null（与基座 getXxx 口径一致）
     */
    RequisitionDO getRequisition(Long id);

    /**
     * 获得申领单明细
     *
     * @param requisitionId 申领单编号
     * @return 明细列表（按录入顺序）
     */
    List<RequisitionItemDO> getRequisitionItemList(Long requisitionId);

    /**
     * 获得申领单分页
     *
     * @param pageReqVO 分页 + 筛选条件
     * @return 申领单分页
     */
    PageResult<RequisitionDO> getRequisitionPage(RequisitionPageReqVO pageReqVO);

}
