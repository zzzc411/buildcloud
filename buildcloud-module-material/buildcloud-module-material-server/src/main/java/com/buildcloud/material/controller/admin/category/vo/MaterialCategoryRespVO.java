package com.buildcloud.material.controller.admin.category.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台 - 材料目录 Response VO
 *
 * 同时用于两种返回形态：
 * 1. `GET /list`（扁平列表）：children 为 null，前端自己按 parentId 组树（element-plus
 *    的 tree-select 与 a-tree 都支持扁平+parentId 直接转树）；
 * 2. `GET /tree`（嵌套树）：children 已填好，前端可零处理直接渲染。
 *
 * 为什么两种都给：组树的「权威口径」在服务端（否则每个端各自实现，父子顺序/排序规则会漂移），
 * 但扁平列表对「按 parentId 懒加载子节点」的场景更省流量——两者共用同一份排序逻辑即可。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料目录 Response VO")
@Data
public class MaterialCategoryRespVO {

    @Schema(description = "目录编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "父目录编号（0 表示根节点）", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long parentId;

    @Schema(description = "目录名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "钢材")
    private String name;

    @Schema(description = "目录编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "STEEL")
    private String code;

    @Schema(description = "排序号", example = "1")
    private Integer sort;

    @Schema(description = "状态：0-开启 1-停用", example = "0")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

    /**
     * 子节点列表
     *
     * 只在 `GET /tree` 的返回里填充（叶子节点为空数组而非 null，前端可直接 v-for 无需判空）；
     * `GET /list` 与 `GET /get` 里为 null。
     */
    @Schema(description = "子节点列表（仅 /tree 接口填充，叶子为空数组）")
    private List<MaterialCategoryRespVO> children = new ArrayList<>();

}
