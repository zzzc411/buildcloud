package com.buildcloud.material.dal.dataobject.stock;

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
 * 库存流水 DO
 *
 * 表 bc_stock_log，对应 spec v1.2 §5.2「库存流水：biz_type 入库/申领/退回/冲正、biz_no、数量±、变动后余额」，
 * 建表见 sql/buildcloud/07-material.sql 第 7 节；该表是 W6 分库分表的**主表**（按 project_id 哈希 4 库 8 表，spec §6.3）。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）。
 *
 * 字段口径（与 DDL 注释严格一致，改这里必须同步改 DDL 与 stock-test-tables.sql）：
 * 1. {@link #bizType}：见 {@link com.buildcloud.material.enums.stock.StockBizTypeEnum}，
 *    1-入库 / 2-申领扣减 / 3-退回 / 4-冲正；本 Phase 只产生 1-入库；
 * 2. {@link #quantity}：**带符号**的变动数量——入库/退回/冲正为正，申领扣减为负。
 *    这样「对账一行聚合 SUM(quantity)」即得净变动，不需要 CASE WHEN 按类型分组求和；
 * 3. {@link #balanceAfter}：变动后余额快照，用于逐笔核对与断点续算
 *    （期初 + SUM(quantity) = 期末，且必须等于 stock.available_qty，spec §6.1 第 ⑦ 步）；
 * 4. {@link #bizNo}：业务单号（入库单号 / 申领单号 req_no），与 bizType 一起构成幂等判别键。
 *    **不是唯一索引**——一次申领可产生多条流水（扣减 + 后续冲正）；
 * 5. {@link #projectId}：**分片键**（spec §6.3），W6 接入 ShardingSphere 时缺失该列会直接报错，
 *    故本 Phase 起所有写流水的地方都必须带它。
 *
 * 主键：DDL 注释约定「应用层用 MyBatis-Plus ASSIGN_ID 雪花 ID 填充，不依赖自增」，以便 W6 分片后
 * 仍能全局唯一。此处**不**在 DO 上写死 {@code @TableId(type = IdType.ASSIGN_ID)}，而是沿用模块级
 * {@code mybatis-plus.global-config.db-config.id-type}（主库 NONE→AUTO，单测 AUTO），
 * 这样单测（H2 自增）与生产（雪花/自增）都能跑；W6 分片时统一改全局配置即可，不必逐 DO 改注解。
 *
 * @author buildcloud
 */
@TableName("bc_stock_log")
@KeySequence("bc_stock_log_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockLogDO extends TenantBaseDO {

    /**
     * 流水编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 所属项目（bc_project.id，**分片键**：W6 按此哈希 4 库 8 表）
     */
    private Long projectId;

    /**
     * 物料（bc_material.id）
     */
    private Long materialId;

    /**
     * 业务类型：1-入库 2-申领扣减 3-退回 4-冲正
     *
     * 枚举 {@link com.buildcloud.material.enums.stock.StockBizTypeEnum}
     */
    private Integer bizType;

    /**
     * 业务单号（入库单号 / 申领单号 req_no）
     *
     * 与 bizType 一起构成幂等判别键与追溯键；一次业务可能产生多条流水，故不加唯一约束。
     */
    private String bizNo;

    /**
     * 变动数量（**正负号表示增减**：入库/退回/冲正为正，申领扣减为负）
     */
    private BigDecimal quantity;

    /**
     * 变动后余额（逐笔快照，用于对账与断点续算）
     *
     * 必须等于该笔流水提交后 stock.available_qty 的值。
     */
    private BigDecimal balanceAfter;

    /**
     * 备注
     */
    private String remark;

}
