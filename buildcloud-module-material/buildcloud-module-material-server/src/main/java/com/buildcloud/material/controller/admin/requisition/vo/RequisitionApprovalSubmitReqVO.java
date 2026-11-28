package com.buildcloud.material.controller.admin.requisition.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 管理后台 - 材料申领审批提交 Request VO
 *
 * 对应 {@code POST /buildcloud-material/requisition/approval/submit}。
 *
 * ========== 两个字段就是「谁审、审哪张单」的全部信息 ==========
 *
 * - {@code requisitionId}：要提交审批的申领单（必须处于草稿态）；
 * - {@code approverUserIds}：**会签审批人列表**（至少 1 人）。
 *
 * ========== 为什么审批人由调用方传入，而不是像立项/进度款那样写死在 BPMN 的用户组里 ==========
 *
 * 材料申领是「项目现场按需申领、由项目经理/材料员/成本员共同把关」的场景，
 * 会签人**随项目与申领金额变化**（同一张单在不同项目未必是同一批人），
 * 写死用户组会让「A 项目的会签人」被迫审 B 项目的单。
 * 因此本流程用基座的「流程表达式」候选策略（candidateStrategy=60）+ 变量 {@code approvers}，
 * 由业务方在发起时把这份名单作为流程变量传下去：
 * 名单里有几个人 → 基座就并行创建几个任务实例 → **全部通过才算通过**
 * （机制细节见 sql/buildcloud/bpm/requisition-approve.bpmn20.xml 文件头「会签」一节）。
 *
 * ========== 为什么要限制人数上限（不设也能跑，但会出事故） ==========
 *
 * 会签实例数 = 名单人数。若误传一个 200 人的列表（比如前端传了整个部门），
 * 就会在 Flowable 里创建 200 个并行任务、审批列表被淹没且永远集不齐。
 * 上限取 {@link #MAX_APPROVERS}：实际业务里会签 1-5 人是合理区间，
 * 10 已经是很重的会签了；超过就在 Service 层明确报错而不是默默接受
 * （上限校验不做在注解上，是为了让错误以业务错误码 `1-102-050-017` 返回，
 * 而不是笼统的参数校验失败——前端要能提示「会签人数过多」）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料申领审批提交 Request VO")
@Data
public class RequisitionApprovalSubmitReqVO {

    /**
     * 会签审批人人数上限
     */
    public static final int MAX_APPROVERS = 10;

    @Schema(description = "申领单编号（bc_requisition.id，必须处于草稿态）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "申领单编号不能为空")
    private Long requisitionId;

    /**
     * 会签审批人用户编号列表
     *
     * 空列表必须在 Service 层显式报错（{@code REQUISITION_APPROVERS_EMPTY}）：
     * 基座的多实例行为对空集合的处理是「不创建任何任务」，流程会直接流到结束事件并被判定为
     * 通过——那意味着这张单在**无人审批**的情况下扣掉了库存，属于必须拦住的账务风险。
     */
    @Schema(description = "会签审批人用户编号列表（至少 1 人，最多 10 人；全部通过才算通过）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "[1,100]")
    @NotEmpty(message = "会签审批人不能为空，请至少指定 1 人")
    private List<Long> approverUserIds;

}
