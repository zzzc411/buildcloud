package com.buildcloud.material.dal.dataobject.material;

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
 * 物料 DO
 *
 * 表 bc_material 对应 spec v1.2 §5.2：编码/名称/规格/单位/目录。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）。
 *
 * @author buildcloud
 */
@TableName("bc_material")
@KeySequence("bc_material_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialDO extends TenantBaseDO {

    /**
     * 物料编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 物料编码（业务编码，租户内唯一）
     *
     * 入库/申领/流水全链路以它做人工可读标识；唯一性对应真库 uk_material_no。
     */
    private String materialNo;

    /**
     * 物料名称
     */
    private String name;

    /**
     * 规格型号（如 HRB400 Φ20）
     */
    private String spec;

    /**
     * 计量单位（个/吨/米/立方…）
     */
    private String unit;

    /**
     * 所属目录（bc_material_category.id）
     *
     * 逻辑关联，不建物理外键（与项目域口径一致）。
     */
    private Long categoryId;

    /**
     * 状态：0-开启 1-停用（停用后不可申领）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;

}
