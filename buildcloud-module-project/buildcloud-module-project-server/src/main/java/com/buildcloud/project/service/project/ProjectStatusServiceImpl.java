package com.buildcloud.project.service.project;

import cn.hutool.core.util.ObjectUtil;
import com.buildcloud.project.controller.admin.project.vo.ProjectUpdateStatusReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import com.buildcloud.project.enums.project.ProjectStatusEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_STATUS_INVALID;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_STATUS_TRANSITION_INVALID;

/**
 * 项目状态机 Service 实现类
 *
 * 实现方式：状态流转表用两个 switch 表达（源状态 → 允许的目标状态集合），
 * 只有一处判定逻辑；{@link #getNextStatusList} 与 {@link #isTransitionAllowed} 共用同一张表，
 * 避免「前端按钮允许、后端拒绝」这类前后端口径分裂。
 *
 * @author buildcloud
 */
@Service
@Validated
public class ProjectStatusServiceImpl implements ProjectStatusService {

    @Resource
    private ProjectMapper projectMapper;

    @Override
    public void updateProjectStatus(ProjectUpdateStatusReqVO reqVO) {
        // 1. 校验项目存在，并拿到**库里的源状态**
        ProjectDO project = projectMapper.selectById(reqVO.getId());
        if (project == null) {
            throw exception(PROJECT_NOT_EXISTS);
        }
        Integer fromStatus = project.getStatus();
        Integer toStatus = reqVO.getStatus();
        // 2. 校验目标状态取值合法（不在枚举范围内 → 状态值非法，而非流转非法）
        ProjectStatusEnum targetStatus = parseStatus(toStatus);
        if (targetStatus == null) {
            throw exception(PROJECT_STATUS_INVALID);
        }
        // 3. 校验流转合法
        if (!isTransitionAllowed(fromStatus, toStatus)) {
            throw exception(PROJECT_STATUS_TRANSITION_INVALID,
                    getStatusName(fromStatus), getStatusName(toStatus));
        }
        // 4. 更新状态 + 回填实际日期（工程语义：开工/竣工日期随状态流转自动落库，人工仍可后续修正）
        ProjectDO updateObj = new ProjectDO();
        updateObj.setId(project.getId());
        updateObj.setStatus(toStatus);
        if (ObjectUtil.equal(toStatus, ProjectStatusEnum.CONSTRUCTING.getStatus())
                && project.getActualStartDate() == null) {
            updateObj.setActualStartDate(LocalDate.now());
        }
        if (ObjectUtil.equal(toStatus, ProjectStatusEnum.COMPLETED.getStatus())
                && project.getActualEndDate() == null) {
            updateObj.setActualEndDate(LocalDate.now());
        }
        projectMapper.updateById(updateObj);
    }

    @Override
    public boolean isTransitionAllowed(Integer fromStatus, Integer toStatus) {
        if (fromStatus == null || toStatus == null) {
            return false;
        }
        if (ObjectUtil.equal(fromStatus, toStatus)) {
            // 同状态重复流转不算合法流转：语义上是空操作，明确拒绝便于前端/调用方发现逻辑错误
            return false;
        }
        Set<ProjectStatusEnum> allowed = getNextStatusList(fromStatus);
        return allowed.stream().anyMatch(e -> ObjectUtil.equal(e.getStatus(), toStatus));
    }

    @Override
    public Set<ProjectStatusEnum> getNextStatusList(Integer currentStatus) {
        if (ObjectUtil.equal(currentStatus, ProjectStatusEnum.PREPARING.getStatus())) {
            // 筹备 → 在建
            return EnumSet.of(ProjectStatusEnum.CONSTRUCTING);
        }
        if (ObjectUtil.equal(currentStatus, ProjectStatusEnum.CONSTRUCTING.getStatus())) {
            // 在建 → 停工（暂停）/ 竣工
            return EnumSet.of(ProjectStatusEnum.SUSPENDED, ProjectStatusEnum.COMPLETED);
        }
        if (ObjectUtil.equal(currentStatus, ProjectStatusEnum.SUSPENDED.getStatus())) {
            // 停工 → 在建（复工）/ 竣工
            return EnumSet.of(ProjectStatusEnum.CONSTRUCTING, ProjectStatusEnum.COMPLETED);
        }
        // 竣工是终态；未知状态同样无后续
        return EnumSet.noneOf(ProjectStatusEnum.class);
    }

    // ========== 枚举辅助 ==========

    private ProjectStatusEnum parseStatus(Integer status) {
        if (status == null) {
            return null;
        }
        for (ProjectStatusEnum value : ProjectStatusEnum.values()) {
            if (ObjectUtil.equal(value.getStatus(), status)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 取状态中文名；未知状态返回「未知状态(x)」，保证错误提示可读而不是抛异常
     */
    private String getStatusName(Integer status) {
        ProjectStatusEnum value = parseStatus(status);
        return value != null ? value.getName() : "未知状态(" + status + ")";
    }

}
