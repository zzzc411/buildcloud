package com.buildcloud.project.controller.admin.project;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.project.controller.admin.project.vo.ProjectPageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectRespVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectSaveReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectStatusRespVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectUpdateStatusReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectDO;
import com.buildcloud.project.service.project.ProjectService;
import com.buildcloud.project.service.project.ProjectStatusService;
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
 * 管理后台 - 项目
 *
 * URL 段与基座约定一致（对照 system 的 /system/dict-type）：首段为服务/模块标识，
 * 经框架 adminApi 前缀（`**.controller.admin.**` → `/admin-api`）对外暴露：
 * http://127.0.0.1:48080/admin-api/buildcloud-project/project/**
 *
 * 精修相对生成器产物的改动：去掉本次未启用的 Excel 导出（infra 生成器 unit-test/import 默认关闭，
 * 导出留到报表域统一实现，避免 project 服务强依赖 excel starter）；权限标识沿用 project:project:*。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 项目")
@RestController
@RequestMapping("/buildcloud-project/project")
@Validated
public class ProjectController {

    @Resource
    private ProjectService projectService;

    @Resource
    private ProjectStatusService projectStatusService;

    @PostMapping("/create")
    @Operation(summary = "创建项目")
    @PreAuthorize("@ss.hasPermission('project:project:create')")
    public CommonResult<Long> createProject(@Valid @RequestBody ProjectSaveReqVO createReqVO) {
        return success(projectService.createProject(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新项目")
    @PreAuthorize("@ss.hasPermission('project:project:update')")
    public CommonResult<Boolean> updateProject(@Valid @RequestBody ProjectSaveReqVO updateReqVO) {
        projectService.updateProject(updateReqVO);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "变更项目状态", description = "状态机：筹备(0) → 在建(1) ⇄ 停工(2) → 竣工(3)；非法流转返回 1_100_000_004")
    @PreAuthorize("@ss.hasPermission('project:project:update')")
    public CommonResult<Boolean> updateProjectStatus(@Valid @RequestBody ProjectUpdateStatusReqVO reqVO) {
        projectStatusService.updateProjectStatus(reqVO);
        return success(true);
    }

    @GetMapping("/list-next-status")
    @Operation(summary = "获得指定状态可流转到的目标状态", description = "供前端按钮置灰/下拉过滤，与后端校验同源")
    @Parameter(name = "status", description = "当前状态：0-筹备 1-在建 2-停工 3-竣工", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('project:project:query')")
    public CommonResult<List<ProjectStatusRespVO>> getNextStatusList(@RequestParam("status") Integer status) {
        // 状态机返回的是枚举集合，这里显式转 VO：ProjectStatusEnum 的字段名是 status/name，
        // 与 ProjectStatusRespVO 同名，BeanUtils 可直接映射
        List<ProjectStatusRespVO> list = projectStatusService.getNextStatusList(status).stream()
                .map(e -> BeanUtils.toBean(e, ProjectStatusRespVO.class))
                .toList();
        return success(list);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除项目")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('project:project:delete')")
    public CommonResult<Boolean> deleteProject(@RequestParam("id") Long id) {
        projectService.deleteProject(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除项目")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('project:project:delete')")
    public CommonResult<Boolean> deleteProjectList(@RequestParam("ids") List<Long> ids) {
        projectService.deleteProjectListByIds(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得项目")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('project:project:query')")
    public CommonResult<ProjectRespVO> getProject(@RequestParam("id") Long id) {
        ProjectDO project = projectService.getProject(id);
        return success(BeanUtils.toBean(project, ProjectRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得项目分页")
    @PreAuthorize("@ss.hasPermission('project:project:query')")
    public CommonResult<PageResult<ProjectRespVO>> getProjectPage(@Valid ProjectPageReqVO pageReqVO) {
        PageResult<ProjectDO> pageResult = projectService.getProjectPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, ProjectRespVO.class));
    }

}
