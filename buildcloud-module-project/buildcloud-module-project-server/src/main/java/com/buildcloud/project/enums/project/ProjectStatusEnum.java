package com.buildcloud.project.enums.project;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目状态枚举
 *
 * 状态机（spec v1.2 §5.1）：筹备 → 在建 ⇄ 停工 → 竣工。
 * 状态流转校验在 ProjectServiceImpl 中集中实现，禁止在 Controller 内散落判断。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ProjectStatusEnum {

    PREPARING(0, "筹备"),
    CONSTRUCTING(1, "在建"),
    SUSPENDED(2, "停工"),
    COMPLETED(3, "竣工");

    /**
     * 状态值
     */
    private final Integer status;

    /**
     * 状态名
     */
    private final String name;

}
