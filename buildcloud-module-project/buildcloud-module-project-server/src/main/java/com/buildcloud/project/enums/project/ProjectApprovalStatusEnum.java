package com.buildcloud.project.enums.project;

import cn.hutool.core.util.ArrayUtil;
import cn.iocoder.yudao.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 项目立项审批状态枚举
 *
 * **刻意与基座 {@link cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum} 数值一一对齐**
 * （-1 / 1 / 2 / 3 / 4），原因：
 * 1. 业务表 bc_project.approval_status 直接落库的就是流程实例状态值，回调时无需做映射换算，
 *    少一层转换就少一处「映射写错导致状态错位」的隐患；
 * 2. 排障时 `SELECT approval_status FROM bc_project` 与
 *    `SELECT STATUS_ FROM ACT_HI_VARINST WHERE NAME_='STATUS'` 可直接对读，运维不用心算；
 * 3. 与基座 OA/CRM 的业务表口径一致（读码笔记《reading-notes-bpm-integration.md》§2.2：
 *    「业务表 status 直接复用 BpmProcessInstanceStatusEnum」）。
 *
 * 之所以仍在本模块定义一份枚举（而非直接引用基座枚举）：
 * - 自研模块对基座 bpm-api 的依赖只应有「发起流程」这一条必要链路，状态语义属于本域业务概念，
 *   未来若 workflow 引擎从 Flowable 换成别的实现，本枚举不受影响；
 * - 中英文描述、以及 {@link #isEndStatus()} 这类判定是本域视角（如「已取消」在本域也视为终态），
 *   与基座枚举的生命周期判定不完全同义。
 *
 * 与 {@link ProjectStatusEnum} 的区别：那是**项目业务状态机**（筹备/在建/停工/竣工），
 * 这是**审批维度**，两者正交，审批通过只是「筹备→在建」的触发条件，绝不直接写 status 列。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ProjectApprovalStatusEnum implements ArrayValuable<Integer> {

    /**
     * 未开始：项目刚创建、尚未提交立项审批
     *
     * 对应 bc_project.approval_status 的列默认值 -1。
     */
    NOT_START(-1, "未开始"),

    /**
     * 审批中：已发起 project-approve 流程实例，尚未出结果
     */
    RUNNING(1, "审批中"),

    /**
     * 审批通过：流程正常结束且结果为通过
     */
    APPROVE(2, "审批通过"),

    /**
     * 审批不通过：审批人驳回（终止型驳回）
     */
    REJECT(3, "审批不通过"),

    /**
     * 已取消：发起人撤回或管理员终止
     */
    CANCEL(4, "已取消");

    public static final Integer[] ARRAYS = Arrays.stream(values())
            .map(ProjectApprovalStatusEnum::getStatus).toArray(Integer[]::new);

    /**
     * 状态值（与 BpmProcessInstanceStatusEnum 对齐）
     */
    private final Integer status;

    /**
     * 状态描述
     */
    private final String desc;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    /**
     * 按状态值查枚举
     *
     * @param status 状态值
     * @return 枚举；取值不在范围内返回 {@code null}
     */
    public static ProjectApprovalStatusEnum valueOf(Integer status) {
        return ArrayUtil.firstMatch(item -> item.getStatus().equals(status), values());
    }

    /**
     * 是否为审批终态（通过 / 不通过 / 已取消）
     *
     * 用途：{@code submitApproval} 的准入判断——只有「未开始」或「已终态」的项目才允许（重新）发起，
     * 「审批中」的必须拒绝，避免同一项目并发产生两个流程实例。
     *
     * @param status 状态值
     * @return 是否终态
     */
    public static boolean isEndStatus(Integer status) {
        return APPROVE.getStatus().equals(status)
                || REJECT.getStatus().equals(status)
                || CANCEL.getStatus().equals(status);
    }

}
