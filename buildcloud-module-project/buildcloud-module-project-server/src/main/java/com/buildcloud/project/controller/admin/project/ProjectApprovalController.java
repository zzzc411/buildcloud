package com.buildcloud.project.controller.admin.project;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.buildcloud.project.controller.admin.project.vo.ProjectApprovalStatusRespVO;
import com.buildcloud.project.service.project.ProjectApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 项目立项审批
 *
 * URL 遵循 R-9：首段为服务标识 `/buildcloud-project`，经框架 adminApi 前缀后对外为
 * `http://127.0.0.1:48080/admin-api/buildcloud-project/project/approval/**`。
 *
 * 为什么单独建 Controller 而不并进 ProjectController：
 * 立项审批是**跨服务协作**的入口（依赖 bpm-server），与项目台账的纯 CRUD 生命周期不同；
 * 分开后单测只需 mock BPM 依赖，不必让整个 ProjectController 的测试都背着 Feign 客户端。
 *
 * 注意：真正的审批动作（通过/驳回）**不在本服务**，由基座 bpm 的
 * `/admin-api/bpm/task/approve`、`/admin-api/bpm/task/reject` 处理
 * （读码笔记 §3.3），本服务只负责发起与接收结果。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 项目立项审批")
@RestController
@RequestMapping("/buildcloud-project/project/approval")
@Validated
public class ProjectApprovalController {

    @Resource
    private ProjectApprovalService projectApprovalService;

    @PostMapping("/submit")
    @Operation(summary = "提交项目立项审批",
            description = "校验项目为筹备状态且无审批中的流程，发起 project-approve 流程实例并回写流程实例编号；"
                    + "失败返回 1_100_030_000/001/002")
    @Parameter(name = "projectId", description = "项目编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('project:project:update')")
    public CommonResult<String> submitApproval(@RequestParam("projectId") Long projectId) {
        return success(projectApprovalService.submitApproval(projectId));
    }

    @GetMapping("/status")
    @Operation(summary = "获得项目立项审批状态",
            description = "返回流程实例编号、审批状态（-1未开始/1审批中/2通过/3不通过/4已取消）与项目业务状态")
    @Parameter(name = "projectId", description = "项目编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('project:project:query')")
    public CommonResult<ProjectApprovalStatusRespVO> getApprovalStatus(@RequestParam("projectId") Long projectId) {
        return success(projectApprovalService.getApprovalStatus(projectId));
    }

}
