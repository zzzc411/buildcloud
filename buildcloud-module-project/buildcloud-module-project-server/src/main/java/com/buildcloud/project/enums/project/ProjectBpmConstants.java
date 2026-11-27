package com.buildcloud.project.enums.project;

/**
 * 立项审批相关的流程常量
 *
 * 集中管理流程定义 key，避免 key 字符串散落在发起方与回调监听器两侧
 * （两处必须完全一致，否则：发起成功但回调永远匹配不上，流程走完业务状态纹丝不动）。
 *
 * 命名与基座 OA 模块的 {@code BpmModelConstants} 同构（读码笔记 §5.1 复刻清单）。
 *
 * @author buildcloud
 */
public class ProjectBpmConstants {

    /**
     * 立项审批的流程定义 key
     *
     * 必须与以下三处完全一致，缺一处则链路断开：
     * 1. BPMN 文件的 {@code <process id="project-approve">}，见 sql/buildcloud/bpm/project-approve.bpmn20.xml；
     * 2. 流程模型的 key，见 scripts/bpm-deploy-project-approve.ps1 的 -ModelKey；
     * 3. bpm-server 侧转发监听器的 getProcessDefinitionKey() 返回值。
     */
    public static final String PROCESS_KEY_PROJECT_APPROVE = "project-approve";

    /**
     * 流程变量名：项目编号（businessKey 冗余一份进变量，便于流程详情页直接展示）
     */
    public static final String VARIABLE_PROJECT_ID = "projectId";

    /**
     * 流程变量名：项目业务编码（如 BC-2026-0001）
     */
    public static final String VARIABLE_PROJECT_NO = "projectNo";

    /**
     * 流程变量名：项目名称（流程标题/待办列表展示用）
     */
    public static final String VARIABLE_PROJECT_NAME = "projectName";

    /**
     * 流程变量名：发起人用户编号
     *
     * 基座在发起时还会注入 {@code START_USER_ID}（见读码笔记 §3.2），
     * 本变量用于审批页展示业务侧口径的「发起人」。
     */
    public static final String VARIABLE_INITIATOR = "initiator";

    /**
     * 流程变量名：项目所属部门编号（审批人按部门取部门经理时可读）
     */
    public static final String VARIABLE_DEPT_ID = "deptId";

    /**
     * 回调 URL 路径（相对于 buildcloud-project-server 的 RPC 前缀）
     *
     * 与 bpm-server 侧 {@code ProjectApproveStatusListener} 里写的 URL 尾段必须一致。
     * 这里只存路径，服务名由 nacos 的 spring.application.name 决定。
     */
    public static final String CALLBACK_PATH_UPDATE_AUDIT_STATUS = "/project/update-audit-status";

    private ProjectBpmConstants() {
    }

}
