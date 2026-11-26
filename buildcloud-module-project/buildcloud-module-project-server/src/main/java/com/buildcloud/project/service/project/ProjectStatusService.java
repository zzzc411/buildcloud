package com.buildcloud.project.service.project;

import com.buildcloud.project.controller.admin.project.vo.ProjectUpdateStatusReqVO;
import com.buildcloud.project.enums.project.ProjectStatusEnum;
import jakarta.validation.Valid;

import java.util.Set;

/**
 * 项目状态机 Service
 *
 * 严格按 spec v1.2 §5.1 的状态机实现：`筹备(0) → 在建(1) ⇄ 停工(2) → 竣工(3)`。
 * 反向 / 跳跃 / 重复流转一律拒绝（错误码 PROJECT_STATUS_TRANSITION_INVALID）。
 *
 * | 源状态 | 允许的目标状态 |
 * | --- | --- |
 * | 筹备(0) | 在建(1) |
 * | 在建(1) | 停工(2)、竣工(3) |
 * | 停工(2) | 在建(1)（复工）、竣工(3) |
 * | 竣工(3) | 无（终态） |
 *
 * 设计取舍：
 * 1. 源状态从**库里读**，不接受前端传入的源状态——否则并发下两个请求各自基于旧状态判定，
 *    会出现「已竣工的项目被另一个请求改成在建」的双写；
 * 2. 状态机集中在本 Service，Controller 与其它 Service 不得散落 if-else 判断（spec §5.1）；
 * 3. 停工 → 竣工 允许：工程实务中存在「停工时直接竣工验收」的情形，spec 的状态图写作
 *    「在建 ⇄ 停工 → 竣工」箭头指向竣工，与「在建/停工 → 竣工」等价。
 *
 * @author buildcloud
 */
public interface ProjectStatusService {

    /**
     * 变更项目状态（含合法性校验与实际日期回填）
     *
     * 副作用（贴合工程语义）：
     * - 进入「在建」且实际开工日期为空 → 回填当天；
     * - 进入「竣工」且实际竣工日期为空 → 回填当天。
     *
     * @param reqVO 变更请求（项目编号 + 目标状态）
     */
    void updateProjectStatus(@Valid ProjectUpdateStatusReqVO reqVO);

    /**
     * 判断状态流转是否合法（纯函数，不查库）
     *
     * @param fromStatus 源状态
     * @param toStatus   目标状态
     * @return 是否合法
     */
    boolean isTransitionAllowed(Integer fromStatus, Integer toStatus);

    /**
     * 获得某状态下允许流转到的目标状态集合（供前端按钮置灰/下拉过滤）
     *
     * @param currentStatus 当前状态
     * @return 允许的目标状态集合（终态返回空集合）
     */
    Set<ProjectStatusEnum> getNextStatusList(Integer currentStatus);

}
