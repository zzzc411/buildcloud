package com.buildcloud.project.enums.project;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目类型枚举
 *
 * 对应 bc_project.project_type 字段；由代码生成器产出的 DO 注释引用，人工补齐。
 * 后续材料/报表域如需联动分类，统一走此枚举，禁止魔法数字（AGENTS.md §4）。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ProjectTypeEnum {

    HOUSE_BUILD(1, "房建"),
    MUNICIPAL(2, "市政"),
    ROAD_BRIDGE(3, "路桥"),
    DECORATION(4, "装饰"),
    OTHER(9, "其他");

    /**
     * 类型值
     */
    private final Integer type;

    /**
     * 类型名
     */
    private final String name;

}
