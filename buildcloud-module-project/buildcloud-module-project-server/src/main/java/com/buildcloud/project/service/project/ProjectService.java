package com.buildcloud.project.service.project;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.project.controller.admin.project.vo.ProjectPageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 项目 Service 接口
 *
 * @author buildcloud
 */
public interface ProjectService {

    /**
     * 创建项目
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createProject(@Valid ProjectSaveReqVO createReqVO);

    /**
     * 更新项目
     *
     * @param updateReqVO 更新信息
     */
    void updateProject(@Valid ProjectSaveReqVO updateReqVO);

    /**
     * 删除项目
     *
     * @param id 编号
     */
    void deleteProject(Long id);

    /**
     * 批量删除项目
     *
     * @param ids 编号数组
     */
    void deleteProjectListByIds(List<Long> ids);

    /**
     * 获得项目
     *
     * @param id 编号
     * @return 项目
     */
    ProjectDO getProject(Long id);

    /**
     * 获得项目分页
     *
     * @param pageReqVO 分页查询
     * @return 项目分页
     */
    PageResult<ProjectDO> getProjectPage(ProjectPageReqVO pageReqVO);

}
