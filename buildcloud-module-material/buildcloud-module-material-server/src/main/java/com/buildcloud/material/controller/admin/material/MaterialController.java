package com.buildcloud.material.controller.admin.material;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.material.controller.admin.material.vo.MaterialPageReqVO;
import com.buildcloud.material.controller.admin.material.vo.MaterialRespVO;
import com.buildcloud.material.controller.admin.material.vo.MaterialSaveReqVO;
import com.buildcloud.material.dal.dataobject.material.MaterialDO;
import com.buildcloud.material.service.material.MaterialService;
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
 * 管理后台 - 物料
 *
 * URL 段遵循 AGENTS R-9：首段 = 服务标识 {@code buildcloud-material}，第二段 = 域 {@code material}；
 * 对外暴露：http://127.0.0.1:48080/admin-api/buildcloud-material/material/**
 *
 * 权限标识 {@code material:material:*}。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 物料")
@RestController
@RequestMapping("/buildcloud-material/material")
@Validated
public class MaterialController {

    @Resource
    private MaterialService materialService;

    @PostMapping("/create")
    @Operation(summary = "创建物料")
    @PreAuthorize("@ss.hasPermission('material:material:create')")
    public CommonResult<Long> createMaterial(@Valid @RequestBody MaterialSaveReqVO createReqVO) {
        return success(materialService.createMaterial(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新物料")
    @PreAuthorize("@ss.hasPermission('material:material:update')")
    public CommonResult<Boolean> updateMaterial(@Valid @RequestBody MaterialSaveReqVO updateReqVO) {
        materialService.updateMaterial(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除物料")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('material:material:delete')")
    public CommonResult<Boolean> deleteMaterial(@RequestParam("id") Long id) {
        materialService.deleteMaterial(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得物料")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('material:material:query')")
    public CommonResult<MaterialRespVO> getMaterial(@RequestParam("id") Long id) {
        MaterialDO material = materialService.getMaterial(id);
        return success(BeanUtils.toBean(material, MaterialRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得物料分页", description = "支持按目录筛选，名称/编码/规格模糊匹配")
    @PreAuthorize("@ss.hasPermission('material:material:query')")
    public CommonResult<PageResult<MaterialRespVO>> getMaterialPage(@Valid MaterialPageReqVO pageReqVO) {
        PageResult<MaterialDO> pageResult = materialService.getMaterialPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, MaterialRespVO.class));
    }

}
