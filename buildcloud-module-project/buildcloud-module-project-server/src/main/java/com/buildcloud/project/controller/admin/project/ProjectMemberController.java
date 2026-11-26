package com.buildcloud.project.controller.admin.project;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberPageReqVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberRespVO;
import com.buildcloud.project.controller.admin.project.vo.ProjectMemberSaveReqVO;
import com.buildcloud.project.dal.dataobject.project.ProjectMemberDO;
import com.buildcloud.project.service.project.ProjectMemberService;
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
import java.util.Set;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 项目成员
 *
 * URL 段遵循 AGENTS R-9：`/buildcloud-project/member`；
 * 经网关访问：http://127.0.0.1:48080/admin-api/buildcloud-project/member/**
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 项目成员")
@RestController
@RequestMapping("/buildcloud-project/member")
@Validated
public class ProjectMemberController {

    @Resource
    private ProjectMemberService projectMemberService;

    @PostMapping("/create")
    @Operation(summary = "添加项目成员")
    @PreAuthorize("@ss.hasPermission('project:member:create')")
    public CommonResult<Long> createMember(@Valid @RequestBody ProjectMemberSaveReqVO createReqVO) {
        return success(projectMemberService.createMember(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新项目成员", description = "角色变更走本接口（同一用户同一项目不新增行）")
    @PreAuthorize("@ss.hasPermission('project:member:update')")
    public CommonResult<Boolean> updateMember(@Valid @RequestBody ProjectMemberSaveReqVO updateReqVO) {
        projectMemberService.updateMember(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "移除项目成员")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('project:member:delete')")
    public CommonResult<Boolean> deleteMember(@RequestParam("id") Long id) {
        projectMemberService.deleteMember(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量移除项目成员")
    @Parameter(name = "ids", description = "编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('project:member:delete')")
    public CommonResult<Boolean> deleteMemberList(@RequestParam("ids") List<Long> ids) {
        projectMemberService.deleteMemberListByIds(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得项目成员")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('project:member:query')")
    public CommonResult<ProjectMemberRespVO> getMember(@RequestParam("id") Long id) {
        ProjectMemberDO member = projectMemberService.getMember(id);
        return success(BeanUtils.toBean(member, ProjectMemberRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得项目成员分页")
    @PreAuthorize("@ss.hasPermission('project:member:query')")
    public CommonResult<PageResult<ProjectMemberRespVO>> getMemberPage(@Valid ProjectMemberPageReqVO pageReqVO) {
        PageResult<ProjectMemberDO> pageResult = projectMemberService.getMemberPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, ProjectMemberRespVO.class));
    }

    @GetMapping("/list-by-project")
    @Operation(summary = "获得指定项目的成员列表", description = "按项目内角色升序，返回 userId + projectRole")
    @Parameter(name = "projectId", description = "项目编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('project:member:query')")
    public CommonResult<List<ProjectMemberRespVO>> getMemberListByProjectId(
            @RequestParam("projectId") Long projectId) {
        List<ProjectMemberDO> list = projectMemberService.getMemberListByProjectId(projectId);
        return success(BeanUtils.toBean(list, ProjectMemberRespVO.class));
    }

    @GetMapping("/list-project-id-by-user")
    @Operation(summary = "获得用户参与的项目编号集合",
            description = "数据权限第二维度（成员白名单）的取数入口；无参与项目时返回空数组")
    @Parameter(name = "userId", description = "用户编号，不传则取当前登录用户", example = "1")
    @PreAuthorize("@ss.hasPermission('project:member:query')")
    public CommonResult<Set<Long>> getProjectIdListByUserId(@RequestParam(value = "userId", required = false) Long userId) {
        return success(projectMemberService.getProjectIdSetByUserId(userId));
    }

}
