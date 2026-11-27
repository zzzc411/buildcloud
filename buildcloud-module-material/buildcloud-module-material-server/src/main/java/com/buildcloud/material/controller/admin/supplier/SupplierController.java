package com.buildcloud.material.controller.admin.supplier;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierPageReqVO;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierRespVO;
import com.buildcloud.material.controller.admin.supplier.vo.SupplierSaveReqVO;
import com.buildcloud.material.dal.dataobject.supplier.SupplierDO;
import com.buildcloud.material.service.supplier.SupplierService;
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

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 供应商
 *
 * URL 段遵循 AGENTS R-9：首段 = 服务标识 {@code buildcloud-material}，第二段 = 域 {@code supplier}；
 * 对外暴露：http://127.0.0.1:48080/admin-api/buildcloud-material/supplier/**
 *
 * 权限标识 {@code material:supplier:*}。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 供应商")
@RestController
@RequestMapping("/buildcloud-material/supplier")
@Validated
public class SupplierController {

    @Resource
    private SupplierService supplierService;

    @PostMapping("/create")
    @Operation(summary = "创建供应商")
    @PreAuthorize("@ss.hasPermission('material:supplier:create')")
    public CommonResult<Long> createSupplier(@Valid @RequestBody SupplierSaveReqVO createReqVO) {
        return success(supplierService.createSupplier(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新供应商")
    @PreAuthorize("@ss.hasPermission('material:supplier:update')")
    public CommonResult<Boolean> updateSupplier(@Valid @RequestBody SupplierSaveReqVO updateReqVO) {
        supplierService.updateSupplier(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除供应商")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('material:supplier:delete')")
    public CommonResult<Boolean> deleteSupplier(@RequestParam("id") Long id) {
        supplierService.deleteSupplier(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得供应商")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('material:supplier:query')")
    public CommonResult<SupplierRespVO> getSupplier(@RequestParam("id") Long id) {
        SupplierDO supplier = supplierService.getSupplier(id);
        return success(BeanUtils.toBean(supplier, SupplierRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得供应商分页")
    @PreAuthorize("@ss.hasPermission('material:supplier:query')")
    public CommonResult<PageResult<SupplierRespVO>> getSupplierPage(@Valid SupplierPageReqVO pageReqVO) {
        PageResult<SupplierDO> pageResult = supplierService.getSupplierPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, SupplierRespVO.class));
    }

}
