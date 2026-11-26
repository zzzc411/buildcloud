package com.buildcloud.project.service.project;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberPageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectMemberDO;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Set;

/**
 * 项目成员 Service 接口
 *
 * 业务边界（spec v1.2 §5.1 用例「成员维护」+ 数据权限第二维度）：
 * 1. 同一用户在同一项目内只能有一条成员记录（uk_project_user），重复添加返回
 *    {@code PROJECT_MEMBER_USER_DUPLICATE} 而不是数据库唯一键异常；
 * 2. 成员一定要挂在存在的项目下（错误码 PROJECT_MEMBER_PROJECT_NOT_EXISTS）；
 * 3. {@link #getProjectIdSetByUserId} 是数据权限的取数入口：服务层用它拼
 *    「本部门项目 ∪ 我参与的项目」，是白名单维度的唯一来源。
 *
 * @author buildcloud
 */
public interface ProjectMemberService {

    /**
     * 添加成员
     *
     * @param createReqVO 添加信息
     * @return 编号
     */
    Long createMember(@Valid ProjectMemberSaveReqVO createReqVO);

    /**
     * 更新成员（角色变更走本方法，不新增行）
     *
     * @param updateReqVO 更新信息
     */
    void updateMember(@Valid ProjectMemberSaveReqVO updateReqVO);

    /**
     * 移除成员
     *
     * @param id 编号
     */
    void deleteMember(Long id);

    /**
     * 批量移除成员
     *
     * @param ids 编号数组
     */
    void deleteMemberListByIds(List<Long> ids);

    /**
     * 获得成员
     *
     * @param id 编号
     * @return 成员
     */
    ProjectMemberDO getMember(Long id);

    /**
     * 获得成员分页
     *
     * @param pageReqVO 分页查询
     * @return 成员分页
     */
    PageResult<ProjectMemberDO> getMemberPage(ProjectMemberPageReqVO pageReqVO);

    /**
     * 获得某项目的成员列表（按 projectRole 升序，不分页）
     *
     * @param projectId 项目编号
     * @return 成员列表
     */
    List<ProjectMemberDO> getMemberListByProjectId(Long projectId);

    /**
     * 获得用户参与的**项目编号集合**（数据权限第二维度：成员白名单）
     *
     * @param userId 用户编号
     * @return 项目编号集合（无参与项目时为空集合，不返回 null）
     */
    Set<Long> getProjectIdSetByUserId(Long userId);

}
