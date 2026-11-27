package com.buildcloud.material.controller.admin.category;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategoryPageReqVO;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategoryRespVO;
import com.buildcloud.material.controller.admin.category.vo.MaterialCategorySaveReqVO;
import com.buildcloud.material.dal.dataobject.category.MaterialCategoryDO;
import com.buildcloud.material.service.category.MaterialCategoryService;
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
 * 管理后台 - 材料目录
 *
 * URL 段遵循 AGENTS R-9：首段 = 服务标识 {@code buildcloud-material}，第二段 = 域 {@code category}；
 * 经框架 adminApi 前缀（`**.controller.admin.**` → `/admin-api`）对外暴露：
 * http://127.0.0.1:48080/admin-api/buildcloud-material/category/**
 *
 * 权限标识沿用 {@code material:category:*}（菜单 SQL 里与按钮一一对应）。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 材料目录")
@RestController
@RequestMapping("/buildcloud-material/category")
@Validated
public class MaterialCategoryController {

    @Resource
    private MaterialCategoryService materialCategoryService;

    @PostMapping("/create")
    @Operation(summary = "创建材料目录")
    @PreAuthorize("@ss.hasPermission('material:category:create')")
    public CommonResult<Long> createCategory(@Valid @RequestBody MaterialCategorySaveReqVO createReqVO) {
        return success(materialCategoryService.createCategory(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新材料目录")
    @PreAuthorize("@ss.hasPermission('material:category:update')")
    public CommonResult<Boolean> updateCategory(@Valid @RequestBody MaterialCategorySaveReqVO updateReqVO) {
        materialCategoryService.updateCategory(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除材料目录", description = "存在子目录时拒绝删除（MATERIAL_CATEGORY_HAS_CHILDREN）")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('material:category:delete')")
    public CommonResult<Boolean> deleteCategory(@RequestParam("id") Long id) {
        materialCategoryService.deleteCategory(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得材料目录")
    @Parameter(name = "id", description = "编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('material:category:query')")
    public CommonResult<MaterialCategoryRespVO> getCategory(@RequestParam("id") Long id) {
        MaterialCategoryDO category = materialCategoryService.getCategory(id);
        // children 不填充：单条查询没有上下文，前端拿到节点后应走 /list 或 /tree
        return success(BeanUtils.toBean(category, MaterialCategoryRespVO.class,
                node -> node.setChildren(null)));
    }

    @GetMapping("/page")
    @Operation(summary = "获得材料目录分页", description = "目录很多时按名称/编码搜索的运维场景；组树请用 /list 或 /tree")
    @PreAuthorize("@ss.hasPermission('material:category:query')")
    public CommonResult<PageResult<MaterialCategoryRespVO>> getCategoryPage(
            @Valid MaterialCategoryPageReqVO pageReqVO) {
        PageResult<MaterialCategoryDO> pageResult = materialCategoryService.getCategoryPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, MaterialCategoryRespVO.class,
                node -> node.setChildren(null)));
    }

    @GetMapping("/list")
    @Operation(summary = "获得材料目录列表（扁平）",
            description = "返回全量目录，按 parentId/sort 升序；children 为 null，供前端自行组树（懒加载子节点也用它）")
    @PreAuthorize("@ss.hasPermission('material:category:query')")
    public CommonResult<List<MaterialCategoryRespVO>> getCategoryList() {
        List<MaterialCategoryDO> list = materialCategoryService.getCategoryList();
        return success(BeanUtils.toBean(list, MaterialCategoryRespVO.class,
                node -> node.setChildren(null)));
    }

    @GetMapping("/tree")
    @Operation(summary = "获得材料目录树（嵌套）",
            description = "children 已填充，叶子节点为空数组；前端可零处理直接渲染")
    @PreAuthorize("@ss.hasPermission('material:category:query')")
    public CommonResult<List<MaterialCategoryRespVO>> getCategoryTree() {
        return success(materialCategoryService.getCategoryTree());
    }

}
