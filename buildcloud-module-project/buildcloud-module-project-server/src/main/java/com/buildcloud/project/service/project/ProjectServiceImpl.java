package com.buildcloud.project.service.project;

import cn.hutool.core.util.ObjectUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.project.controller.admin.project.vo.ProjectPageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_NO_DUPLICATE;

/**
 * 项目 Service 实现类
 *
 * 精修相对生成器产物的改动：
 * 1. 生成器只做 CRUD，未做业务唯一性校验；这里补 project_no 重复校验（对应唯一键 uk_project_no）；
 * 2. 去掉生成器无用的 CollUtil/CollectionUtils 静态导入与未使用方法；
 * 3. 错误码由生成器的 TODO 占位改为 com.buildcloud.project.enums.ErrorCodeConstants 的实值。
 *
 * @author buildcloud
 */
@Service
@Validated
public class ProjectServiceImpl implements ProjectService {

    @Resource
    private ProjectMapper projectMapper;

    @Override
    public Long createProject(ProjectSaveReqVO createReqVO) {
        // 校验项目编号唯一
        validateProjectNoUnique(null, createReqVO.getProjectNo());
        // 插入
        ProjectDO project = BeanUtils.toBean(createReqVO, ProjectDO.class);
        projectMapper.insert(project);
        // 返回
        return project.getId();
    }

    @Override
    public void updateProject(ProjectSaveReqVO updateReqVO) {
        // 校验存在
        validateProjectExists(updateReqVO.getId());
        // 校验项目编号唯一（排除自身）
        validateProjectNoUnique(updateReqVO.getId(), updateReqVO.getProjectNo());
        // 更新
        ProjectDO updateObj = BeanUtils.toBean(updateReqVO, ProjectDO.class);
        projectMapper.updateById(updateObj);
    }

    @Override
    public void deleteProject(Long id) {
        // 校验存在
        validateProjectExists(id);
        // 删除
        projectMapper.deleteById(id);
    }

    @Override
    public void deleteProjectListByIds(List<Long> ids) {
        // 删除
        projectMapper.deleteByIds(ids);
    }

    @Override
    public ProjectDO getProject(Long id) {
        return projectMapper.selectById(id);
    }

    @Override
    public PageResult<ProjectDO> getProjectPage(ProjectPageReqVO pageReqVO) {
        return projectMapper.selectPage(pageReqVO);
    }

    private void validateProjectExists(Long id) {
        if (projectMapper.selectById(id) == null) {
            throw exception(PROJECT_NOT_EXISTS);
        }
    }

    /**
     * 校验项目编号在同一租户内唯一
     *
     * 租户隔离由基座多租户插件（biz-tenant）在 SQL 层自动追加 tenant_id 条件，此处无需显式过滤。
     *
     * @param id        项目编号（更新时排除自身；新增传 null）
     * @param projectNo 业务项目编号
     */
    private void validateProjectNoUnique(Long id, String projectNo) {
        ProjectDO project = projectMapper.selectByProjectNo(projectNo);
        if (project == null) {
            return;
        }
        if (ObjectUtil.notEqual(project.getId(), id)) {
            throw exception(PROJECT_NO_DUPLICATE);
        }
    }

}
