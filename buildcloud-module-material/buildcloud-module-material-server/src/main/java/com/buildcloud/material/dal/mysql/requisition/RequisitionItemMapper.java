package com.buildcloud.material.dal.mysql.requisition;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 材料申领明细 Mapper
 *
 * 继承基座 {@link BaseMapperX}（AGENTS §4）。
 *
 * 明细是申领单的**从属资源**（一定挂在某张申领单下），故本 Mapper 只提供「按申领单查明细」，
 * 不提供跨单据的物料维度查询——那属于消耗汇总报表（report 域）的口径，
 * 在这里开口子会让 material 域承担报表职责（spec §5.4 报表域）。
 *
 * W4-P5 追加 {@link #increaseReturnedQtyReturnCheck}：退料用「条件更新即并发防线」的原子累加，
 * 与 {@code RequisitionMapper} 的一组 CAS 同一套思路（见该方法注释）。
 *
 * @author buildcloud
 */
@Mapper
public interface RequisitionItemMapper extends BaseMapperX<RequisitionItemDO> {

    /**
     * 查询某张申领单的全部明细（详情页一次性取全量，按 id 正序即录入顺序）
     *
     * @param requisitionId 申领单编号
     * @return 明细列表（无明细时为空列表而非 null）
     */
    default List<RequisitionItemDO> selectListByRequisitionId(Long requisitionId) {
        return selectList(new LambdaQueryWrapperX<RequisitionItemDO>()
                .eq(RequisitionItemDO::getRequisitionId, requisitionId)
                .orderByAsc(RequisitionItemDO::getId));
    }

    /**
     * 删除某张申领单的全部明细（逻辑删除）
     *
     * 用途：本 Phase 的 create 只在新建时写明细，不需重建；
     * 保留该方法是因为 P5 退料会对明细做「部分回退」调整，届时需要删旧插新。
     * 注意逻辑删除不会释放任何唯一索引（本表没有业务唯一索引，无 ADR-004 冲突）。
     *
     * @param requisitionId 申领单编号
     * @return 删除行数
     */
    default int deleteByRequisitionId(Long requisitionId) {
        return delete(new LambdaQueryWrapperX<RequisitionItemDO>()
                .eq(RequisitionItemDO::getRequisitionId, requisitionId));
    }

    /**
     * **退料原子条件更新**：累计已退量 += 本次退料量，且必须不超过申领量
     *
     * <pre>
     *   UPDATE bc_requisition_item
     *      SET returned_qty = returned_qty + #{quantity},
     *          update_time  = NOW()
     *    WHERE id = #{id} AND returned_qty + #{quantity} <= quantity AND deleted = 0
     * </pre>
     *
     * ========== 为什么把「可退量校验」写进 WHERE，而不是先查再更新（核心，别改） ==========
     *
     * 退料可能被重复提交（双击/网关重试/两个仓管同时点）。若按「先 select 算可退量 →
     * 校验 → update 新值」的写法，两个并发请求会**同时**读到同一个 returned_qty 并双双通过校验，
     * 于是库存被回补两次——而库存回补是不可察觉的账务错误（钱/料凭空多出）。
     * `returned_qty + #{quantity} <= quantity` 把「校验 + 累加」合成一条语句，由数据库行锁保证互斥：
     * 第二个请求影响行数 0 → Service 抛 REQUISITION_RETURN_QUANTITY_EXCEEDED → 整体回滚，
     * 连它已回补的库存一起退掉（与 V1 扣减、状态 CAS 同一套「条件更新即并发防线」思路）。
     *
     * 自引用 {@code returned_qty = returned_qty + ?} 而不是 #{newQty} 的理由（与 P3 入库 CAS 同）：
     * 「读旧值 → 算新值」发生在数据库内部，不会用应用层读到的旧快照覆盖别人的增量。
     *
     * 精度必须与 quantity 一致（decimal(18,4)），否则 `<=` 比较会因 scale 差异出现
     * 「明明退完了却永远差不完」的幽灵差异（DDL/DO 注释同口径）。
     *
     * `deleted = 0` 必须显式写：注解 SQL 不经过 MyBatis-Plus 逻辑删除自动拼接。
     *
     * @param id       明细编号
     * @param quantity 本次退料量（必须 > 0，由 Service 校验）
     * @return 影响行数（1 成功；0 表示超过可退量 / 明细不存在 / 已删除）
     */
    @Update("UPDATE bc_requisition_item SET returned_qty = returned_qty + #{quantity}, update_time = NOW() "
            + "WHERE id = #{id} AND returned_qty + #{quantity} <= quantity AND deleted = 0")
    int increaseReturnedQtyReturnCheck(@Param("id") Long id, @Param("quantity") BigDecimal quantity);

}
