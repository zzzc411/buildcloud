package com.buildcloud.project.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * Project 项目子域错误码枚举类
 *
 * project 模块，使用 1-100-000-000 段（spec v1.2 §7.2 段位分配）；
 * 注意：contract 子域使用 1-101-xxx，两者同模块但段位独立，避免冲突。
 *
 * @author buildcloud
 */
public interface ErrorCodeConstants {

    // ========== 项目 1-100-000-000 ==========
    ErrorCode PROJECT_NOT_EXISTS = new ErrorCode(1_100_000_000, "项目不存在");
    ErrorCode PROJECT_NO_DUPLICATE = new ErrorCode(1_100_000_001, "项目编号已存在");
    ErrorCode PROJECT_NOT_PREPARE = new ErrorCode(1_100_000_002, "项目不允许修改，不是筹备中的项目");
    ErrorCode PROJECT_STATUS_INVALID = new ErrorCode(1_100_000_003, "项目状态流转不正确");

}
