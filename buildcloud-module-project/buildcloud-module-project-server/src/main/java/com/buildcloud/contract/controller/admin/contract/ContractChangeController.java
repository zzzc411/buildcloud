package com.buildcloud.contract.controller.admin.contract;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangePageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangeRespVO;
import com.buildcloud.contract.controller.admin.contract.vo.change.ContractChangeSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractChangeDO;
import com.buildcloud.contract.service.contract.ContractChangeService;
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
 * 管理后台 - 合同变更签证
 *
 * URL 遵循 AGENTS R-9：`/buildcloud-project/contract/change`。
 * change_amount 为带符号金额（正数增项、负数减项），0 值被拒（CONTRACT_CHANGE_AMOUNT_ZERO）。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 合同变更签证")
@RestController
@RequestMapping("/buildcloud-project/contract/change")
@Validated
public class ContractChangeController {

    @Resource
    private ContractChangeService contractChangeService;

    @PostMapping("/create")
    @Operation(summary = "创建合同变更", description = "增减金额可负不可 0（正数增项、负数减项）")
    @PreAuthorize("@ss.hasPermission('contract:change:create')")
    public CommonResult<Long> createContractChange(@Valid @RequestBody ContractChangeSaveReqVO createReqVO) {
        return success(contractChangeService.createContractChange(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新合同变更")
    @PreAuthorize("@ss.hasPermission('contract:change:update')")
    public CommonResult<Boolean> updateContractChange(@Valid @RequestBody ContractChangeSaveReqVO updateReqVO) {
        contractChangeService.updateContractChange(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除合同变更")
    @Parameter(name = "id", description = "变更编号", required = true)
    @PreAuthorize("@ss.hasPermission('contract:change:delete')")
    public CommonResult<Boolean> deleteContractChange(@RequestParam("id") Long id) {
        contractChangeService.deleteContractChange(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得合同变更")
    @Parameter(name = "id", description = "变更编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('contract:change:query')")
    public CommonResult<ContractChangeRespVO> getContractChange(@RequestParam("id") Long id) {
        ContractChangeDO change = contractChangeService.getContractChange(id);
        return success(BeanUtils.toBean(change, ContractChangeRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得合同变更分页")
    @PreAuthorize("@ss.hasPermission('contract:change:query')")
    public CommonResult<PageResult<ContractChangeRespVO>> getContractChangePage(
            @Valid ContractChangePageReqVO pageReqVO) {
        PageResult<ContractChangeDO> pageResult = contractChangeService.getContractChangePage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, ContractChangeRespVO.class));
    }

    @GetMapping("/list-by-contract")
    @Operation(summary = "按合同获得变更列表", description = "按编号升序，合同详情/对账页使用")
    @Parameter(name = "contractId", description = "合同编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('contract:change:query')")
    public CommonResult<List<ContractChangeRespVO>> getContractChangeListByContractId(
            @RequestParam("contractId") Long contractId) {
        List<ContractChangeDO> list = contractChangeService.getContractChangeListByContractId(contractId);
        return success(BeanUtils.toBean(list, ContractChangeRespVO.class));
    }

}
