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
 * - 060 段：库存流水（bc_stock_log）；
 * - 070 段：V1/V2 申领链路开关（spec §6.1「同一接口两实现」，W5 起扩展）。
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
    /**
     * 入库数量非法（必须 > 0）
     *
     * 入库只走正向增量（biz_type=1，quantity 记正数）；减少库存必须走申领扣减(2)/退回(3)/冲正(4)，
     * 各自由对应业务链路写入并留痕。若允许「负数入库」，则同一笔业务既可能是入库也可能是出库，
     * 流水的 biz_type 就失去意义，对账时也无法按类型分组核对（spec §5.2 流水口径）。
     */
    ErrorCode STOCK_INBOUND_QUANTITY_INVALID = new ErrorCode(1_102_040_002, "入库数量必须大于 0（当前 {}）");
    /**
     * 库存乐观锁冲突重试耗尽
     *
     * 入库用 `UPDATE ... WHERE id = ? AND version = ?` 条件更新，影响行数 0 表示该行在
     * 「读版本 → 条件更新」之间被其它事务改过（version 已前进）。有限次重试后仍冲突说明热点极端
     * （同一项目同一物料被高频并发写入），此时快速失败比无限重试更安全——避免请求线程被拖死、
     * 连接池被耗尽。提示语带上重试次数，便于压测时定位热点。
     */
    ErrorCode STOCK_UPDATE_CONFLICT = new ErrorCode(1_102_040_003, "库存更新冲突，已重试 {} 次仍失败，请稍后重试");

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
    /**
     * 申领明细为空
     *
     * 一张申领单至少要有一行物料：允许空明细会出现「状态已提交/已扣减但库存一分未动」的空单据，
     * 既污染申领量统计，也让「已扣减」这个状态失去含义（对账时无法解释）。
     */
    ErrorCode REQUISITION_ITEM_EMPTY = new ErrorCode(1_102_050_003, "申领明细不能为空，请至少添加一行物料");
    /**
     * 申领明细中的物料重复
     *
     * 同一物料在明细里出现两行时，V1 的逐行加锁扣减会**对同一行库存行两次加锁**——
     * MySQL 下同一事务内第二次 `SELECT ... FOR UPDATE` 是重入的（不报错、直接读到本事务已改的值），
     * 所以不会死锁，却会让「校验可用量」的语义变得不明显：两行各校验一次 50，
     * 而实际只需要 100，任一时刻只有第一行校验时库存充足。合并成一行扣减才是唯一清晰的语义。
     */
    ErrorCode REQUISITION_ITEM_MATERIAL_DUPLICATE = new ErrorCode(1_102_050_004, "申领明细中物料【{}】重复，请合并为一行");
    /**
     * 申领单当前状态不允许取消
     *
     * 与 {@link #REQUISITION_STATUS_INVALID} 分开的理由：取消的业务前置是「动过库存的两个状态」，
     * 前端需要对「已完成/已取消」给出「单据已结束，无需取消」这类明确提示，而不是笼统的「状态流转非法」。
     */
    ErrorCode REQUISITION_CANNOT_CANCEL = new ErrorCode(1_102_050_005, "申领单当前状态【{}】不允许取消");

    // ========== 库存流水 1-102-060-000 ==========
    ErrorCode STOCK_LOG_NOT_EXISTS = new ErrorCode(1_102_060_000, "库存流水不存在");

    // ========== V1/V2 申领链路开关 1-102-070-000 ==========
    /**
     * V2 申领链路尚未实现
     *
     * spec §6.1 定义的 V2（Redis Lua 预扣 + RocketMQ 事务消息 + 异步落库）属 W5 交付；
     * W4 只交付 V1（`SELECT ... FOR UPDATE` 同步扣减，压测对照组）。
     * 开关存在但选了 V2 时必须**明确报未实现**，绝不能静默回退到 V1——
     * 否则 W5 压测时会以为在压 V2、实际压的是 V1，对照组与实验组同时失效。
     */
    ErrorCode REQUISITION_MODE_NOT_IMPLEMENTED = new ErrorCode(1_102_070_000, "申领链路模式【{}】尚未实现（当前仅支持 V1）");

}
