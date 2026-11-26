package com.buildcloud.project.service.project;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestonePageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestoneSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectMilestoneDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 项目里程碑 Service 接口
 *
 * 业务边界（spec v1.2 §5.1 用例「里程碑维护」）：
 * 1. 里程碑一律挂在项目下：新增/修改都校验项目存在（错误码 PROJECT_MILESTONE_PROJECT_NOT_EXISTS）；
 * 2. 权重用 decimal(5,2) 存百分比，单个 0~100；同一项目内合计不得超过 100
 *    （项目进度 = 已完成里程碑权重 / 全部里程碑权重，合计超 100 会让进度算出 >100%）；
 * 3. 列表按项目维度取（sort 升序），用于甘特图/进度看板。
 *
 * @author buildcloud
 */
public interface ProjectMilestoneService {

    /**
     * 创建里程碑
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createMilestone(@Valid ProjectMilestoneSaveReqVO createReqVO);

    /**
     * 更新里程碑
     *
     * @param updateReqVO 更新信息
     */
    void updateMilestone(@Valid ProjectMilestoneSaveReqVO updateReqVO);

    /**
     * 删除里程碑
     *
     * @param id 编号
     */
    void deleteMilestone(Long id);

    /**
     * 批量删除里程碑
     *
     * @param ids 编号数组
     */
    void deleteMilestoneListByIds(List<Long> ids);

    /**
     * 获得里程碑
     *
     * @param id 编号
     * @return 里程碑
     */
    ProjectMilestoneDO getMilestone(Long id);

    /**
     * 获得里程碑分页
     *
     * @param pageReqVO 分页查询
     * @return 里程碑分页
     */
    PageResult<ProjectMilestoneDO> getMilestonePage(ProjectMilestonePageReqVO pageReqVO);

    /**
     * 获得某项目的里程碑列表（按 sort 升序，不分页）
     *
     * @param projectId 项目编号
     * @return 里程碑列表
     */
    List<ProjectMilestoneDO> getMilestoneListByProjectId(Long projectId);

}
