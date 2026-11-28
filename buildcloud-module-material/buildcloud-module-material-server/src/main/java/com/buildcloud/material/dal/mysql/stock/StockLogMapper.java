package com.buildcloud.material.dal.mysql.stock;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.controller.admin.stock.vo.StockLogPageReqVO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.enums.stock.StockBizTypeEnum;
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

    /**
     * 统计某张申领单在某物料上「已落库的申领扣减」总笔数（V2 消费幂等的**数据库侧判据**）
     *
     * <pre>
     *   SELECT COUNT(1) FROM bc_stock_log
     *    WHERE project_id = #{projectId} AND material_id = #{materialId}
     *      AND biz_no = #{bizNo} AND biz_type = #{bizType} AND deleted = 0
     * </pre>
     *
     * ========== 为什么幂等不能只看单据状态 ==========
     *
     * spec §6.1 的幂等条款允许「`req_no` 唯一索引 + 消费状态表（**或状态机判断**）」两种口径。
     * 本项目选的是状态机判断，但**单据状态只能覆盖"整单"**：
     * V2 是先 Redis 预扣（按物料逐个 Lua）、再一条消息携带多行明细。若消费者在第 2 行失败回滚，
     * 单据仍是「已提交(1)」，此时重投必须**重新处理全部行**（不能跳行）；
     * 而如果第 1 行的流水已经提交（不可能与本方法同事务，见消费者实现），
     * 用「本单本物料是否已有 biz_type=2 流水」作判据就能精确到行。
     *
     * 所以真正的幂等顺序是：**先看单据状态（整单闸门）→ 再看本行流水（行级兜底）**，
     * 本方法就是后者。
     *
     * @param projectId  项目编号（分片键，必带）
     * @param materialId 物料编号
     * @param bizNo      业务单号（= req_no）
     * @param bizType    业务类型（= 2 申领扣减）
     * @return 已有流水的条数（0 表示该行尚未扣减过）
     */
    @Select("SELECT COUNT(1) FROM bc_stock_log "
            + "WHERE project_id = #{projectId} AND material_id = #{materialId} "
            + "AND biz_no = #{bizNo} AND biz_type = #{bizType} AND deleted = 0")
    Long selectCountByBizNoAndType(@Param("projectId") Long projectId,
                                   @Param("materialId") Long materialId,
                                   @Param("bizNo") String bizNo,
                                   @Param("bizType") Integer bizType);

    /**
     * 查某张申领单在某物料上最后一条申领扣减流水（幂等短路时还原 balance_after 用）
     *
     * 用途见 {@code RequisitionDeductConsumer}：重复投递时若本行已扣过，直接 ACK，
     * 但要能解释"为什么跳过"，日志里带上既有流水的 balance_after 便于对账比对。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param bizNo      业务单号
     * @param bizType    业务类型
     * @return 最新一条流水；没有返回 null
     */
    default StockLogDO selectLatestByBizNoAndType(Long projectId, Long materialId,
                                                 String bizNo, Integer bizType) {
        return selectOne(new LambdaQueryWrapperX<StockLogDO>()
                .eq(StockLogDO::getProjectId, projectId)
                .eq(StockLogDO::getMaterialId, materialId)
                .eq(StockLogDO::getBizNo, bizNo)
                .eq(StockLogDO::getBizType, bizType)
                .orderByDesc(StockLogDO::getId)
                .last("LIMIT 1"));
    }

    // ========== 对账（W5-P4） ==========

    /**
     * 查询某项目某物料**全部**申领扣减流水（biz_type=2），按 id 升序
     *
     * 用途（{@code StockReconcileService} 的 D2 判定）：一条 SQL 取回该库存维度上
     * 所有已生效的扣减流水，与 Redis 明细（`HGETALL pm:prededuct:{pid}:{mid}`）**逐笔比对**——
     * DB 有而 Redis 没有的 reqNo 就是 D2 差异。
     *
     * 为什么不用「逐个 reqNo 查一次 DB」（即 N+1）：对账是定时全量扫描，
     * 库存维度数与流水行数都是千级，N+1 会把一轮对账从秒级拖到分钟级；
     * 这里一次取回、内存里建 Set 比对，复杂度 O(n)。
     *
     * @param projectId  项目编号（分片键，必带）
     * @param materialId 物料编号
     * @return 该维度全部申领扣减流水（id 正序；无流水返回空列表）
     */
    default List<StockLogDO> selectListRequisitionOutLogs(Long projectId, Long materialId) {
        return selectList(new LambdaQueryWrapperX<StockLogDO>()
                .eq(StockLogDO::getProjectId, projectId)
                .eq(StockLogDO::getMaterialId, materialId)
                .eq(StockLogDO::getBizType, StockBizTypeEnum.REQUISITION_OUT.getBizType())
                .orderByAsc(StockLogDO::getId));
    }

    /**
     * 按业务单号查询某项目某物料的全部流水（含各种 biz_type，id 正序）
     *
     * 用途（D1 判定「单据在途」分支）：补扣减前要先确认「本单本物料确实一笔都没扣过」，
     * 补完之后再用 {@link #selectCountByBizNoAndType} 做幂等复核。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param bizNo      业务单号（req_no）
     * @return 流水列表（无记录返回空列表）
     */
    default List<StockLogDO> selectListByBizNo(Long projectId, Long materialId, String bizNo) {
        return selectList(new LambdaQueryWrapperX<StockLogDO>()
                .eq(StockLogDO::getProjectId, projectId)
                .eq(StockLogDO::getMaterialId, materialId)
                .eq(StockLogDO::getBizNo, bizNo)
                .orderByAsc(StockLogDO::getId));
    }

    /**
     * 汇总某维度某类业务的流水数量（**0 超卖断言的核心查询**）
     *
     * <p>「期初 + Σ(biz_type=2 的 quantity) == available_qty」是 W5 冒烟的第 1 条断言：
     * `quantity` 用正负号表示增减，故申领扣减为负，直接相加即为净变动。</p>
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param bizType    业务类型（见 {@link com.buildcloud.material.enums.stock.StockBizTypeEnum}）
     * @return 汇总值；无流水时返回 {@code null}（调用方转为 0）
     */
    default BigDecimal sumQuantityByProjectAndMaterialAndBizType(Long projectId, Long materialId, Integer bizType) {
        List<StockLogDO> list = selectList(new LambdaQueryWrapperX<StockLogDO>()
                .eq(StockLogDO::getProjectId, projectId)
                .eq(StockLogDO::getMaterialId, materialId)
                .eq(StockLogDO::getBizType, bizType));
        if (list.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (StockLogDO log : list) {
            if (log.getQuantity() != null) {
                sum = sum.add(log.getQuantity());
            }
        }
        return sum;
    }

}
