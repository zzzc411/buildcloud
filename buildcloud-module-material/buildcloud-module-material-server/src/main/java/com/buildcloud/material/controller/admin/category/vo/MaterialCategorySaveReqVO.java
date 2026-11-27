package com.buildcloud.material.controller.admin.category.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 材料目录新增/修改 Request VO
 *
 * 校验分两层：
 * 1. Bean Validation（本类注解）：必填/长度/编码字符集/状态值域——能进 Service 前拦截的错误就不查库；
 * 2. Service 业务校验：同级同名、编码唯一、父子关系合法性（需查库，见 MaterialCategoryServiceImpl）。
 *
 * 注意 parentId 用包装类型且**允许 null**：前端新增根节点时可以不传（Service 归一化为 0），
 * 否则每个根节点请求都得显式写 `"parentId": 0`，是易漏点。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料目录新增/修改 Request VO")
@Data
public class MaterialCategorySaveReqVO {

    @Schema(description = "目录编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "父目录编号（不传或传 0 表示根节点）", example = "0")
    private Long parentId;

    @Schema(description = "目录名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "钢材")
    @NotEmpty(message = "目录名称不能为空")
    @Size(max = 100, message = "目录名称长度不能超过 100 个字符")
    private String name;

    @Schema(description = "目录编码（租户内唯一，导入/对账用稳定键）", requiredMode = Schema.RequiredMode.REQUIRED, example = "STEEL")
    @NotEmpty(message = "目录编码不能为空")
    @Size(max = 64, message = "目录编码长度不能超过 64 个字符")
    // 编码是导入/对账的稳定引用键，限制为「字母数字 + 下划线/中划线」，
    // 避免前后空格、中文、路径分隔符在不同系统间传递时产生不可见的对不上
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "目录编码只能包含字母、数字、下划线和中划线")
    private String code;

    @Schema(description = "排序号（同级内升序展示）", example = "1")
    private Integer sort;

    @Schema(description = "状态：0-开启 1-停用", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
