package com.buildcloud.material.enums.requisition;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 材料申领单状态枚举
 *
 * 状态机（spec v1.2 §5.2 原文）：
 * <pre>
 * 草稿 ──提交──▶ 已提交(已预扣) ──审批通过──▶ 已扣减 ──出库──▶ 已出库 ──核销──▶ 已完成
 *                     │                                              │
 *                     ├──审批驳回──▶ 已取消(回滚预扣+冲正流水)          └──退料──▶ 部分回退
 *                     └──超时未审完(延迟消息)──▶ 已关闭(回滚预扣+冲正流水)
 * </pre>
 *
 * 与 spec 的对应关系（**逐字段对照 DDL 的 status 列注释**，见 sql/buildcloud/07-material.sql 第 5 节）：
 * 0-草稿 / 1-已提交(已预扣) / 2-已扣减 / 3-已出库 / 4-已完成 / 5-部分回退 / 8-已关闭(超时) / 9-已取消(驳回)。
 *
 * ========== 本 Phase（W4-P4，V1 同步链路）只落地其中一段 ==========
 *
 * 任务书明确：「本 Phase（V1）只实现 草稿→已提交→已扣减（同步扣减）、以及取消。出库/退库留给 P5。」
 * 因此 V1 的一次 {@code submitRequisition} 会**在同一个本地事务里连续走两步**：
 * 草稿(0) →〔状态机〕→ 已提交(1) →〔同步扣减成功〕→ 已扣减(2)。
 * 「已提交(1)」这个中间态在 V1 里只存在事务内、不落库（事务结束就是 2）；
 * 之所以仍然在状态机里保留它，是因为 **V2（W5）要把它变成真正的持久态**：
 * Redis Lua 预扣成功 → 落库 已提交(1) + locked_qty+ / available_qty- → MQ 异步扣减 → 已扣减(2)。
 * 这样 V1/V2 共用同一张状态图、同一套 canTransfer 校验，W5 只需替换「谁在什么时候推进状态」。
 *
 * 8-已关闭 与 9-已取消 分开存的理由（DDL 注释同口径）：超时自动关单（运营侧、系统驱动）
 * 与审批驳回（业务侧、人驱动）在报表与追责上是两件事，合并成一个终止态就分不出来了。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum RequisitionStatusEnum implements ArrayValuable<Integer> {

    /**
     * 草稿：新建后的初始态，可编辑明细，未动库存
     */
    DRAFT(0, "草稿"),

    /**
     * 已提交（已预扣）：V2 里「Redis 预扣成功、DB 尚未扣减」的持久态；
     * V1 里只是事务内的瞬时态（同一事务紧接着推进到已扣减）
     */
    SUBMITTED(1, "已提交"),

    /**
     * 已扣减：库存已从 available_qty 扣掉、扣减流水已落库。
     * V1 的终态（压测对照组跑到这里就结束）；V2 由 MQ 消费者推进到这里
     */
    DEDUCTED(2, "已扣减"),

    /**
     * 已出库：W4-P5 出库动作完成后（本 Phase 不产生）
     */
    OUTBOUND(3, "已出库"),

    /**
     * 已完成：核销完毕（本 Phase 不产生）
     */
    COMPLETED(4, "已完成"),

    /**
     * 部分回退：退料后部分数量退回库存（W4-P5 产生）
     */
    PARTIAL_RETURNED(5, "部分回退"),

    /**
     * 已关闭：超时未审完，延迟消息自动关单（W5 产生）
     */
    CLOSED(8, "已关闭"),

    /**
     * 已取消：审批驳回 / 主动取消，需回滚库存并写冲正流水
     */
    CANCELED(9, "已取消");

    /**
     * 状态值（落库值）
     */
    private final Integer status;

    /**
     * 状态名（前端展示）
     */
    private final String name;

    public static final Integer[] ARRAY = Arrays.stream(values())
            .map(RequisitionStatusEnum::getStatus).toArray(Integer[]::new);

    @Override
    public Integer[] array() {
        return ARRAY;
    }

    /**
     * 校验状态值是否为合法枚举值
     *
     * @param status 状态值
     * @return 是否合法
     */
    public static boolean isValid(Integer status) {
        return ArrayUtil.contains(ARRAY, status);
    }

    /**
     * 按状态值查枚举
     *
     * @param status 状态值
     * @return 枚举；取值不在范围内返回 {@code null}
     */
    public static RequisitionStatusEnum valueOf(Integer status) {
        return ArrayUtil.firstMatch(item -> item.getStatus().equals(status), values());
    }

    /**
     * 判定状态流转是否合法（状态机唯一实现，与 W3 的 {@code ContractStatusEnum#canTransfer} 同构）
     *
     * 合法边（spec §5.2 全图 + 本 Phase 的落地范围）：
     * <pre>
     * 草稿0     → 已提交1 / 已取消9
     * 已提交1   → 已扣减2 / 已取消9 / 已关闭8
     * 已扣减2   → 已出库3 / 已取消9            （P5 出库；取消 = 撤销申领，回补库存 + 冲正流水）
     * 已出库3   → 已完成4 / 部分回退5
     * 部分回退5 → 已完成4
     * 已完成4 / 已取消9 / 已关闭8 → 终态，无出边
     * </pre>
     *
     * 为什么「已扣减2 → 已取消9」是合法边：任务书要求 V1 的 cancel 支持「已提交/已扣减可取消」。
     * 已扣减的单据取消 = 撤销申领，必须回补 available_qty 并写 biz_type=4 冲正流水，
     * 语义上与 P5 审批驳回一致（P5 只是把触发者从「人点取消」换成「审批回调」）。
     *
     * 为什么不允许「已提交2 → 草稿0」（退回可编辑）：库存已被预扣/扣减，
     * 退回草稿会让「单据状态」与「库存事实」脱钩；要改就取消（自动回补）后重建单。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return 是否允许；源或目标为空、或二者相同一律不允许
     */
    public static boolean canTransfer(Integer from, Integer to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        if (DRAFT.status.equals(from)) {
            return SUBMITTED.status.equals(to) || CANCELED.status.equals(to);
        }
        if (SUBMITTED.status.equals(from)) {
            return DEDUCTED.status.equals(to) || CANCELED.status.equals(to) || CLOSED.status.equals(to);
        }
        if (DEDUCTED.status.equals(from)) {
            return OUTBOUND.status.equals(to) || CANCELED.status.equals(to);
        }
        if (OUTBOUND.status.equals(from)) {
            return COMPLETED.status.equals(to) || PARTIAL_RETURNED.status.equals(to);
        }
        if (PARTIAL_RETURNED.status.equals(from)) {
            return COMPLETED.status.equals(to);
        }
        // 已完成 / 已取消 / 已关闭：终态
        return false;
    }

    /**
     * 是否为终态（无出边）
     *
     * @param status 状态值
     * @return 是否终态
     */
    public static boolean isEndStatus(Integer status) {
        return COMPLETED.status.equals(status) || CANCELED.status.equals(status) || CLOSED.status.equals(status);
    }

    /**
     * 判断当前状态是否允许取消（任务书口径：只有已提交/已扣减可取消）
     *
     * 单独暴露成方法的理由：Controller/Service/前端按钮都调用同一处判断，
     * 避免「前端按 status in (1,2) 显示按钮、后端用 canTransfer 校验」两处口径漂移。
     * 注意本方法比 canTransfer 更窄：canTransfer 还允许草稿→已取消（作废草稿），
     * 但草稿没有动过库存，真正的「取消」业务动作只需要处理动过库存的两个状态。
     *
     * @param status 当前状态
     * @return 是否可取消
     */
    public static boolean canCancel(Integer status) {
        return SUBMITTED.status.equals(status) || DEDUCTED.status.equals(status);
    }

    /**
     * 判断库存是否已被本单据扣减过（决定取消时是否需要回补）
     *
     * V1 里「已提交」(1) 在事务内不代表已扣减（扣减发生在状态推进到 2 之前），
     * 但**只有 4/5 之外的状态**才需要看流水；真正可靠的判据是「有没有 biz_type=2 的流水」，
     * 所以 Service 层不靠本方法做回补判断，而是按流水实际存在与否决定（见 RequisitionServiceImpl#cancelRequisition）。
     * 本方法保留给前端做「取消会回补库存」的提示文案。
     *
     * @param status 当前状态
     * @return 是否可能已扣减
     */
    public static boolean mayHaveDeducted(Integer status) {
        return status != null && (DEDUCTED.status.equals(status) || SUBMITTED.status.equals(status));
    }

}
