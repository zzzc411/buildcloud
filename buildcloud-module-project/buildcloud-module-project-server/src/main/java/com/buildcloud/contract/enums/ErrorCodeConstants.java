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

}
