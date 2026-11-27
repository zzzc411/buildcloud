package com.buildcloud.material.dal.dataobject.requisition;

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

import java.math.BigDecimal;

/**
 * 材料申领明细 DO
 *
 * 表 bc_requisition_item，对应 spec v1.2 §5.2「明细：申领单/物料/数量」，建表见 07-material.sql 第 6 节。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）。
 *
 * 精度：quantity 用 decimal(18,4)，与 bc_material_stock.available_qty **同精度**。
 * 若明细 4 位、库存 2 位，累加时会产生精度漂移导致对账不平（DDL 注释同口径）。
 *
 * 数量语义：明细里的 quantity 恒为**正数**（申领量）；库存的增减靠流水 quantity 的符号表达，
 * 明细只描述「申请多少」，不参与符号约定。这样明细行在任何状态下都自解释。
 *
 * @author buildcloud
 */
@TableName("bc_requisition_item")
@KeySequence("bc_requisition_item_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequisitionItemDO extends TenantBaseDO {

    /**
     * 明细编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 所属申领单（bc_requisition.id）
     */
    private Long requisitionId;

    /**
     * 物料（bc_material.id）
     */
    private Long materialId;

    /**
     * 申领数量（正数，退回量另记流水）
     */
    private BigDecimal quantity;

    /**
     * 备注
     */
    private String remark;

}
