package com.buildcloud.material.dal.mysql.stock;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.controller.admin.stock.vo.StockLogPageReqVO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 库存流水 Mapper
 *
 * 继承基座 {@link BaseMapperX}（AGENTS §4）。
 *
 * 精修点：
 * 1. {@link #selectPage(StockLogPageReqVO)} 的排序按 `create_time DESC, id DESC`——
 *    流水是**时间序**，按 id 排在同毫秒批量写入时会乱（雪花/自增都不能保证与业务时间一致），
 *    而「某项目某时间段的流水」正是 idx_project_create 的索引顺序，排序也能顺带用上索引；
 * 2. {@link #selectListByProjectIdAndMaterialId} 供单测/对账遍历某行库存的全部流水
 *    （一致性断言 SUM 之外的逐笔核对），按时间正序返回便于「逐笔累加是否等于 balance_after」验证。
 *
 * @author buildcloud
 */
@Mapper
public interface StockLogMapper extends BaseMapperX<StockLogDO> {

    /**
     * 库存流水分页查询
     *
     * 检索维度对齐索引（DDL 第 7 节）：
     * - projectId + createTime 区间 → idx_project_create，分片内主路径；
     * - projectId + bizNo → idx_project_biz，按业务单号追溯；
     * - materialId / bizType 作为附加过滤（在 projectId 圈定的分片内回表过滤，代价可接受）。
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果（createTime 倒序，即最新流水在前）
     */
    default PageResult<StockLogDO> selectPage(StockLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<StockLogDO>()
                .eqIfPresent(StockLogDO::getProjectId, reqVO.getProjectId())
                .eqIfPresent(StockLogDO::getMaterialId, reqVO.getMaterialId())
                .eqIfPresent(StockLogDO::getBizType, reqVO.getBizType())
                .eqIfPresent(StockLogDO::getBizNo, reqVO.getBizNo())
                .betweenIfPresent(StockLogDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(StockLogDO::getCreateTime)
                .orderByDesc(StockLogDO::getId));
    }

    /**
     * 查询某项目某物料的全部流水（按时间正序）
     *
     * 用途：单测里逐笔核对「本期初 + Σquantity = balance_after 逐笔递增」；
     * 生产侧的对账 Job（W5/W6）也可复用来定位断点。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 流水列表（createTime、id 正序；无流水时为空列表而非 null）
     */
    default List<StockLogDO> selectListByProjectIdAndMaterialId(Long projectId, Long materialId) {
        return selectList(new LambdaQueryWrapperX<StockLogDO>()
                .eq(StockLogDO::getProjectId, projectId)
                .eq(StockLogDO::getMaterialId, materialId)
                .orderByAsc(StockLogDO::getCreateTime)
                .orderByAsc(StockLogDO::getId));
    }

    /**
     * 统计某项目某物料的流水净变动 SUM(quantity)
     *
     * 用途：一致性校验与对账——`stock.available_qty` 必须等于该 stock 全部流水的 SUM(quantity)
     * （spec §6.1 第 ⑦ 步「期初 + SUM(quantity) = 期末」）。
     * 用一条聚合 SQL 在库里算完，不把流水全查回应用层累加。
     *
     * 口径说明：
     * 1. 显式 `deleted = 0`——注解 SQL 不经过 MyBatis-Plus 的逻辑删除自动拼接；
     * 2. `COALESCE(..., 0)`：无流水时返回 0 而非 null，调用方可直接参与数值比较；
     * 3. 按 `project_id + material_id` 圈定：project_id 是分片键，W6 分片后该聚合落在单片内，
     *    不产生跨片查询（spec §6.3）。
     *
     * 放在本 Mapper（而非 MaterialStockMapper）的理由：聚合对象是 bc_stock_log，
     * 让每条 SQL 只操作自己 DO 对应的表，后续接 ShardingSphere 时该表的路由配置与其他流水查询集中在一处。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 流水净额（无流水时为 0）
     */
    @Select("SELECT COALESCE(SUM(quantity), 0) FROM bc_stock_log "
            + "WHERE project_id = #{projectId} AND material_id = #{materialId} AND deleted = 0")
    BigDecimal selectSumQuantity(@Param("projectId") Long projectId,
                                 @Param("materialId") Long materialId);

}
