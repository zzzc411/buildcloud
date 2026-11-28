package com.buildcloud.material.dal.mysql.stock;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.dal.dataobject.stock.ReconcileLogDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存对账快照 Mapper
 *
 * 继承基座 {@link BaseMapperX}（AGENTS §4）。
 *
 * 本表是**只增的审计表**：不提供 update/delete 的业务封装——每轮对账追加新行，
 * 「差异是否在收敛」靠比较不同批次的行得出，而不是覆盖旧结论。
 * 覆盖会让历史告警消失，事故复盘时无法证明"当时确实告警过"。
 *
 * @author buildcloud
 */
@Mapper
public interface ReconcileLogMapper extends BaseMapperX<ReconcileLogDO> {

    /**
     * 按批次号查该轮对账的全部结论（每类差异一行）
     *
     * @param batchNo 批次号
     * @return 快照列表（check_type 升序：D1 → D2 → D3，与修复顺序一致；无记录返回空列表）
     */
    default List<ReconcileLogDO> selectListByBatchNo(String batchNo) {
        return selectList(new LambdaQueryWrapperX<ReconcileLogDO>()
                .eq(ReconcileLogDO::getBatchNo, batchNo)
                .orderByAsc(ReconcileLogDO::getCheckType));
    }

    /**
     * 统计某类型在时间窗口内的差异总条数（阈值告警与收敛趋势判断用）
     *
     * <pre>
     *   SELECT COUNT(1) FROM bc_reconcile_log
     *    WHERE check_type = #{checkType} AND check_time >= #{from} AND diff_count > 0 AND deleted = 0
     * </pre>
     *
     * 口径说明：
     * 1. 显式 `deleted = 0`——注解 SQL 不经过 MyBatis-Plus 的逻辑删除自动拼接；
     * 2. 只统计 `diff_count > 0` 的轮次：干净轮次很多，计入会让"最近出过几次差异"不可读。
     *
     * @param checkType 差异类型
     * @param from      起始时间（含）
     * @return 出现差异的轮次数
     */
    @Select("SELECT COUNT(1) FROM bc_reconcile_log "
            + "WHERE check_type = #{checkType} AND check_time >= #{from} "
            + "AND diff_count > 0 AND deleted = 0")
    Long selectDiffRoundCount(@Param("checkType") Integer checkType,
                              @Param("from") LocalDateTime from);

}
