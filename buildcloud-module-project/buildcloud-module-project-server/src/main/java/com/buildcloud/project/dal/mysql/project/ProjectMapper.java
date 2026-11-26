package com.buildcloud.project.dal.mysql.project;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.project.controller.admin.project.vo.ProjectPageReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目 Mapper
 *
 * 继承基座 {@link BaseMapperX}，复用其分页/单条查询封装；查询条件用 {@link LambdaQueryWrapperX}
 * 的 xxxIfPresent 系列，做到"参数为空则不加条件"（基座约定，勿改写成手写 XML）。
 *
 * @author buildcloud
 */
@Mapper
public interface ProjectMapper extends BaseMapperX<ProjectDO> {

    /**
     * 项目台账分页查询（spec §5.1 用例：分页 + 多条件）
     *
     * 说明：生成器默认把每一列都拼成查询条件。精修时收敛为台账真实需要的检索维度，
     * 避免无索引字段（address/remark 等）参与等值筛选导致全表扫描。
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<ProjectDO> selectPage(ProjectPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ProjectDO>()
                .eqIfPresent(ProjectDO::getProjectNo, reqVO.getProjectNo())
                .likeIfPresent(ProjectDO::getProjectName, reqVO.getProjectName())
                .eqIfPresent(ProjectDO::getProjectType, reqVO.getProjectType())
                .eqIfPresent(ProjectDO::getStatus, reqVO.getStatus())
                .eqIfPresent(ProjectDO::getProvinceCode, reqVO.getProvinceCode())
                .eqIfPresent(ProjectDO::getCityCode, reqVO.getCityCode())
                .likeIfPresent(ProjectDO::getBuildUnit, reqVO.getBuildUnit())
                .eqIfPresent(ProjectDO::getManagerUserId, reqVO.getManagerUserId())
                .eqIfPresent(ProjectDO::getDeptId, reqVO.getDeptId())
                .betweenIfPresent(ProjectDO::getPlanStartDate, reqVO.getPlanStartDate())
                .betweenIfPresent(ProjectDO::getPlanEndDate, reqVO.getPlanEndDate())
                .betweenIfPresent(ProjectDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(ProjectDO::getId));
    }

    /**
     * 按项目编号查询（唯一键 uk_project_no 的业务侧校验入口）
     *
     * @param projectNo 项目编号
     * @return 项目
     */
    default ProjectDO selectByProjectNo(String projectNo) {
        return selectOne(ProjectDO::getProjectNo, projectNo);
    }

}
