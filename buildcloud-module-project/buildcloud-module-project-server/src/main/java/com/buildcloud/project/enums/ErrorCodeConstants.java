package com.buildcloud.project.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * Project 项目子域错误码枚举类
 *
 * project 模块，使用 1-100-000-000 段（spec v1.2 §7.2 段位分配）；
 * 注意：contract 子域使用 1-101-xxx，两者同模块但段位独立，避免冲突。
 *
 * 段位内编号约定（与 contract 子域同构，便于两个子域对照阅读）：
 * - 000 段：项目主体（bc_project）crud 与状态机；
 * - 010 段：里程碑（bc_project_milestone）；
 * - 020 段：项目成员（bc_project_member）。
 *
 * @author buildcloud
 */
public interface ErrorCodeConstants {

    // ========== 项目 1-100-000-000 ==========
    ErrorCode PROJECT_NOT_EXISTS = new ErrorCode(1_100_000_000, "项目不存在");
    ErrorCode PROJECT_NO_DUPLICATE = new ErrorCode(1_100_000_001, "项目编号已存在");
    ErrorCode PROJECT_NOT_PREPARE = new ErrorCode(1_100_000_002, "项目不允许修改，不是筹备中的项目");
    ErrorCode PROJECT_STATUS_INVALID = new ErrorCode(1_100_000_003, "项目状态流转不正确");
    /**
     * 状态机非法流转：如「筹备 → 竣工」跳级、竣工后再次流转、同状态重复流转
     *
     * 与 {@link #PROJECT_STATUS_INVALID} 的区别：后者是「状态值非法（不在枚举范围内）」，
     * 本错误码是「状态值合法但当前源状态不允许流转到目标状态」，错误提示带上具体流转，便于前端定位。
     */
    ErrorCode PROJECT_STATUS_TRANSITION_INVALID = new ErrorCode(1_100_000_004, "项目状态不允许从【{}】流转到【{}】");

    // ========== 里程碑 1-100-010-000 ==========
    ErrorCode PROJECT_MILESTONE_NOT_EXISTS = new ErrorCode(1_100_010_000, "项目里程碑不存在");
    ErrorCode PROJECT_MILESTONE_PROJECT_NOT_EXISTS = new ErrorCode(1_100_010_001, "里程碑所属的项目不存在");
    ErrorCode PROJECT_MILESTONE_WEIGHT_INVALID = new ErrorCode(1_100_010_002, "里程碑权重必须在 0~100 之间");
    ErrorCode PROJECT_MILESTONE_WEIGHT_EXCEED = new ErrorCode(1_100_010_003, "同一项目下里程碑权重合计不能超过 100（当前合计 {}）");

    // ========== 项目成员 1-100-020-000 ==========
    ErrorCode PROJECT_MEMBER_NOT_EXISTS = new ErrorCode(1_100_020_000, "项目成员不存在");
    ErrorCode PROJECT_MEMBER_USER_DUPLICATE = new ErrorCode(1_100_020_001, "该用户已是本项目成员");
    ErrorCode PROJECT_MEMBER_PROJECT_NOT_EXISTS = new ErrorCode(1_100_020_002, "成员所属的项目不存在");
    ErrorCode PROJECT_MEMBER_ROLE_INVALID = new ErrorCode(1_100_020_003, "项目内角色取值不正确");

}
