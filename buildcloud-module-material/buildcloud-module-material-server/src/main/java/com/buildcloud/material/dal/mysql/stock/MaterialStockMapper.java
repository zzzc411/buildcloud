package com.buildcloud.material.dal.mysql.stock;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.buildcloud.material.controller.admin.stock.vo.StockPageReqVO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
 * 4. W4-P4 追加三条**申领 V1 行锁链路**方法（{@link #selectByProjectIdAndMaterialIdForUpdate} /
 *    {@link #reduceAvailableQtyUnderLock} / {@link #increaseAvailableQtyUnderLock}）——
 *    它们与 {@link #updateAvailableQtyIncrWithVersion} 的 CAS 是**两套并存的写入策略**：
 *    入库走 CAS（低频、无长事务），申领扣减/取消回补走行锁（spec §6.1 明确的行锁对照组）。
 *    不要因为"看起来能统一"就把申领也改成 CAS ——那会毁掉 W5 的对照基线（见方法注释）。
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

    // ========== 申领扣减（V1 行锁链路，W4-P4） ==========

    /**
     * **悲观行锁查询**：按「项目 + 物料」锁定库存行（V1 申领扣减的第一步，spec §6.1 原文）
     *
     * <pre>
     *   SELECT ... FROM bc_material_stock
     *    WHERE project_id = #{projectId} AND material_id = #{materialId} AND deleted = 0
     *      FOR UPDATE
     * </pre>
     *
     * ========== 为什么手写原生 @Select，而不用基座现成的 selectOneForUpdate ==========
     *
     * 基座 {@code BaseMapperX#selectOneForUpdate} 走的是 `SelectForUpdateMethod` 内置查询 +
     * `ForUpdateInnerInterceptor`，会额外校验「必须在事务中」「首行锁要求 READ COMMITTED」，
     * 且它的 wrapper 只支持 LambdaQueryWrapper 的等值条件。本处需求特殊，选原生 SQL 的理由是：
     * 1. **显式可读**：V1 是压测对照组，面试/复核时「这条 SQL 到底是什么」必须一眼可见，
     *    而不是要读者去追 `SelectForUpdateMethod` + 拦截器改写；
     * 2. `deleted = 0` 必须**自己写**：注解 SQL 不经过 MyBatis-Plus 逻辑删除自动拼接
     *    （同本类 `updateAvailableQtyIncrWithVersion` 的处理）。不写就会把逻辑删除的库存行也锁进来，
     *    进而扣减一行「已删除」的库存——余额对了但对账口径全错；
     * 3. 多租户 `tenant_id` 仍由 `TenantLineInnerInterceptor` 自动追加（`bc_material_stock` 对应 DO
     *    `extends TenantBaseDO`，见 `TenantDatabaseInterceptor#computeIgnoreTable`），
     *    所以这里**不要**手写 tenant_id，否则会变成两遍 `tenant_id = ?` 且第二遍参数不好绑。
     *
     * ========== 事务要求（调用方责任，写在这里防止误用） ==========
     *
     * 本方法**必须在 {@code @Transactional} 方法内调用**：
     * - 自动提交（无事务）下 MySQL 会在语句结束即释放行锁，`SELECT ... FOR UPDATE` 等于没加锁，
     *   并发下会退化成「无保护的读-改-写」→ 超卖。基座的 `ForUpdateInnerInterceptor` 对这种误用
     *   会抛异常，但本方法走原生 SQL 绕过了它，**没有这层保护**，只能在调用侧用代码约定兜住
     *   （`RequisitionServiceImpl#submitRequisition` 与 V1 策略实现类都标了 `@Transactional`）。
     *
     * 为什么在 H2 单测里也能验证出并发正确性：H2 的 MVStore/MVCC 同样支持 `SELECT ... FOR UPDATE`
     * 的行级锁与锁等待，并发用例（10 线程各申领 10、库存 50）实测确实被串行化。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 被锁定的库存行；不存在返回 null（调用方按 STOCK_NOT_EXISTS 处理）
     */
    @Select("SELECT id, project_id, material_id, available_qty, locked_qty, version, remark, "
            + "tenant_id, creator, create_time, updater, update_time, deleted "
            + "FROM bc_material_stock "
            + "WHERE project_id = #{projectId} AND material_id = #{materialId} AND deleted = 0 "
            + "FOR UPDATE")
    MaterialStockDO selectByProjectIdAndMaterialIdForUpdate(@Param("projectId") Long projectId,
                                                            @Param("materialId") Long materialId);

    /**
     * **行锁下的直接扣减**（V1 专用）：把 available_qty 减掉指定数量
     *
     * <pre>
     *   UPDATE bc_material_stock
     *      SET available_qty = available_qty - #{quantity},
     *          version       = version + 1,
     *          update_time   = NOW()
     *    WHERE id = #{id} AND available_qty >= #{quantity} AND deleted = 0
     * </pre>
     *
     * ========== 为什么 V1 不用 CAS 重试（这条注释是 W5 对照组的口径依据，别"优化"掉） ==========
     *
     * 1. **V1 的对照组价值就在于「串行」**：spec §6.1 原文「V1 行锁串行化，热点库存记录成为瓶颈，
     *    吞吐随并发骤降——这是压测对照组，不是要保留的方案」。若这里改写成
     *    `WHERE version = #{version}` 的 CAS + 重试，它就不再是「行锁对照组」，
     *    W5 的 V2 压测失去可比基线（两个版本都是乐观锁，差异被抹平）；
     * 2. **正确性由行锁保证，不需要 CAS**：调用方已在同一事务内 `FOR UPDATE` 锁住该行，
     *    其它事务的扣减会阻塞在锁上，读到的一定是最新已提交值，故这里不需要版本条件；
     * 3. `version = version + 1` 仍然保留：不是拿来做 CAS，而是让「这一行变过几次」可观测，
     *    也是将来若有人误把本方法当 CAS 用时能留下痕迹（单测会断言 V1 扣减后 version 递增）。
     *
     * `available_qty >= #{quantity}` 这个条件在 V1 里是**第二道保险**（第一道是 Service 在锁内校验）：
     * 万一将来有人把本方法挪到非事务上下文调用（丢了行锁），这条条件更新仍能保证不扣成负数
     * （影响行数 0 → 调用方按库存不足处理）。代价是它让这条 UPDATE 能命中索引判断，
     * 但本语句已经按主键定位，代价可忽略。
     *
     * @param id       库存编号（已被 FOR UPDATE 锁定）
     * @param quantity 扣减数量（正数）
     * @return 影响行数（1 成功；0 表示可用量不足或行不存在/已删除）
     */
    @Update("UPDATE bc_material_stock SET available_qty = available_qty - #{quantity}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE id = #{id} AND available_qty >= #{quantity} AND deleted = 0")
    int reduceAvailableQtyUnderLock(@Param("id") Long id, @Param("quantity") BigDecimal quantity);

    /**
     * **行锁下的直接回补**（V1 取消/冲正专用）：把 available_qty 加回指定数量
     *
     * 与 {@link #updateAvailableQtyIncrWithVersion} 的区别：
     * - 那个是**入库**用的 CAS（带 version 条件 + 重试），入库是低频管理动作；
     * - 这个是**取消申领**用的行锁内直接累加（调用方已 `FOR UPDATE` 锁行），与扣减同对称。
     *
     * 为什么返回值不做 `>= 0` 之类的约束：回补只增不减，不存在「回补成负数」；
     * 唯一要防的是对同一单据重复回补，那是靠「取消前后状态机校验 + 冲正流水存在性」兜的，
     * 不是靠这条 SQL（SQL 层无从知道"这笔业务是否已回补过"）。
     *
     * @param id       库存编号（已被 FOR UPDATE 锁定）
     * @param quantity 回补数量（正数）
     * @return 影响行数（1 成功；0 表示行不存在或已删除）
     */
    @Update("UPDATE bc_material_stock SET available_qty = available_qty + #{quantity}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE id = #{id} AND deleted = 0")
    int increaseAvailableQtyUnderLock(@Param("id") Long id, @Param("quantity") BigDecimal quantity);

    // ========== V2 申领扣减（MQ 消费者，W5-P2） ==========

    /**
     * **V2 消费者的 DB 真实扣减**：可用量条件更新（防超卖第二道防线，spec §6.1）
     *
     * <pre>
     *   UPDATE bc_material_stock
     *      SET available_qty = available_qty - #{quantity},
     *          version       = version + 1,
     *          update_time   = NOW()
     *    WHERE project_id = #{projectId} AND material_id = #{materialId}
     *      AND available_qty >= #{quantity} AND deleted = 0
     * </pre>
     *
     * ========== 与 V1 的 {@link #reduceAvailableQtyUnderLock} 是两套东西，别合并 ==========
     *
     * | | V1 行锁扣减 | 本方法（V2 消费者） |
     * | --- | --- | --- |
     * | 定位 | 先 `FOR UPDATE` 锁行，再按主键 UPDATE | **不锁行**，直接按 (project_id, material_id) 条件 UPDATE |
     * | 并发正确性 | 来自行锁（串行化） | 来自 `available_qty >= quantity` 这条 **WHERE 条件** |
     * | 影响 0 行的含义 | 不该发生（锁内刚校验过） | **Redis 与 DB 已漂移**：Redis 说够，DB 不够 |
     * | 冲突处理 | 直接报库存不足 | 报错 → 让 MQ 重试 → 超过次数进死信 + 告警（对账 Job 兜底） |
     *
     * ========== 为什么 WHERE 用 (project_id, material_id) 而不是主键 id ==========
     *
     * 消费者只拿到消息里的 projectId / materialId，**不去反查库存行**：
     * 「先 SELECT 拿 id → 再 UPDATE」中间会有窗口（行被逻辑删除/重建），而且多一次往返。
     * 直接把业务唯一键 `uk_project_material` 写进 WHERE，一条语句完成「定位 + 校验 + 扣减」，
     * 这也是 spec §6.1 步骤④ 原文的语句形状。
     *
     * `deleted = 0` 必须自己写（注解 SQL 不走 MyBatis-Plus 逻辑删除拼接）；
     * `tenant_id` 由多租户插件自动追加（DO extends TenantBaseDO，AGENTS R-8）。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @param quantity   扣减数量（正数）
     * @return 影响行数（1 成功；**0 = 可用量不足或库存行不存在/已删除**，调用方必须按故障处理）
     */
    @Update("UPDATE bc_material_stock SET available_qty = available_qty - #{quantity}, "
            + "version = version + 1, update_time = NOW() "
            + "WHERE project_id = #{projectId} AND material_id = #{materialId} "
            + "AND available_qty >= #{quantity} AND deleted = 0")
    int reduceAvailableQtyByBusinessKey(@Param("projectId") Long projectId,
                                        @Param("materialId") Long materialId,
                                        @Param("quantity") BigDecimal quantity);

}
