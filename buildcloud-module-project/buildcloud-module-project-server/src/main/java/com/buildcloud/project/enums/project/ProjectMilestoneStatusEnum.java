package com.buildcloud.project.enums.project;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 项目里程碑状态枚举
 *
 * 对应 bc_project_milestone.status；与项目状态机（{@link ProjectStatusEnum}）不同，
 * 里程碑状态是**展示态**而非严格状态机：实际日期到达/延期由进度看板与 Job 回写，
 * 人工也只做「未开始 → 进行中 → 已完成」与「延期」修正。故本枚举不提供流转校验。
 *
 * @author buildcloud
 */
@Getter
@AllArgsConstructor
public enum ProjectMilestoneStatusEnum {

    NOT_STARTED(0, "未开始"),
    IN_PROGRESS(1, "进行中"),
    COMPLETED(2, "已完成"),
    DELAYED(3, "已延期");

    /**
     * 状态值
     */
    private final Integer status;

    /**
     * 状态名
     */
    private final String name;

}
