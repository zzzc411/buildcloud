package com.buildcloud.material.dal.dataobject.supplier;

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
 * 供应商 DO
 *
 * 表 bc_supplier 对应 spec v1.2 §5.2 材料域实体「供应商」。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）。
 *
 * @author buildcloud
 */
@TableName("bc_supplier")
@KeySequence("bc_supplier_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierDO extends TenantBaseDO {

    /**
     * 供应商编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 供应商编码（业务编码，租户内唯一）
     */
    private String supplierNo;

    /**
     * 供应商名称
     */
    private String name;

    /**
     * 联系人
     */
    private String contactPerson;

    /**
     * 联系电话
     */
    private String contactPhone;

    /**
     * 联系地址
     */
    private String address;

    /**
     * 状态：0-开启 1-停用
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;

}
