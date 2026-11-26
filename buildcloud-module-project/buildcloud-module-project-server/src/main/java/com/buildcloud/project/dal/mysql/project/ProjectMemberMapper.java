package com.buildcloud.project.dal.mysql.project;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberPageReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectMemberDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 项目成员 Mapper
 *
 * 继承基座 {@link BaseMapperX}；条件用 {@link LambdaQueryWrapperX}。
 *
 * @author buildcloud
 */
@Mapper
public interface ProjectMemberMapper extends BaseMapperX<ProjectMemberDO> {

    /**
     * 成员分页查询
     *
     * 排序：projectRole 升序（项目经理在前，符合成员列表的阅读习惯），再按 id 升序兜底。
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<ProjectMemberDO> selectPage(ProjectMemberPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ProjectMemberDO>()
                .eqIfPresent(ProjectMemberDO::getProjectId, reqVO.getProjectId())
                .eqIfPresent(ProjectMemberDO::getUserId, reqVO.getUserId())
                .eqIfPresent(ProjectMemberDO::getProjectRole, reqVO.getProjectRole())
                .orderByAsc(ProjectMemberDO::getProjectRole)
                .orderByAsc(ProjectMemberDO::getId));
    }

    /**
     * 按项目查询成员列表（不分页）
     *
     * @param projectId 项目编号
     * @return 成员列表
     */
    default List<ProjectMemberDO> selectListByProjectId(Long projectId) {
        return selectList(new LambdaQueryWrapperX<ProjectMemberDO>()
                .eq(ProjectMemberDO::getProjectId, projectId)
                .orderByAsc(ProjectMemberDO::getProjectRole)
                .orderByAsc(ProjectMemberDO::getId));
    }

    /**
     * 按用户查询其参与的全部项目成员记录（成员白名单数据权限的取数入口）
     *
     * @param userId 用户编号
     * @return 成员记录列表
     */
    default List<ProjectMemberDO> selectListByUserId(Long userId) {
        return selectList(ProjectMemberDO::getUserId, userId);
    }

    /**
     * 按用户查询其参与的项目编号集合
     *
     * 供数据权限拼接 IN 条件：非超管用户能看到「本部门项目 ∪ 自己参与的项目」。
     *
     * @param userId 用户编号
     * @return 项目编号集合（可能为空集合，绝不能返回 null）
     */
    default List<Long> selectProjectIdListByUserId(Long userId) {
        return selectListByUserId(userId).stream().map(ProjectMemberDO::getProjectId).distinct().toList();
    }

    /**
     * 按「项目 + 用户」查询唯一成员记录
     *
     * 唯一键 uk_project_user 的业务侧前置校验入口（重复添加时返回业务错误码而非 DB 异常）。
     *
     * @param projectId 项目编号
     * @param userId    用户编号
     * @return 成员记录
     */
    default ProjectMemberDO selectByProjectIdAndUserId(Long projectId, Long userId) {
        return selectOne(new LambdaQueryWrapperX<ProjectMemberDO>()
                .eq(ProjectMemberDO::getProjectId, projectId)
                .eq(ProjectMemberDO::getUserId, userId));
    }

    /**
     * 按项目编号批量删除成员（项目删除时级联清理，避免孤儿成员记录污染白名单）
     *
     * @param projectIds 项目编号集合
     */
    default void deleteByProjectIds(Collection<Long> projectIds) {
        delete(new LambdaQueryWrapperX<ProjectMemberDO>().in(ProjectMemberDO::getProjectId, projectIds));
    }

    /**
     * 按项目编号删除成员（单个项目）
     *
     * @param projectId 项目编号
     */
    default void deleteByProjectId(Long projectId) {
        delete(new LambdaQueryWrapperX<ProjectMemberDO>().eq(ProjectMemberDO::getProjectId, projectId));
    }

}
