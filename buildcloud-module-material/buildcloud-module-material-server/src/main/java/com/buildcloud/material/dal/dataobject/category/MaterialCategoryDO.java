package com.buildcloud.material.dal.dataobject.category;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 材料目录 DO（树形）
 *
 * 表 bc_material_category 对应 spec v1.2 §5.2 材料域实体「树形目录」。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）：表含 tenant_id 列，基座多租户插件只对该基类的子类
 * 自动追加 tenant_id 条件；审计字段（creator/create_time/updater/update_time/deleted）由 BaseDO 提供。
 *
 * @author buildcloud
 */
@TableName("bc_material_category")
@KeySequence("bc_material_category_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialCategoryDO extends TenantBaseDO {

    /**
     * 根节点的 parent_id 约定值
     *
     * 用 0 而非 null：null 在 SQL 里 `WHERE parent_id = ?` 永不命中，
     * 而树加载与「查子节点」都要能按 parent_id 等值查，故统一用 0 表示根（与 07-material.sql 一致）。
     */
    public static final Long PARENT_ID_ROOT = 0L;

    /**
     * 目录编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 父目录编号（{@link #PARENT_ID_ROOT} 表示根节点）
     */
    private Long parentId;

    /**
     * 目录名称
     */
    private String name;

    /**
     * 目录编码（租户内唯一）
     *
     * 供导入/对账时稳定引用：名称会改，编码不改。
     */
    private String code;

    /**
     * 排序号（同级内升序展示）
     */
    private Integer sort;

    /**
     * 状态：0-开启 1-停用（{@link cn.iocoder.yudao.framework.common.enums.CommonStatusEnum}）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;

}
