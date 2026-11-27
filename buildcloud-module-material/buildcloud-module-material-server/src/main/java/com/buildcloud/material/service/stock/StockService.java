package com.buildcloud.material.service.stock;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.buildcloud.material.controller.admin.stock.vo.StockInboundReqVO;
import com.buildcloud.material.controller.admin.stock.vo.StockLogPageReqVO;
import com.buildcloud.material.controller.admin.stock.vo.StockPageReqVO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import jakarta.validation.Valid;

/**
 * 项目库存 Service 接口
 *
 * 业务边界（spec v1.2 §5.2 用例「入库（写 stock + stock_log）」、§6.1 V1 事务边界）：
 * 1. **入库**：同一事务内 upsert `bc_material_stock`（项目+物料 唯一，version 乐观锁）
 *    + 写 `bc_stock_log`（biz_type=1 入库，quantity 记正数，balance_after=更新后 available_qty）。
 *    两者必须同成同败——只写流水不更新库存会让余额与流水不一致，只更新库存不写流水会让
 *    变更不可追溯且对账时 SUM(quantity) ≤ available_qty。
 * 2. **扣减**（V1 `SELECT ... FOR UPDATE`）在 W4-P4 实现，本接口不提供，避免与申领链路的
 *    状态机/审批回流耦合。
 * 3. 查询只读，无事务。
 *
 * @author buildcloud
 */
public interface StockService {

    /**
     * 材料入库
     *
     * 同一事务内完成：
     * 1. 按「项目 + 物料」查库存行；
     * 2. 不存在 → 新建（available_qty=quantity、locked_qty=0、version=0），再写流水；
     * 3. 存在 → 用**乐观锁条件更新**累加（`UPDATE ... WHERE id=? AND version=?`），
     *    影响行数 0（版本冲突）则有限次重试，仍失败抛 STOCK_UPDATE_CONFLICT；
     * 4. 写流水 biz_type=1、quantity=+qty、balance_after=更新后 available_qty。
     *
     * @param reqVO 入库请求（项目/物料/数量/备注）
     * @return 本次入库产生的流水（含生成的 bizNo 与 balanceAfter，便于前端直接展示入库单号）
     */
    StockLogDO inbound(@Valid StockInboundReqVO reqVO);

    /**
     * 获得库存
     *
     * @param id 库存编号
     * @return 库存
     */
    MaterialStockDO getStock(Long id);

    /**
     * 获得库存分页
     *
     * @param pageReqVO 分页查询（按 projectId / materialId 筛）
     * @return 库存分页
     */
    PageResult<MaterialStockDO> getStockPage(StockPageReqVO pageReqVO);

    /**
     * 获得库存流水分页
     *
     * @param pageReqVO 分页查询（按 projectId + materialId + bizType + 时间范围筛）
     * @return 流水分页
     */
    PageResult<StockLogDO> getStockLogPage(StockLogPageReqVO pageReqVO);

    /**
     * 校验某项目某物料库存是否平衡（available_qty == SUM(stock_log.quantity)）
     *
     * 供对账 Job（W5/W6）与运维排查使用；单测亦用它做整体一致性断言。
     *
     * @param projectId  项目编号
     * @param materialId 物料编号
     * @return 平衡时返回该库存行；不平衡时抛 IllegalStateException 并带上两个数值
     */
    MaterialStockDO validateStockBalance(Long projectId, Long materialId);

}
