package com.buildcloud.project.controller.admin.project;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestonePageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestoneRespVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMilestoneSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectMilestoneDO;
import com.buildcloud.project.service.project.ProjectMilestoneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 项目里程碑
 *
 * URL 段遵循 AGENTS R-9：首段 = 服务标识 {@code buildcloud-project}，第二段 = 域 {@code milestone}；
 * 经框架 adminApi 前缀（`**.controller.admin.**` → `/admin-api`）对外暴露：
 * http://127.0.0.1:48080/admin-api/buildcloud-project/milestone/**
 *
 * 权限标识沿用 {@code project:milestone:*}（菜单 SQL 里与 9000 段的按钮一一对应）。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 项目里程碑")
@RestController
@RequestMapping("/buildcloud-project/milestone")
@Validated
public class ProjectMilestoneController {

    @Resource
    private ProjectMilestoneService projectMilestoneService;

    @PostMapping("/create")
    @Operation(summary = "创建项目里程碑")
    @PreAuthorize("@ss.hasPermission('project:milestone:create')")
    public CommonResult<Long> createMilestone(@Valid @RequestBody ProjectMilestoneSaveReqVO createReqVO) {
        return success(projectMilestoneService.createMilestone(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新项目里程碑")
    @PreAuthorize("@ss.hasPermission('project:milestone:update')")
    public CommonResult<Boolean> updateMilestone(@Valid @RequestBody ProjectMilestoneSaveReqVO updateReqVO) {
        projectMilestoneService.updateMilestone(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除项目里程碑")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('project:milestone:delete')")
    public CommonResult<Boolean> deleteMilestone(@RequestParam("id") Long id) {
        projectMilestoneService.deleteMilestone(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除项目里程碑")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('project:milestone:delete')")
    public CommonResult<Boolean> deleteMilestoneList(@RequestParam("ids") List<Long> ids) {
        projectMilestoneService.deleteMilestoneListByIds(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得项目里程碑")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('project:milestone:query')")
    public CommonResult<ProjectMilestoneRespVO> getMilestone(@RequestParam("id") Long id) {
        ProjectMilestoneDO milestone = projectMilestoneService.getMilestone(id);
        return success(BeanUtils.toBean(milestone, ProjectMilestoneRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得项目里程碑分页")
    @PreAuthorize("@ss.hasPermission('project:milestone:query')")
    public CommonResult<PageResult<ProjectMilestoneRespVO>> getMilestonePage(
            @Valid ProjectMilestonePageReqVO pageReqVO) {
        PageResult<ProjectMilestoneDO> pageResult = projectMilestoneService.getMilestonePage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, ProjectMilestoneRespVO.class));
    }

    @GetMapping("/list-by-project")
    @Operation(summary = "获得指定项目的里程碑列表", description = "按 sort 升序，供甘特图/进度看板一次性拉取")
    @Parameter(name = "projectId", description = "项目编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('project:milestone:query')")
    public CommonResult<List<ProjectMilestoneRespVO>> getMilestoneListByProjectId(
            @RequestParam("projectId") Long projectId) {
        List<ProjectMilestoneDO> list = projectMilestoneService.getMilestoneListByProjectId(projectId);
        return success(BeanUtils.toBean(list, ProjectMilestoneRespVO.class));
    }

}
