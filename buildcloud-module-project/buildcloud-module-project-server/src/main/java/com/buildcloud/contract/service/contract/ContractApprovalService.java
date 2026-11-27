package com.buildcloud.contract.service.contract;

import com.buildcloud.contract.controller.admin.contract.vo.contractapproval.ContractApprovalStatusRespVO;

import java.math.BigDecimal;

/**
 * 合同进度款审批 Service 接口
 *
 * 职责边界（spec v1.2 §5.1 / §5.3）：
 * - 本服务只负责**发起**进度款审批与**接收**审批结果；
 * - 真正的审批动作（通过/驳回）在基座 bpm 的 {@code /admin-api/bpm/task/approve|reject}，
 *   本服务不代理、不复制（读码笔记 §3.3）；
 * - 审批结果回流由 bpm-server 的 {@code PaymentApproveStatusListener} 转发到本服务的
 *   {@code ContractApprovalListener}（ADR-003）。
 *
 * 与项目立项审批（W3-P4 的 ProjectApprovalService）的差异只有业务副作用：
 * 立项审批通过 → 项目状态机 筹备→在建；
 * 进度款审批通过 → **生成 bc_contract_payment_record + 原子累加 bc_contract.paid_amount
 * + 合同状态 审批中→履行中**（spec §5.1「通过后生成 payment_record 并回写合同累计收付」）。
 *
 * @author buildcloud
 */
public interface ContractApprovalService {

    /**
     * 提交进度款审批
     *
     * 校验链（任一不满足抛对应错误码，均不调 BPM）：
     * 1. 合同存在（CONTRACT_NOT_EXISTS）；
     * 2. 合同业务状态 ∈ {草稿(0), 履行中(2)}（CONTRACT_APPROVAL_STATUS_NOT_ALLOWED）——
     *    草稿首次申请、履行中追加期次都合理；审批中是在途重复，已结算/已作废是终态；
     * 3. 无审批中的流程（CONTRACT_APPROVAL_ALREADY_RUNNING）；
     * 4. 金额 > 0 且 ≤ 合同未收付余额（CONTRACT_APPROVAL_AMOUNT_INVALID）。
     *
     * 通过校验后：落「审批中」状态 + 在途金额/期次 → Feign 发起 payment-approve 实例
     * → 回写流程实例编号。返回流程实例编号。
     *
     * @param contractId 合同编号
     * @param period     期次（第几期，从 1 起）
     * @param amount     本次申请金额（元）
     * @return 流程实例编号
     */
    String submitApproval(Long contractId, Integer period, BigDecimal amount);

    /**
     * 查询进度款审批状态（含合同业务状态与累计收付，供台账行内刷新）
     *
     * @param contractId 合同编号
     * @return 审批状态
     */
    ContractApprovalStatusRespVO getApprovalStatus(Long contractId);

    /**
     * 处理审批结果回调（幂等）
     *
     * @param contractId        合同编号（= businessKey）
     * @param processInstanceId 流程实例编号（回调事件 id）
     * @param approved          true=通过；false=驳回或取消
     * @param reason            审批意见/结束原因
     */
    void handleApprovalResult(Long contractId, String processInstanceId, boolean approved, String reason);

    /**
     * 处理审批结果回调（带完整审批状态值，供监听器直传事件里的 status）
     *
     * 与上面 boolean 版本的差别：能区分「驳回(3)」与「取消(4)」——两者的业务副作用相同
     * （都不生成流水、合同退回），但写进 approval_status 的值不同，前端提示语也不同。
     * 幂等三件套与 boolean 版本完全一致：
     * 1. 串号校验（回调实例号 ≠ 库中当前实例号 → 旧流程迟到通知，丢弃）；
     * 2. 终态短路（已是同一终态 → 重复通知，直接返回；**这是防重复生成流水/重复累加的关键**）；
     * 3. 流水判重（按 processInstanceId 已生成过流水则不再生成、不再累加）。
     *
     * @param contractId        合同编号（= businessKey）
     * @param processInstanceId 流程实例编号（回调事件 id）
     * @param approvalStatus    审批状态：2-通过 3-不通过 4-已取消
     * @param reason            审批意见/结束原因
     */
    void handleApprovalResult(Long contractId, String processInstanceId, Integer approvalStatus, String reason);

}
