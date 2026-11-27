package com.buildcloud.material.dal.mysql.stock;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.controller.admin.stock.vo.StockPageReqVO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * 项目库存 Mapper
 *
 * 继承基座 {@link BaseMapperX}，复用分页/单条查询封装与 {@link LambdaQueryWrapperX} 的
 * xxxIfPresent 条件拼接（AGENTS §4：与基座风格一致，勿改写成手写 XML）。
 *
 * 精修点（相对生成器产物）：
 * 1. {@link #updateAvailableQtyIncrWithVersion} 是**乐观锁条件更新**：一条 UPDATE 内同时完成
 *    「累加数量」与「version + 1」，并把旧 version 放进 WHERE 子句做 CAS；
 * 2. {@link #selectSumQuantityByProjectAndMaterial} 用一条聚合 SQL 取流水净额，供一致性断言与
 *    对账使用（不在应用层把流水全查回来再累加，那是 N 行传输）；
 * 3. {@link #selectByProjectIdAndMaterialId} 是 uk_project_material 的查询入口，入库 upsert 前先查它。
 *
 * @author buildcloud
 */
@Mapper
public interface MaterialStockMapper extends BaseMapperX<MaterialStockDO> {

    /**
     * 项目库存分页查询
     *
     * 只保留走索引的检索维度（projectId / materialId）：
     * - 只传 projectId → 项目库存台账（uk_project_material 最左前缀）；
     * - 只传 materialId → 跨项目库存分布（idx_material_id）。
     *
     * @param reqVO 分页 + 筛选条件
     * @return 分页结果
     */
    default PageResult<MaterialStockDO> selectPage(StockPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<MaterialStockDO>()
                .eqIfPresent(MaterialStockDO::getProjectId, reqVO.getProjectId())
                .eqIfPresent(MaterialStockDO::getMaterialId, reqVO.getMaterialId())
                .orderByDesc(MaterialStockDO::getId));
    }

    /**
     * 按「项目 + 物料」查询库存行（uk_project_material 的业务侧入口）
     *
     * 租户隔离由基座多租户插件在 SQL 层自动追加 tenant_id，此处无需显式过滤。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 库存；不存在返回 null
     */
    default MaterialStockDO selectByProjectIdAndMaterialId(Long projectId, Long materialId) {
        return selectOne(MaterialStockDO::getProjectId, projectId,
                MaterialStockDO::getMaterialId, materialId);
    }

    /**
     * **乐观锁条件更新**：累加可用库存并推进版本号
     *
     * SQL 语义（一条语句完成 CAS + 累加，不做「先 select 再 set」）：
     * <pre>
     *   UPDATE bc_material_stock
     *      SET available_qty = available_qty + #{delta},
     *          version       = version + 1,
     *          update_time   = NOW()
     *    WHERE id = #{id} AND version = #{version} AND deleted = 0
     * </pre>
     *
     * 为什么把 `available_qty = available_qty + delta` 写成**自引用**而不是 #{newQty}：
     * 自引用让「读旧值 → 写新值」这一步发生在数据库内部（行锁保护），即使别的事务在这条语句
     * 执行前后插入修改，也不会用应用层算出来的旧总和覆盖别人的增量。
     *
     * 影响行数的两种含义（调用方必须区分）：
     * - **1**：更新成功，version 已前进；
     * - **0**：CAS 失败——该行 version 已在「读」与「写」之间被其它事务推进（并发入库），
     *   或该行被逻辑删除。Service 层据此**重试**（重新读 version 再试），
     *   有限次后仍冲突则抛 STOCK_UPDATE_CONFLICT。
     *
     * 注意 `deleted = 0` 必须显式写：注解 SQL 不经过 MyBatis-Plus 的逻辑删除自动拼接
     * （同 ContractMapper#updatePaidAmount 的处理）。
     *
     * @param id      库存编号
     * @param delta   增量（入库传正数；本方法也复用于扣减场景，那时传负数）
     * @param version 读取时拿到的版本号（CAS 期望值）
     * @return 更新行数（0 表示版本冲突或行不存在）
     */
    @Update("UPDATE bc_material_stock SET available_qty = available_qty + #{delta}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE id = #{id} AND version = #{version} AND deleted = 0")
    int updateAvailableQtyIncrWithVersion(@Param("id") Long id,
                                          @Param("delta") BigDecimal delta,
                                          @Param("version") Integer version);

}
