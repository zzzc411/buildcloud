package com.buildcloud.contract.controller.admin.contract;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.contract.controller.admin.contract.vo.ContractPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.ContractReconcileRespVO;
import com.buildcloud.contract.controller.admin.contract.vo.ContractRespVO;
import com.buildcloud.contract.controller.admin.contract.vo.ContractSaveReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.ContractUpdateStatusReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractDO;
import com.buildcloud.contract.service.contract.ContractService;
import com.buildcloud.contract.service.contract.bo.ContractReconcileBO;
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
 * 管理后台 - 合同
 *
 * URL 遵循 AGENTS R-9（首段 = 服务标识，对照基座 /system/dict-type）：
 * 本服务对外前缀为 `/admin-api/buildcloud-project`（基座 web starter 按 `**.controller.admin.**`
 * 包路径自动拼接），故合同子域首段写 `/buildcloud-project/contract`；
 * 写错会导致「直连 48084 通、经网关 404」（lessons-learned L2-3）。
 *
 * 接口清单：
 * <ul>
 *   <li>POST   /create            登记合同（草稿）</li>
 *   <li>PUT    /update            编辑合同（仅草稿）</li>
 *   <li>PUT    /update-status     状态机流转（草稿→审批中→履行中→已结算，旁路作废）</li>
 *   <li>DELETE /delete            删除合同（仅草稿）</li>
 *   <li>DELETE /delete-list       批量删除（仅草稿）</li>
 *   <li>GET    /get               合同详情</li>
 *   <li>GET    /page              合同台账分页（project_id / status / direction 等筛选）</li>
 *   <li>GET    /reconcile         台账对账（计划 vs 实际差异）</li>
 * </ul>
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 合同")
@RestController
@RequestMapping("/buildcloud-project/contract")
@Validated
public class ContractController {

    @Resource
    private ContractService contractService;

    @PostMapping("/create")
    @Operation(summary = "创建合同", description = "登记合同，初始状态为草稿(0)")
    @PreAuthorize("@ss.hasPermission('contract:contract:create')")
    public CommonResult<Long> createContract(@Valid @RequestBody ContractSaveReqVO createReqVO) {
        return success(contractService.createContract(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新合同", description = "仅草稿状态允许更新")
    @PreAuthorize("@ss.hasPermission('contract:contract:update')")
    public CommonResult<Boolean> updateContract(@Valid @RequestBody ContractSaveReqVO updateReqVO) {
        contractService.updateContract(updateReqVO);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "更新合同状态",
            description = "状态机：0草稿→1审批中→2履行中→3已结算，旁路 9已作废；非法流转返回 1_101_000_002")
    @PreAuthorize("@ss.hasPermission('contract:contract:update')")
    public CommonResult<Boolean> updateContractStatus(@Valid @RequestBody ContractUpdateStatusReqVO reqVO) {
        contractService.updateContractStatus(reqVO.getId(), reqVO.getStatus());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除合同", description = "仅草稿状态允许删除")
    @Parameter(name = "id", description = "合同编号", required = true)
    @PreAuthorize("@ss.hasPermission('contract:contract:delete')")
    public CommonResult<Boolean> deleteContract(@RequestParam("id") Long id) {
        contractService.deleteContract(id);
        return success(true);
    }

    @DeleteMapping("/delete-list")
    @Operation(summary = "批量删除合同", description = "仅草稿状态允许删除，含任一非草稿整体回滚")
    @Parameter(name = "ids", description = "合同编号列表", required = true)
    @PreAuthorize("@ss.hasPermission('contract:contract:delete')")
    public CommonResult<Boolean> deleteContractList(@RequestParam("ids") List<Long> ids) {
        contractService.deleteContractListByIds(ids);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得合同")
    @Parameter(name = "id", description = "合同编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('contract:contract:query')")
    public CommonResult<ContractRespVO> getContract(@RequestParam("id") Long id) {
        ContractDO contract = contractService.getContract(id);
        return success(BeanUtils.toBean(contract, ContractRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得合同台账分页")
    @PreAuthorize("@ss.hasPermission('contract:contract:query')")
    public CommonResult<PageResult<ContractRespVO>> getContractPage(@Valid ContractPageReqVO pageReqVO) {
        PageResult<ContractDO> pageResult = contractService.getContractPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, ContractRespVO.class));
    }

    @GetMapping("/reconcile")
    @Operation(summary = "合同台账对账",
            description = "一次返回合同金额、计划合计、实际收付（分收/付）、变更后金额与两组差异（计划vs实际、合同vs变更后）")
    @Parameter(name = "contractId", description = "合同编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('contract:contract:query')")
    public CommonResult<ContractReconcileRespVO> getReconcile(@RequestParam("contractId") Long contractId) {
        ContractReconcileBO reconcile = contractService.getReconcile(contractId);
        return success(BeanUtils.toBean(reconcile, ContractReconcileRespVO.class));
    }

}
