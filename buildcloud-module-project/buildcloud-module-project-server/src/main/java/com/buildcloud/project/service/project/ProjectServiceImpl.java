package com.buildcloud.project.service.project;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.project.controller.admin.project.vo.ProjectPageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import com.buildcloud.project.dal.mysql.project.ProjectMemberMapper;
import com.buildcloud.project.dal.mysql.project.ProjectMilestoneMapper;
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
 * 3. 错误码由生成器的 TODO 占位改为 com.buildcloud.project.enums.ErrorCodeConstants 的实值；
 * 4. W3-P2 新增：删除项目时级联清理里程碑与成员（详见 {@link #deleteProject}）。
 *
 * 说明：状态变更**不走** {@link #updateProject}（避免通用更新把状态随手改掉），
 * 一律走 {@link ProjectStatusService#updateProjectStatus}，状态机校验集中在那里（spec §5.1）。
 *
 * @author buildcloud
 */
@Service
@Validated
public class ProjectServiceImpl implements ProjectService {

    @Resource
    private ProjectMapper projectMapper;

    @Resource
    private ProjectMilestoneMapper projectMilestoneMapper;

    @Resource
    private ProjectMemberMapper projectMemberMapper;

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
        // 关键：表单更新**不允许**改状态，状态只能走 ProjectStatusService 的状态机。
        // 置 null 后 MyBatis-Plus 默认字段策略（NOT_NULL）会跳过 status 列，从而实现「不动状态」。
        // 否则一个普通编辑请求就能把「竣工」改回「筹备」，状态机的所有校验形同虚设。
        updateObj.setStatus(null);
        projectMapper.updateById(updateObj);
    }

    @Override
    public void deleteProject(Long id) {
        // 校验存在
        validateProjectExists(id);
        // 删除
        projectMapper.deleteById(id);
        // 级联清理子表：里程碑与成员。二者脱离项目即失去意义，
        // 残留会让「成员白名单」把用户授权到不存在的项目、甘特图出现孤儿节点。
        projectMilestoneMapper.deleteByProjectId(id);
        projectMemberMapper.deleteByProjectId(id);
    }

    @Override
    public void deleteProjectListByIds(List<Long> ids) {
        // 删除
        projectMapper.deleteByIds(ids);
        // 级联清理子表（同上）
        if (CollUtil.isNotEmpty(ids)) {
            projectMilestoneMapper.deleteByProjectIds(ids);
            projectMemberMapper.deleteByProjectIds(ids);
        }
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
