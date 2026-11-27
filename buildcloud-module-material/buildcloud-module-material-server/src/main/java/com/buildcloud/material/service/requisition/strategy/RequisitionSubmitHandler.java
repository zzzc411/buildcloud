package com.buildcloud.material.service.requisition.strategy;

import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.enums.requisition.RequisitionModeEnum;

import java.util.List;

/**
 * 申领提交策略接口（spec v1.2 §6.1「V1/V2 用配置开关切换（同一接口两实现），保证压测可比」）
 *
 * ========== 为什么用「接口 + 两实现 + 工厂」而不是 @ConditionalOnProperty 二选一 ==========
 *
 * 二选一（`@ConditionalOnProperty` 直接挑 bean）确实更"干净"，但在本项目里有两个具体代价：
 * 1. **配置写错时是启动失败**：`requisition-mode: V3` 会让两个 bean 都不注册，
 *    Service 注入失败 → 服务起不来。压测现场改配置写错一个字母就整机不可用，
 *    而归属于「配置错误」的失败应该以**业务错误码**告诉调用方（1-102-070-000），而不是启动崩溃；
 * 2. **W5 的灰度需要两实现同时存在**：spec §6.1 的比压测要求「同一份代码 + 一个开关」就能来回切，
 *    两实现同时在容器里，才能做到「改 Nacos 配置 → 下一次请求生效」而不重启。
 *    若用 conditional bean，切模式必须重启，压测中途换组就要重来。
 *
 * 因此这里两实现都常驻，由 {@link RequisitionSubmitHandlerFactory} 按配置值**分发**；
 * V2 的实现体当前只抛「未实现」错误码，等 W5 填内容，**接口签名与分发逻辑都不用再动**。
 *
 * ========== 事务契约（实现类必须遵守） ==========
 *
 * {@link #submit} **不自己开事务**，它运行在调用方 {@code RequisitionServiceImpl#submitRequisition}
 * 的同一个 `@Transactional` 里。理由：V1 的「锁库存行 → 扣减 → 写单据与流水」必须同成同败
 * （spec §6.1 V1 事务边界）；若各自 `@Transactional(REQUIRES_NEW)`，
 * 扣减成功后写单失败就会留下「库存少了但没有单据」的脏数据，且无补偿路径。
 *
 * @author buildcloud
 */
public interface RequisitionSubmitHandler {

    /**
     * 本实现对应的模式
     *
     * @return 模式枚举
     */
    RequisitionModeEnum getMode();

    /**
     * 提交申领：扣减库存并产生库存流水（**由调用方保证在事务内执行**）
     *
     * 前置条件（由 {@code RequisitionServiceImpl} 保证，实现类可复用不必重复校验）：
     * 1. {@code requisition.getStatus()} 已推进到「已提交(1)」，且 {@code reqNo} 已生成；
     * 2. 明细非空、每行 quantity > 0、同一物料不重复。
     *
     * 后置条件：
     * 1. 库存 {@code available_qty} 已按明细扣减；
     * 2. 每行明细产生一条 {@code biz_type=2} 的流水（quantity 记负数、balance_after=扣减后余额、
     *    biz_no=req_no）；
     * 3. V1 实现额外把单据推进到「已扣减(2)」。
     *
     * @param requisition 已锁定的申领单（状态=已提交）
     * @param items       明细（顺序不保证，实现类需自行按需排序以避免死锁）
     */
    void submit(RequisitionDO requisition, List<RequisitionItemDO> items);

    /**
     * 取消/冲正：回补库存并写冲正流水（**由调用方保证在事务内执行**）
     *
     * 与 {@link #submit} 对称：V1 用行锁回补，V2（W5）用 Lua 恢复 Redis 库存 + 冲正流水。
     *
     * @param requisition 已锁定的申领单（状态=已提交/已扣减）
     * @param items       明细
     */
    void cancel(RequisitionDO requisition, List<RequisitionItemDO> items);

}
