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

    // ========== 申领审批 / 出库 / 退料（W4-P5，仍属 050 段：都是申领单的生命周期动作） ==========

    /**
     * 申领单已有审批中的流程实例
     *
     * 与 contract 域的 {@code CONTRACT_APPROVAL_ALREADY_RUNNING} 同构：同一张单并发发起两个流程，
     * 会得到两个待办、两次回调，驳回时甚至可能回补两次库存。提示语带上在途实例号，运维可直接定位。
     */
    ErrorCode REQUISITION_APPROVAL_ALREADY_RUNNING = new ErrorCode(1_102_050_006, "申领单已有审批中的流程（实例 {}），请先处理该流程");

    /**
     * 发起审批失败：bpm 未返回流程实例编号
     *
     * 必须抛错让事务回滚。否则业务表会留下「审批中(1) 但没有流程实例」的死状态：
     * 永远等不到回调、库存却已按 V1 口径扣减，人工也撤销不了（撤销要 state=1 的 CAS，能做但没人知道要做）。
     */
    ErrorCode REQUISITION_APPROVAL_START_FAILED = new ErrorCode(1_102_050_007, "发起申领审批失败：流程引擎未返回流程实例编号");

    /**
     * 当前状态不允许提交审批
     *
     * 只有草稿(0) 可提交审批；草稿以外（已提交/已扣减/已出库/已完成/已取消…）再提交会
     * 造成重复预扣或把终态单拉回流程，必须先取消重建。
     */
    ErrorCode REQUISITION_APPROVAL_STATUS_NOT_ALLOWED = new ErrorCode(1_102_050_008, "申领单当前状态【{}】不允许提交审批（仅草稿可提交）");

    /**
     * 会签审批人列表为空
     *
     * 会签的实例数 = 审批人个数（基座 BpmParallelMultiInstanceBehavior#resolveNrOfInstances），
     * 空列表会让基座补一个 null 审批人并可能「自动通过」——那是这张单在无人审批下被扣库存，
     * 属于必须拦住的账务风险，故显式报错而不是让它跑下去。
     */
    ErrorCode REQUISITION_APPROVERS_EMPTY = new ErrorCode(1_102_050_009, "会签审批人不能为空，请至少指定 1 人");

    /**
     * 当前状态不允许出库
     *
     * 出库（已扣减2 → 已出库3）是**一次性**动作：重复出库会把 actual_out_time 刷成新时间、
     * 让「在途料时长」报表失真，故用状态 CAS 拦住，并给出可读原因。
     */
    ErrorCode REQUISITION_CANNOT_OUTBOUND = new ErrorCode(1_102_050_010, "申领单当前状态【{}】不允许出库（仅已扣减可出库）");

    /**
     * 当前状态不允许退料
     *
     * 退料只发生在「已出库(3) / 部分回退(5)」：库存还没出库时退料等于撤销申领（走 cancel 回补+冲正），
     * 路径不同、流水类型也不同（3-退回 vs 4-冲正），混用会让对账无法按类型分组。
     */
    ErrorCode REQUISITION_CANNOT_RETURN = new ErrorCode(1_102_050_011, "申领单当前状态【{}】不允许退料（仅已出库/部分回退可退料）");

    /**
     * 退料数量超过可退量
     *
     * 可退量 = 明细 quantity - returned_qty（已退累计）。提示语给出物料、可退量与申请量，
     * 前端可直接展示，不需要再查一次详情。
     */
    ErrorCode REQUISITION_RETURN_QUANTITY_EXCEEDED = new ErrorCode(1_102_050_012, "退料数量超过可退量（物料 {}，可退 {}，本次 {}）");

    /**
     * 退料明细中的物料不在该申领单里
     *
     * 不允许「退一张单没领过的料」：那会让库存净增却不挂任何单据，是对账断点的典型来源。
     */
    ErrorCode REQUISITION_RETURN_ITEM_NOT_IN_REQUISITION = new ErrorCode(1_102_050_013, "退料明细中的物料【{}】不在该申领单中");

    /**
     * 退料明细为空
     *
     * 与 {@link #REQUISITION_ITEM_EMPTY} 分开：建单为空是「单据没内容」，
     * 退料为空是「点了退料按钮但没填数量」，前端提示完全不同。
     */
    ErrorCode REQUISITION_RETURN_ITEM_EMPTY = new ErrorCode(1_102_050_014, "退料明细不能为空，请至少填写一行退料数量");

    /**
     * 退料数量必须大于 0
     *
     * 与建单的「申领数量必须大于 0」分开的原因：建单是参数校验（invalidParamException 级别），
     * 而这里是**业务动作**的语义校验——退 0 等于没退，却会写出一条 quantity=0 的流水
     * （biz_type=3），让「退回笔数」与「实际回补量」对不上，对账时凭空多出噪声行。
     * 数量为负更严重：会变成「反向申领」把库存扣掉，必须显式拦住。
     */
    ErrorCode REQUISITION_RETURN_QUANTITY_INVALID = new ErrorCode(1_102_050_015, "退料数量必须大于 0（物料 {}，本次 {}）");

    /**
     * 退料明细中的物料重复
     *
     * 与 {@link #REQUISITION_ITEM_MATERIAL_DUPLICATE}（建单）同构但独立成码：退料是
     * 「按物料的原子条件更新 returned_qty」，同一物料出现两行时两行各自校验可退量，
     * 会同时通过却合计超过可退量（与 V1 扣减重复物料完全同一类缺陷），故必须合并成一行。
     */
    ErrorCode REQUISITION_RETURN_ITEM_DUPLICATE = new ErrorCode(1_102_050_016, "退料明细中物料【{}】重复，请合并为一行");

    /**
     * 会签审批人人数超过上限
     *
     * 会签实例数 = 审批人个数（基座 BpmParallelMultiInstanceBehavior#resolveNrOfInstances），
     * 误传「整个部门」这类大列表会在 Flowable 里创建海量并行任务，审批列表被淹没且永远集不齐。
     * 上限由 {@code RequisitionApprovalSubmitReqVO#MAX_APPROVERS} 定义，两边引用同一常量。
     */
    ErrorCode REQUISITION_APPROVERS_EXCEED = new ErrorCode(1_102_050_017, "会签审批人最多 {} 人（当前 {} 人）");

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
