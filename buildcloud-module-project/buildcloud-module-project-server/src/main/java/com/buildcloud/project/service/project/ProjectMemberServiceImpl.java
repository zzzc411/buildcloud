package com.buildcloud.project.service.project;

import cn.hutool.core.util.ObjectUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberPageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectMemberDO;
import com.buildcloud.project.dal.mysql.project.ProjectMapper;
import com.buildcloud.project.dal.mysql.project.ProjectMemberMapper;
import com.buildcloud.project.enums.project.ProjectRoleEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MEMBER_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MEMBER_PROJECT_NOT_EXISTS;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MEMBER_ROLE_INVALID;
import static com.buildcloud.project.enums.ErrorCodeConstants.PROJECT_MEMBER_USER_DUPLICATE;

/**
 * 项目成员 Service 实现类
 *
 * 精修要点（相对生成器产物）：
 * 1. 补唯一键前置校验：重复添加返回业务错误码（生成器直接撞 DB 唯一索引，前端拿到 500）；
 * 2. 补角色取值校验（枚举白名单），避免脏值进入成员白名单判定；
 * 3. 补项目存在性校验，防止孤儿成员记录污染数据权限；
 * 4. 新增 {@link #getProjectIdSetByUserId} —— 数据权限第二维度的唯一取数入口。
 *
 * @author buildcloud
 */
@Service
@Validated
public class ProjectMemberServiceImpl implements ProjectMemberService {

    @Resource
    private ProjectMemberMapper projectMemberMapper;

    @Resource
    private ProjectMapper projectMapper;

    @Override
    public Long createMember(ProjectMemberSaveReqVO createReqVO) {
        // 校验项目存在
        validateProjectExists(createReqVO.getProjectId());
        // 校验角色取值
        validateProjectRole(createReqVO.getProjectRole());
        // 校验成员不重复（uk_project_user）
        validateMemberUserUnique(null, createReqVO.getProjectId(), createReqVO.getUserId());
        // 插入
        ProjectMemberDO member = BeanUtils.toBean(createReqVO, ProjectMemberDO.class);
        projectMemberMapper.insert(member);
        // 返回
        return member.getId();
    }

    @Override
    public void updateMember(ProjectMemberSaveReqVO updateReqVO) {
        // 校验存在
        validateMemberExists(updateReqVO.getId());
        // 校验项目存在
        validateProjectExists(updateReqVO.getProjectId());
        // 校验角色取值
        validateProjectRole(updateReqVO.getProjectRole());
        // 校验成员不重复（排除自身）
        validateMemberUserUnique(updateReqVO.getId(), updateReqVO.getProjectId(), updateReqVO.getUserId());
        // 更新
        ProjectMemberDO updateObj = BeanUtils.toBean(updateReqVO, ProjectMemberDO.class);
        projectMemberMapper.updateById(updateObj);
    }

    @Override
    public void deleteMember(Long id) {
        // 校验存在
        validateMemberExists(id);
        // 删除
        projectMemberMapper.deleteById(id);
    }

    @Override
    public void deleteMemberListByIds(List<Long> ids) {
        // 删除
        projectMemberMapper.deleteByIds(ids);
    }

    @Override
    public ProjectMemberDO getMember(Long id) {
        return projectMemberMapper.selectById(id);
    }

    @Override
    public PageResult<ProjectMemberDO> getMemberPage(ProjectMemberPageReqVO pageReqVO) {
        return projectMemberMapper.selectPage(pageReqVO);
    }

    @Override
    public List<ProjectMemberDO> getMemberListByProjectId(Long projectId) {
        return projectMemberMapper.selectListByProjectId(projectId);
    }

    @Override
    public Set<Long> getProjectIdSetByUserId(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return projectMemberMapper.selectProjectIdListByUserId(userId).stream()
                .filter(ObjectUtil::isNotNull)
                .collect(Collectors.toSet());
    }

    // ========== 校验方法 ==========

    private void validateMemberExists(Long id) {
        if (projectMemberMapper.selectById(id) == null) {
            throw exception(PROJECT_MEMBER_NOT_EXISTS);
        }
    }

    /**
     * 校验项目存在
     *
     * 成员记录脱离项目即失去意义：既会让成员白名单把用户「授权」到不存在的项目，
     * 也会让项目删除后残留孤儿行。租户条件由基座多租户插件自动追加。
     *
     * @param projectId 项目编号
     */
    private void validateProjectExists(Long projectId) {
        if (projectId == null || projectMapper.selectById(projectId) == null) {
            throw exception(PROJECT_MEMBER_PROJECT_NOT_EXISTS);
        }
    }

    /**
     * 校验项目内角色取值在枚举白名单内
     *
     * @param projectRole 项目内角色
     */
    private void validateProjectRole(Integer projectRole) {
        boolean matched = Arrays.stream(ProjectRoleEnum.values())
                .anyMatch(e -> ObjectUtil.equal(e.getRole(), projectRole));
        if (!matched) {
            throw exception(PROJECT_MEMBER_ROLE_INVALID);
        }
    }

    /**
     * 校验「同一项目内同一用户只有一条成员记录」（uk_project_user）
     *
     * @param id        成员编号（更新时排除自身；新增传 null）
     * @param projectId 项目编号
     * @param userId    用户编号
     */
    private void validateMemberUserUnique(Long id, Long projectId, Long userId) {
        ProjectMemberDO member = projectMemberMapper.selectByProjectIdAndUserId(projectId, userId);
        if (member == null) {
            return;
        }
        if (ObjectUtil.notEqual(member.getId(), id)) {
            throw exception(PROJECT_MEMBER_USER_DUPLICATE);
        }
    }

}
