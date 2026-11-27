package com.buildcloud.contract.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * Contract 合同资金子域错误码枚举类
 *
 * contract 子域使用 1-101-000-000 段（spec v1.2 §7.2 段位分配：contract 1-101-xxx）；
 * 与 project 子域（1-100-xxx，见 com.buildcloud.project.enums.ErrorCodeConstants）同模块但段位独立，
 * 避免新增合同功能时与项目错误码抢号。
 *
 * 段位内编号约定：
 * - 000 段：合同主体（bc_contract）crud 与状态机；
 * - 010 段：收付款计划（bc_contract_payment_plan）；
 * - 020 段：实际收付款流水（bc_contract_payment_record）；
 * - 030 段：变更签证（bc_contract_change）。
 *
 * @author buildcloud
 */
public interface ErrorCodeConstants {

    // ========== 合同 1-101-000-000 ==========
    ErrorCode CONTRACT_NOT_EXISTS = new ErrorCode(1_101_000_000, "合同不存在");
    ErrorCode CONTRACT_NO_DUPLICATE = new ErrorCode(1_101_000_001, "合同编号已存在");
    ErrorCode CONTRACT_STATUS_INVALID = new ErrorCode(1_101_000_002, "合同状态流转不正确");
    ErrorCode CONTRACT_NOT_DRAFT = new ErrorCode(1_101_000_003, "合同不允许修改，不是草稿状态");
    ErrorCode CONTRACT_PROJECT_NOT_EXISTS = new ErrorCode(1_101_000_004, "合同关联的项目不存在");
    ErrorCode CONTRACT_AMOUNT_INVALID = new ErrorCode(1_101_000_005, "合同金额必须大于 0");
    ErrorCode CONTRACT_NOT_EFFECTIVE = new ErrorCode(1_101_000_006, "合同不在履行中状态，不能登记收付款");

    // ========== 收付款计划 1-101-010-000 ==========
    ErrorCode PAYMENT_PLAN_NOT_EXISTS = new ErrorCode(1_101_010_000, "收付款计划不存在");
    ErrorCode PAYMENT_PLAN_PERIOD_DUPLICATE = new ErrorCode(1_101_010_001, "该合同的期次计划已存在");
    ErrorCode PAYMENT_PLAN_AMOUNT_EXCEED = new ErrorCode(1_101_010_002, "收付款计划金额合计不能超过合同金额");

    // ========== 实际收付款 1-101-020-000 ==========
    ErrorCode PAYMENT_RECORD_NOT_EXISTS = new ErrorCode(1_101_020_000, "收付款记录不存在");
    ErrorCode PAYMENT_RECORD_APPROVAL_DUPLICATE = new ErrorCode(1_101_020_001, "该审批单已生成过收付款记录");

    // ========== 变更签证 1-101-030-000 ==========
    ErrorCode CONTRACT_CHANGE_NOT_EXISTS = new ErrorCode(1_101_030_000, "合同变更记录不存在");
    ErrorCode CONTRACT_CHANGE_AMOUNT_ZERO = new ErrorCode(1_101_030_001, "变更金额不能为 0");

    // ========== 进度款审批 1-101-040-000（W3-P5，spec §5.1 / §5.3） ==========
    /**
     * 合同当前业务状态不允许发起进度款审批
     *
     * 准入：只有「草稿(0)」（首次申请）与「履行中(2)」（后续期次）可以发起；
     * 审批中(1)/已结算(3)/已作废(9) 都不行——前者是在途重复提交，后两者是终态。
     * 提示语带上当前状态名，便于前端直接展示。
     */
    ErrorCode CONTRACT_APPROVAL_STATUS_NOT_ALLOWED = new ErrorCode(1_101_040_000,
            "合同当前状态为【{}】，只有草稿或履行中的合同才能发起进度款审批");
    /**
     * 合同已存在审批中的进度款流程，拒绝重复发起
     *
     * 与「幂等回调」不是一回事：这里防的是**并发发起**（同一合同点两次提交 / 两个标签页），
     * 回调侧防的是**重复通知**。
     */
    ErrorCode CONTRACT_APPROVAL_ALREADY_RUNNING = new ErrorCode(1_101_040_001,
            "合同已有审批中的进度款流程（流程实例 {}），请勿重复提交");
    /**
     * 发起流程后流程实例编号为空
     *
     * 属数据一致性兜底：bpm 侧没有真正建实例时必须抛错回滚，
     * 否则业务表会留下「审批中但没有流程」的死状态（永远等不到回调）。
     */
    ErrorCode CONTRACT_APPROVAL_START_FAILED = new ErrorCode(1_101_040_002,
            "发起进度款审批失败，流程实例编号为空");
    /**
     * 回调携带的流程实例编号与业务表记录不一致
     *
     * 典型场景：合同驳回后重新发起新流程，旧流程的迟到回调到达——必须丢弃而不是按旧结果覆盖。
     */
    ErrorCode CONTRACT_APPROVAL_PROCESS_INSTANCE_MISMATCH = new ErrorCode(1_101_040_003,
            "审批回调与当前流程实例不匹配（回调 {}，当前 {}），已忽略");
    /**
     * 进度款申请金额非法
     *
     * 金额必须 > 0 且不超过「合同金额 - 已收付金额」的余额——
     * 超付会让 paid_amount 大于合同金额，台账对账口径直接失真（spec §5.1）。
     */
    ErrorCode CONTRACT_APPROVAL_AMOUNT_INVALID = new ErrorCode(1_101_040_004,
            "进度款申请金额必须大于 0 且不超过合同未收付余额（合同金额 {}，已收付 {}，本次 {}）");
    /**
     * 审批通过回调时，合同上没有在途的申请金额
     *
     * 提交时必写 pending_payment_amount，正常不会缺；缺了说明数据被外部改动，
     * 此时无法凭空生成流水（金额来源唯一），抛错留痕让运维按日志人工核对，
     * 而不是生成一条金额为 0 的假流水污染对账口径。
     */
    ErrorCode CONTRACT_APPROVAL_PENDING_AMOUNT_MISSING = new ErrorCode(1_101_040_005,
            "合同({}) 审批通过但缺少在途申请金额，无法生成收付款流水");

}
