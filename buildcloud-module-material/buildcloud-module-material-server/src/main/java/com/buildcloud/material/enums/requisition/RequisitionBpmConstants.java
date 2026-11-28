package com.buildcloud.material.enums.requisition;

/**
 * 材料申领审批（requisition-approve）流程常量
 *
 * 集中管理流程定义 key、流程变量名与回调路径，避免字符串散落在
 * 「发起方（RequisitionApprovalServiceImpl）」「回调监听器（RequisitionApprovalListener）」
 * 「bpm-server 转发器（RequisitionApproveStatusListener）」三侧。
 * 三处 key 必须完全一致，否则：发起成功但回调永远匹配不上，流程走完业务数据纹丝不动——
 * 这正是 W3-P4 踩过的静默断链（lessons-learned L2-12）。
 *
 * 命名与 project 域的 {@code ProjectBpmConstants}、contract 域的 {@code ContractBpmConstants} 同构，
 * 便于三条流程（project-approve / payment-approve / requisition-approve）对照阅读。
 *
 * @author buildcloud
 */
public class RequisitionBpmConstants {

    /**
     * 材料申领审批的流程定义 key
     *
     * 必须与以下四处完全一致，缺一处则链路断开：
     * 1. BPMN 文件的 {@code <process id="requisition-approve">}，
     *    见 sql/buildcloud/bpm/requisition-approve.bpmn20.xml；
     * 2. 流程模型的 key，见 scripts/bpm-deploy-requisition-approve.ps1 的 -ModelKey；
     * 3. bpm-server 侧转发监听器 {@code RequisitionApproveStatusListener#getProcessDefinitionKey()}；
     * 4. 本模块 {@code RequisitionApprovalListener#getProcessDefinitionKey()}（单体部署时的事件过滤）。
     */
    public static final String PROCESS_KEY_REQUISITION_APPROVE = "requisition-approve";

    /**
     * 流程变量名：申领单编号（businessKey 冗余一份进变量，便于流程详情页直接展示）
     */
    public static final String VARIABLE_REQUISITION_ID = "requisitionId";

    /**
     * 流程变量名：申领单号（REQ-yyyyMMdd-XXXXXX，待办标题/审批页展示）
     */
    public static final String VARIABLE_REQ_NO = "reqNo";

    /**
     * 流程变量名：明细摘要文本（"物料1×10；物料2×20"）
     *
     * 为什么要把它塞进流程变量：审批人必须看到「这张单要申领什么、多少」才能决策，
     * 而基座审批详情页只能渲染流程变量与业务表单，不会反查业务库。
     */
    public static final String VARIABLE_ITEMS = "items";

    /**
     * 流程变量名：会签审批人用户编号列表（{@code List<Long>}）
     *
     * **本流程唯一的"谁审批"来源**：BPMN 的会签节点用
     * {@code flowable:candidateStrategy=60}（EXPRESSION 流程表达式）+
     * {@code flowable:candidateParam=${approvers}} 取人，
     * 基座 {@code BpmParallelMultiInstanceBehavior#resolveNrOfInstances} 按这个集合的人数
     * 创建并行任务实例（见 sql/buildcloud/bpm/requisition-approve.bpmn20.xml 文件头「会签」一节）。
     * 因此：approvers 里有几个人，这张单就必须几个人全部通过才算过。
     */
    public static final String VARIABLE_APPROVERS = "approvers";

    /**
     * 流程变量名：发起人用户编号
     *
     * 基座在发起时还会注入 {@code START_USER_ID}（读码笔记 §3.2），
     * 本变量用于审批页展示业务侧口径的「申请人」。
     */
    public static final String VARIABLE_INITIATOR = "initiator";

    /**
     * 回调 URL 路径（相对于 buildcloud-material-server 的 RPC 前缀 ApiConstants.PREFIX）
     *
     * 与 bpm-server 侧 {@code RequisitionApproveStatusListener} 里写的 URL 尾段必须一致。
     * 这里只存路径，服务名由 nacos 的 spring.application.name 决定。
     */
    public static final String CALLBACK_PATH_UPDATE_AUDIT_STATUS = "/requisition/update-audit-status";

    private RequisitionBpmConstants() {
    }

}
