package com.buildcloud.contract.enums.contract;

/**
 * 进度款审批相关的流程常量
 *
 * 集中管理流程定义 key 与变量名，避免字符串散落在发起方、回调监听器、bpm-server 转发器三侧
 * （三处 key 必须完全一致，否则：发起成功但回调永远匹配不上，流程走完业务数据纹丝不动——
 *  这正是 W3-P4 踩过的静默断链，经验 lessons-learned L2-12）。
 *
 * 命名与项目子域 {@code ProjectBpmConstants}（W3-P4）同构，便于两条流程对照阅读。
 *
 * @author buildcloud
 */
public class ContractBpmConstants {

    /**
     * 进度款审批的流程定义 key
     *
     * 必须与以下四处完全一致，缺一处则链路断开：
     * 1. BPMN 文件的 {@code <process id="payment-approve">}，
     *    见 sql/buildcloud/bpm/payment-approve.bpmn20.xml；
     * 2. 流程模型的 key，见 scripts/bpm-deploy-payment-approve.ps1 的 -ModelKey；
     * 3. bpm-server 侧转发监听器 {@code PaymentApproveStatusListener#getProcessDefinitionKey()}；
     * 4. 本模块 {@code ContractApprovalListener#getProcessDefinitionKey()}（单体部署时的事件过滤）。
     */
    public static final String PROCESS_KEY_PAYMENT_APPROVE = "payment-approve";

    /**
     * 流程变量名：合同编号（businessKey 冗余一份进变量，便于流程详情页直接展示）
     */
    public static final String VARIABLE_CONTRACT_ID = "contractId";

    /**
     * 流程变量名：合同业务编码（如 HT-2026-0001）
     */
    public static final String VARIABLE_CONTRACT_NO = "contractNo";

    /**
     * 流程变量名：合同名称（流程标题/待办列表展示用）
     */
    public static final String VARIABLE_CONTRACT_NAME = "contractName";

    /**
     * 流程变量名：本期申请金额（元）
     *
     * 审批通过后按此金额生成 bc_contract_payment_record 并累加 paid_amount；
     * 因此它是「审批结果」与「资金流水」之间唯一的金额来源，必须与提交时的入参一致。
     */
    public static final String VARIABLE_PAYMENT_AMOUNT = "paymentAmount";

    /**
     * 流程变量名：期次（第几期进度款；与 bc_contract_payment_plan.period 同口径）
     */
    public static final String VARIABLE_PERIOD = "period";

    /**
     * 流程变量名：发起人用户编号
     *
     * 基座在发起时还会注入 {@code START_USER_ID}（读码笔记 §3.2），
     * 本变量用于审批页展示业务侧口径的「发起人」。
     */
    public static final String VARIABLE_INITIATOR = "initiator";

    /**
     * 流程变量名：合同所属部门编号
     *
     * **本期（W3-P5）不使用**：bc_contract 没有 dept_id 列（部门归属在项目侧 bc_project.dept_id），
     * 审批人由 BPMN 的用户组策略（9202 财务审批组）决定，不依赖部门维度。
     * 保留常量是为了与 ProjectBpmConstants 同构，且将来若改「按项目部门取审批人」时不必改常量定义。
     */
    public static final String VARIABLE_DEPT_ID = "deptId";

    /**
     * 回调 URL 路径（相对于 buildcloud-project-server 的 RPC 前缀）
     *
     * 与 bpm-server 侧 {@code PaymentApproveStatusListener} 里写的 URL 尾段必须一致。
     * 这里只存路径，服务名由 nacos 的 spring.application.name 决定。
     */
    public static final String CALLBACK_PATH_UPDATE_AUDIT_STATUS = "/contract/update-audit-status";

    private ContractBpmConstants() {
    }

}
