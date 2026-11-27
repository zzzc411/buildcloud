package com.buildcloud.project.service.project;

import com.buildcloud.project.controller.admin.project.vo.ProjectApprovalStatusRespVO;

/**
 * 项目立项审批 Service
 *
 * 职责边界：把「项目」这一业务单据接到基座 BPM 审批流上，复刻基座 OA 请假（形态 B：
 * 独立业务服务）的既定模式（读码笔记《reading-notes-bpm-integration.md》§3）：
 *
 * | 阶段 | 动作 | 数据落点 |
 * | --- | --- | --- |
 * | 发起 | 校验 → Feign 调 bpm → 回写 | approval_status=1、process_instance_id |
 * | 审批中 | 只读查询 | — |
 * | 结果回来 | 按 status 分流 → 通过走状态机 | approval_status=2/3/4；通过时项目 status 0→1 |
 *
 * **不在本类做的事**（避免越界）：
 * - 不直接写 bc_project.status：项目业务状态只能经 {@link ProjectStatusService} 状态机变更（spec §5.1）；
 * - 不感知 Flowable：所有引擎交互都经基座 {@code BpmProcessInstanceApi}，业务侧不出现引擎类型。
 *
 * @author buildcloud
 */
public interface ProjectApprovalService {

    /**
     * 提交项目立项审批
     *
     * 前置校验（全部不满足即抛错，不做「尽力而为」）：
     * 1. 项目存在；
     * 2. 项目业务状态为「筹备」（只有筹备中的项目才需要立项）；
     * 3. 不存在审批中的流程实例（防重复提交）。
     *
     * 成功后的落库（同一事务内）：
     * - {@code processInstanceId} ← bpm 返回的实例编号；
     * - {@code approvalStatus} ← 审批中(1)。
     *
     * @param projectId 项目编号（同时作为流程的 businessKey）
     * @return 流程实例编号
     */
    String submitApproval(Long projectId);

    /**
     * 查询项目的立项审批状态
     *
     * @param projectId 项目编号
     * @return 审批状态（含流程实例编号、状态名、项目状态名）
     */
    ProjectApprovalStatusRespVO getApprovalStatus(Long projectId);

    /**
     * 处理审批结果回调（bpm-server → 本服务）
     *
     * 分流规则：
     * - **通过(2)**：调 {@link ProjectStatusService} 把项目 筹备(0) → 在建(1)，再置 approvalStatus=2；
     * - **不通过(3)**：项目业务状态保持不变（仍是筹备），置 approvalStatus=3；
     * - **已取消(4)**：同样保持筹备，置 approvalStatus=4。
     *
     * **幂等**（关键）：同一 processInstanceId 的重复通知必须安全。
     * 实现方式见 {@code ProjectApprovalServiceImpl#handleApprovalResult}：以库中已记录的
     * approvalStatus 做「终态短路」——已是终态且结果相同时直接返回，不重复走状态机。
     * 不走「先查审批日志再插入」这类额外表，因为本项目不建审批流水表（spec §5.3 未要求）。
     *
     * @param projectId         项目编号（来自回调的 businessKey）
     * @param processInstanceId 流程实例编号（用于识别迟到/串号的回调，可为空则跳过校验）
     * @param approved          是否审批通过
     * @param reason            审批意见/驳回原因
     */
    void handleApprovalResult(Long projectId, String processInstanceId, boolean approved, String reason);

}
