package com.buildcloud.contract.controller.admin.contract;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordPageReqVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordRespVO;
import com.buildcloud.contract.controller.admin.contract.vo.paymentrecord.ContractPaymentRecordSaveReqVO;
import com.buildcloud.contract.dal.dataobject.contract.ContractPaymentRecordDO;
import com.buildcloud.contract.service.contract.ContractPaymentRecordService;
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
 * 管理后台 - 合同实际收付款流水
 *
 * URL 遵循 AGENTS R-9：`/buildcloud-project/contract/payment-record`。
 *
 * 注意：本资源的所有写操作都会**原子累加/冲减** bc_contract.paid_amount
 * （见 ContractPaymentRecordServiceImpl，UPDATE ... SET paid_amount = paid_amount + delta）。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 合同实际收付款")
@RestController
@RequestMapping("/buildcloud-project/contract/payment-record")
@Validated
public class ContractPaymentRecordController {

    @Resource
    private ContractPaymentRecordService paymentRecordService;

    @PostMapping("/create")
    @Operation(summary = "登记实际收付款", description = "合同须处于履行中；登记后原子累加合同累计收付金额")
    @PreAuthorize("@ss.hasPermission('contract:payment-record:create')")
    public CommonResult<Long> createPaymentRecord(@Valid @RequestBody ContractPaymentRecordSaveReqVO createReqVO) {
        return success(paymentRecordService.createPaymentRecord(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新实际收付款", description = "按金额差额调整合同累计收付")
    @PreAuthorize("@ss.hasPermission('contract:payment-record:update')")
    public CommonResult<Boolean> updatePaymentRecord(@Valid @RequestBody ContractPaymentRecordSaveReqVO updateReqVO) {
        paymentRecordService.updatePaymentRecord(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除实际收付款", description = "删除并冲减合同累计收付")
    @Parameter(name = "id", description = "流水编号", required = true)
    @PreAuthorize("@ss.hasPermission('contract:payment-record:delete')")
    public CommonResult<Boolean> deletePaymentRecord(@RequestParam("id") Long id) {
        paymentRecordService.deletePaymentRecord(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得实际收付款流水")
    @Parameter(name = "id", description = "流水编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('contract:payment-record:query')")
    public CommonResult<ContractPaymentRecordRespVO> getPaymentRecord(@RequestParam("id") Long id) {
        ContractPaymentRecordDO record = paymentRecordService.getPaymentRecord(id);
        return success(BeanUtils.toBean(record, ContractPaymentRecordRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得实际收付款流水分页")
    @PreAuthorize("@ss.hasPermission('contract:payment-record:query')")
    public CommonResult<PageResult<ContractPaymentRecordRespVO>> getPaymentRecordPage(
            @Valid ContractPaymentRecordPageReqVO pageReqVO) {
        PageResult<ContractPaymentRecordDO> pageResult = paymentRecordService.getPaymentRecordPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, ContractPaymentRecordRespVO.class));
    }

    @GetMapping("/list-by-contract")
    @Operation(summary = "按合同获得收付款流水列表", description = "按收付日期升序，合同详情/对账页使用")
    @Parameter(name = "contractId", description = "合同编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('contract:payment-record:query')")
    public CommonResult<List<ContractPaymentRecordRespVO>> getPaymentRecordListByContractId(
            @RequestParam("contractId") Long contractId) {
        List<ContractPaymentRecordDO> list = paymentRecordService.getPaymentRecordListByContractId(contractId);
        return success(BeanUtils.toBean(list, ContractPaymentRecordRespVO.class));
    }

}
