package com.buildcloud.material.dal.mysql.requisition;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import org.apache.ibatis.annotations.Mapper;

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

}
