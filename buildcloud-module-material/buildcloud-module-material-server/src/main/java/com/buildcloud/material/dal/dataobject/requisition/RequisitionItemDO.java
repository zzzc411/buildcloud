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
     * 累计已退料量（可退量 = quantity - returned_qty；W4-P5 起）
     *
     * 精度必须与 quantity / bc_material_stock.available_qty 一致（decimal(18,4)），
     * 否则会出现「退完还差 0.0001、单据永远停在部分回退」的幽灵差异。
     *
     * 为什么不按 biz_no 聚合 bc_stock_log 算可退量：流水表是 W6 的分库分表主表，
     * 跨片聚合会变成跨片查询；本列还能支撑「WHERE returned_qty + ? <= quantity」的原子条件更新，
     * 让并发重复退料的第二个请求影响行数 0 而非把库存退两次。
     */
    private BigDecimal returnedQty;

    /**
     * 备注
     */
    private String remark;

}
