package com.buildcloud.project.enums.project;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目内角色枚举
 *
 * 对应 bc_project_member.project_role。当前用 tinyint 常量而非独立字典表
 * （ER 图 v0 §3.8 待复核项 3：角色扩展频繁时再抽表）。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ProjectRoleEnum {

    MANAGER(1, "项目经理"),
    TECH_LEADER(2, "技术负责人"),
    CONSTRUCTOR(3, "施工员"),
    SAFETY_OFFICER(4, "安全员"),
    MATERIAL_KEEPER(5, "材料员"),
    OTHER(9, "其他");

    /**
     * 角色值
     */
    private final Integer role;

    /**
     * 角色名
     */
    private final String name;

}
