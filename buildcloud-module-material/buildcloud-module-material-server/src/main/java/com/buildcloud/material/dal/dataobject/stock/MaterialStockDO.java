package com.buildcloud.material.dal.dataobject.stock;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * 项目库存 DO
 *
 * 表 bc_material_stock，对应 spec v1.2 §5.2「库存：项目+物料 唯一，available_qty / locked_qty / version 乐观锁」，
 * 建表见 sql/buildcloud/07-material.sql 第 4 节。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）：表含 tenant_id 列，基座多租户插件只对该基类的子类自动追加
 * tenant_id 条件；审计字段由 BaseDO 提供。
 *
 * ========== 关于 version 与乐观锁的实现方式（重要，改前必读） ==========
 *
 * 1. {@link Version} 注解在这里是**列语义声明**：它向读代码的人、以及未来可能启用的插件表明
 *    「这一列是乐观锁版本号，每次成功修改 +1」。
 *
 * 2. 真正的并发控制由 {@code MaterialStockMapper#updateAvailableQtyIncrWithVersion} 的
 *    **一条条件更新 SQL** 承担：
 *    {@code UPDATE bc_material_stock SET available_qty = available_qty + ?, version = version + 1
 *     WHERE id = ? AND version = ? AND deleted = 0}
 *    —— 影响行数 0 即「读到的 version 已被别人推进」，由 Service 重试或报冲突。
 *
 * 3. **为什么不用 MyBatis-Plus 的自动乐观锁**（即注册 OptimisticLockerInnerInterceptor）：
 *    基座 {@code YudaoMybatisAutoConfiguration} 只注册了分页与锁定读两个内部拦截器，**没有**
 *    OptimisticLockerInnerInterceptor。要让 {@code @Version} 自动生效，必须往基座那个
 *    MybatisPlusInterceptor Bean 上追加拦截器——这属于「改基座/改全局拦截器链」（AGENTS R-2），
 *    会影响 material 服务内所有模块的 update 行为（含 P4 申领、P5 出库），且拦截器顺序敏感
 *    （租户拦截器必须首位）。故本模块采取「显式条件更新」这一等价且作用域最小的做法，
 *    与 spec §6.1「DB 乐观锁条件更新（available >= qty）作为最后防线」的描述一致。
 *
 * 4. 两者绝不叠加：本 DO 的所有写路径都**不走** {@code updateById}（那才会触发插件级的 version
 *    读写），只走上面那条显式 SQL。单测 {@code testInbound_versionIncrementsByExactlyOne}
 *    固定了「每次入库 version 恰好 +1」的口径；若将来有人给基座加上乐观锁插件而这里又保留
 *    显式 SQL，该用例会立刻暴露「+2」的双自增。
 *
 * available_qty / locked_qty 的语义（预扣拆分，spec §5.2）：
 * - available_qty：可申领量，入库累加、申领扣减、退回/冲正回补；
 * - locked_qty：已提交待审批的预留量（W5 预扣链路使用，本 Phase 只读不写）。
 *
 * @author buildcloud
 */
@TableName("bc_material_stock")
@KeySequence("bc_material_stock_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaterialStockDO extends TenantBaseDO {

    /**
     * 库存编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 所属项目（bc_project.id）
     */
    private Long projectId;

    /**
     * 物料（bc_material.id）
     */
    private Long materialId;

    /**
     * 可用库存数量
     *
     * 精度 decimal(18,4)：材料可能按吨/立方/米计量，需要小数（与 bc_requisition_item.quantity 同精度，
     * 避免明细 4 位、库存 2 位在累加时产生精度漂移导致对账不平）。
     */
    private BigDecimal availableQty;

    /**
     * 锁定数量（已提交待审批的预扣量）
     *
     * W5 预扣链路使用：提交申领即从 available 转入 locked，避免「已提交未审批」的量被其它申领单看到。
     * 本 Phase（W4-P3 入库）只写 0，不参与入库计算。
     */
    private BigDecimal lockedQty;

    /**
     * 乐观锁版本号
     *
     * 见类注释「关于 version 与乐观锁的实现方式」：由 Mapper 的条件更新 SQL 维护，每次成功入库 +1。
     */
    @Version
    private Integer version;

    /**
     * 备注
     */
    private String remark;

}
