package com.buildcloud.contract.controller.admin.contract;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.buildcloud.contract.controller.admin.contract.vo.contractapproval.ContractApprovalStatusRespVO;
import com.buildcloud.contract.controller.admin.contract.vo.contractapproval.ContractApprovalSubmitReqVO;
import com.buildcloud.contract.service.contract.ContractApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 合同进度款审批
 *
 * URL 遵循 R-9：首段为服务标识 `/buildcloud-project`，经框架 adminApi 前缀后对外为
 * `http://127.0.0.1:48080/admin-api/buildcloud-project/contract/approval/**`。
 *
 * 为什么单独建 Controller 而不并进 ContractController：
 * 进度款审批是**跨服务协作**入口（依赖 bpm-server），与合同台账的纯 CRUD 生命周期不同；
 * 分开后单测只需 mock BPM 依赖，不必让整个 ContractController 的测试都背着 Feign 客户端。
 * 与项目立项审批（ProjectApprovalController，W3-P4）保持同构。
 *
 * 注意：真正的审批动作（通过/驳回）**不在本服务**，由基座 bpm 的
 * `/admin-api/bpm/task/approve`、`/admin-api/bpm/task/reject` 处理（读码笔记 §3.3），
 * 本服务只负责发起与接收结果。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 合同进度款审批")
@RestController
@RequestMapping("/buildcloud-project/contract/approval")
@Validated
public class ContractApprovalController {

    @Resource
    private ContractApprovalService contractApprovalService;

    @PostMapping("/submit")
    @Operation(summary = "提交合同进度款审批",
            description = "校验合同状态可审批、无审批中流程、金额不超未收付余额，发起 payment-approve 流程实例并回写实例编号；"
                    + "失败返回 1_101_040_000/001/002/004，或合同不存在 1_101_000_000")
    @PreAuthorize("@ss.hasPermission('contract:contract:update')")
    public CommonResult<String> submitApproval(@Valid @RequestBody ContractApprovalSubmitReqVO reqVO) {
        return success(contractApprovalService.submitApproval(
                reqVO.getContractId(), reqVO.getPeriod(), reqVO.getAmount()));
    }

    @GetMapping("/status")
    @Operation(summary = "获得合同进度款审批状态",
            description = "返回流程实例编号、审批状态（-1未开始/1审批中/2通过/3不通过/4已取消）、"
                    + "合同业务状态、合同金额与累计收付、在途申请金额与期次")
    @Parameter(name = "contractId", description = "合同编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('contract:contract:query')")
    public CommonResult<ContractApprovalStatusRespVO> getApprovalStatus(@RequestParam("contractId") Long contractId) {
        return success(contractApprovalService.getApprovalStatus(contractId));
    }

}
