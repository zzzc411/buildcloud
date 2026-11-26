package com.buildcloud.contract.controller.admin.contract;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanRespVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentplan.ContractPaymentPlanSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentPlanDO;
import com.buildcloud.contract.service.contract.ContractPaymentPlanService;
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
 * 管理后台 - 合同收付款计划
 *
 * URL 遵循 AGENTS R-9：`/buildcloud-project/contract/payment-plan`（首段=服务标识，
 * 二级=子域，三级=资源），经网关后完整路径为
 * `/admin-api/buildcloud-project/contract/payment-plan/**`。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 合同收付款计划")
@RestController
@RequestMapping("/buildcloud-project/contract/payment-plan")
@Validated
public class ContractPaymentPlanController {

    @Resource
    private ContractPaymentPlanService paymentPlanService;

    @PostMapping("/create")
    @Operation(summary = "创建收付款计划", description = "按期次编制，期次唯一且计划合计不得超过合同金额")
    @PreAuthorize("@ss.hasPermission('contract:payment-plan:create')")
    public CommonResult<Long> createPaymentPlan(@Valid @RequestBody ContractPaymentPlanSaveReqVO createReqVO) {
        return success(paymentPlanService.createPaymentPlan(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新收付款计划")
    @PreAuthorize("@ss.hasPermission('contract:payment-plan:update')")
    public CommonResult<Boolean> updatePaymentPlan(@Valid @RequestBody ContractPaymentPlanSaveReqVO updateReqVO) {
        paymentPlanService.updatePaymentPlan(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除收付款计划")
    @Parameter(name = "id", description = "计划编号", required = true)
    @PreAuthorize("@ss.hasPermission('contract:payment-plan:delete')")
    public CommonResult<Boolean> deletePaymentPlan(@RequestParam("id") Long id) {
        paymentPlanService.deletePaymentPlan(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得收付款计划")
    @Parameter(name = "id", description = "计划编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('contract:payment-plan:query')")
    public CommonResult<ContractPaymentPlanRespVO> getPaymentPlan(@RequestParam("id") Long id) {
        ContractPaymentPlanDO plan = paymentPlanService.getPaymentPlan(id);
        return success(BeanUtils.toBean(plan, ContractPaymentPlanRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得收付款计划分页")
    @PreAuthorize("@ss.hasPermission('contract:payment-plan:query')")
    public CommonResult<PageResult<ContractPaymentPlanRespVO>> getPaymentPlanPage(
            @Valid ContractPaymentPlanPageReqVO pageReqVO) {
        PageResult<ContractPaymentPlanDO> pageResult = paymentPlanService.getPaymentPlanPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, ContractPaymentPlanRespVO.class));
    }

    @GetMapping("/list-by-contract")
    @Operation(summary = "按合同获得收付款计划列表", description = "按期次升序，合同详情/对账页使用")
    @Parameter(name = "contractId", description = "合同编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('contract:payment-plan:query')")
    public CommonResult<List<ContractPaymentPlanRespVO>> getPaymentPlanListByContractId(
            @RequestParam("contractId") Long contractId) {
        List<ContractPaymentPlanDO> list = paymentPlanService.getPaymentPlanListByContractId(contractId);
        return success(BeanUtils.toBean(list, ContractPaymentPlanRespVO.class));
    }

}
