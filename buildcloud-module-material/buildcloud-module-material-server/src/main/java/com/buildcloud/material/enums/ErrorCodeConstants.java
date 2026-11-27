package com.buildcloud.material.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * Material 材料域错误码枚举类
 *
 * material 模块使用 1-102-000-000 段（spec v1.2 §7.2 段位分配；AGENTS §4 错误码独立段位）；
 * 同仓对照：project 1-100-xxx / contract 1-101-xxx / report 1-103-xxx / ai 1-104-xxx。
 *
 * 段位内编号约定（按实体分组，与材料域 7 张表一一对应，便于 DAO/Service 对照阅读）：
 * - 010 段：材料目录（bc_material_category）；
 * - 020 段：物料（bc_material）；
 * - 030 段：供应商（bc_supplier）；
 * - 040 段：库存（bc_material_stock）；
 * - 050 段：申领单与明细（bc_requisition / bc_requisition_item）；
 * - 060 段：库存流水（bc_stock_log）。
 *
 * 命名约定与 project 模块一致：`{实体}_NOT_EXISTS` 表示按主键/唯一键查不到；
 * `{实体}_{字段}_DUPLICATE` 表示唯一性冲突（由 Service 层查重或唯一索引捕获后转换）。
 *
 * @author buildcloud
 */
public interface ErrorCodeConstants {

    // ========== 材料目录 1-102-010-000 ==========
    ErrorCode MATERIAL_CATEGORY_NOT_EXISTS = new ErrorCode(1_102_010_000, "材料目录不存在");
    ErrorCode MATERIAL_CATEGORY_NAME_DUPLICATE = new ErrorCode(1_102_010_001, "同级下已存在同名材料目录");
    /**
     * 目录下仍有子目录，拒绝删除
     *
     * 树形结构必须保证「先叶子后父节点」，否则子目录的 parent_id 会指向已被删除的节点，
     * 整棵树在加载时出现挂不上的孤儿节点（前端树组件直接丢节点，且难以排查）。
     */
    ErrorCode MATERIAL_CATEGORY_HAS_CHILDREN = new ErrorCode(1_102_010_002, "该材料目录下存在子目录，无法删除");

    // ========== 物料 1-102-020-000 ==========
    ErrorCode MATERIAL_NOT_EXISTS = new ErrorCode(1_102_020_000, "物料不存在");
    ErrorCode MATERIAL_NO_DUPLICATE = new ErrorCode(1_102_020_001, "物料编码已存在");

    // ========== 供应商 1-102-030-000 ==========
    ErrorCode SUPPLIER_NOT_EXISTS = new ErrorCode(1_102_030_000, "供应商不存在");
    ErrorCode SUPPLIER_NO_DUPLICATE = new ErrorCode(1_102_030_001, "供应商编码已存在");

    // ========== 库存 1-102-040-000 ==========
    ErrorCode STOCK_NOT_EXISTS = new ErrorCode(1_102_040_000, "该项目的物料库存记录不存在");
    /**
     * 可用库存不足，拒绝扣减
     *
     * V1 链路在 `SELECT ... FOR UPDATE` 锁行后由 Service 校验抛出；
     * V2 链路则由 Redis Lua 预扣失败或 DB 乐观锁条件更新影响行数 0 时抛出
     * （spec §6.1「防超卖三道防线」的第二道，最后一道是对账 Job）。
     * 提示语带上可用量与需求量，便于前端直接给出「库存不足」的量化说明。
     */
    ErrorCode STOCK_NOT_ENOUGH = new ErrorCode(1_102_040_001, "库存不足（可用 {}，需要 {}）");

    // ========== 申领单 1-102-050-000 ==========
    ErrorCode REQUISITION_NOT_EXISTS = new ErrorCode(1_102_050_000, "材料申领单不存在");
    ErrorCode REQUISITION_NO_DUPLICATE = new ErrorCode(1_102_050_001, "申领单号已存在");
    /**
     * 申领单状态流转不正确
     *
     * spec §5.2 状态机：草稿0 → 已提交1 → 已扣减2 → 已出库3 → 已完成4，旁路 部分回退5 / 已关闭8 / 已取消9。
     * 与 project 的 `PROJECT_STATUS_TRANSITION_INVALID` 同构：提示语带上源状态与目标状态，
     * 便于前端在按钮误点（如对已完成单据点驳回）时给出可读原因。
     */
    ErrorCode REQUISITION_STATUS_INVALID = new ErrorCode(1_102_050_002, "申领单状态不允许从【{}】流转到【{}】");

    // ========== 库存流水 1-102-060-000 ==========
    ErrorCode STOCK_LOG_NOT_EXISTS = new ErrorCode(1_102_060_000, "库存流水不存在");

}
