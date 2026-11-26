package com.buildcloud.project.dal.mysql.project;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestonePageReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectMilestoneDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * 项目里程碑 Mapper
 *
 * 继承基座 {@link BaseMapperX}；条件用 {@link LambdaQueryWrapperX} 的 xxxIfPresent 系列
 * （参数为空则不加条件，基座约定，勿改写成手写 XML）。
 *
 * @author buildcloud
 */
@Mapper
public interface ProjectMilestoneMapper extends BaseMapperX<ProjectMilestoneDO> {

    /**
     * 里程碑分页查询
     *
     * 排序规则：先按 sort 升序（甘特图/看板的人工顺序），再按 plan_date 升序、
     * id 升序兜底——三者一起才能保证同一 sort 下前端渲染顺序稳定（H2/MySQL 对并列行不保证顺序）。
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<ProjectMilestoneDO> selectPage(ProjectMilestonePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ProjectMilestoneDO>()
                .eqIfPresent(ProjectMilestoneDO::getProjectId, reqVO.getProjectId())
                .likeIfPresent(ProjectMilestoneDO::getMilestoneName, reqVO.getMilestoneName())
                .eqIfPresent(ProjectMilestoneDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(ProjectMilestoneDO::getPlanDate, reqVO.getPlanDate())
                .orderByAsc(ProjectMilestoneDO::getSort)
                .orderByAsc(ProjectMilestoneDO::getPlanDate)
                .orderByAsc(ProjectMilestoneDO::getId));
    }

    /**
     * 按项目查询里程碑列表（不分页，供甘特图/进度看板一次性拉取）
     *
     * @param projectId 项目编号
     * @return 里程碑列表（sort 升序）
     */
    default List<ProjectMilestoneDO> selectListByProjectId(Long projectId) {
        return selectList(new LambdaQueryWrapperX<ProjectMilestoneDO>()
                .eq(ProjectMilestoneDO::getProjectId, projectId)
                .orderByAsc(ProjectMilestoneDO::getSort)
                .orderByAsc(ProjectMilestoneDO::getId));
    }

    /**
     * 按项目查询里程碑列表（排除指定编号）
     *
     * 权重合计校验用：更新时要把自身排除，否则「把某条权重从 20 改到 20」会被自己算进合计。
     *
     * @param projectId   项目编号
     * @param excludeId   需要排除的里程碑编号（新增时传 null）
     * @return 里程碑列表
     */
    default List<ProjectMilestoneDO> selectListByProjectIdExcludeId(Long projectId, Long excludeId) {
        return selectList(new LambdaQueryWrapperX<ProjectMilestoneDO>()
                .eq(ProjectMilestoneDO::getProjectId, projectId)
                .neIfPresent(ProjectMilestoneDO::getId, excludeId));
    }

    /**
     * 按项目编号删除里程碑（项目删除时级联清理）
     *
     * @param projectId 项目编号
     */
    default void deleteByProjectId(Long projectId) {
        delete(new LambdaQueryWrapperX<ProjectMilestoneDO>().eq(ProjectMilestoneDO::getProjectId, projectId));
    }

    /**
     * 按项目编号批量删除里程碑（项目批量删除时级联清理）
     *
     * @param projectIds 项目编号集合
     */
    default void deleteByProjectIds(Collection<Long> projectIds) {
        delete(new LambdaQueryWrapperX<ProjectMilestoneDO>().in(ProjectMilestoneDO::getProjectId, projectIds));
    }

}
