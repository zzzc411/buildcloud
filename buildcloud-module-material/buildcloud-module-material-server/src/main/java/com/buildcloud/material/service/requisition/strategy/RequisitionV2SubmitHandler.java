package com.buildcloud.material.service.requisition.strategy;

import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.enums.requisition.RequisitionModeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_MODE_NOT_IMPLEMENTED;

/**
 * V2 申领提交策略：**占位实现**（W5 填充，spec §6.1 V2 架构）
 *
 * 为什么现在就要有这个类（而不是干脆不注册 V2 的 bean）：
 * 1. **让开关的两端都有实体**：`requisition-mode: V2` 时必须能**明确报未实现**（1-102-070-000），
 *    而不是「配置了但没人认领，服务起不来」或「静默回退 V1」。后者最危险——
 *    W5 压测时会以为在压 V2、实际压的是 V1，对照组与实验组同时作废；
 * 2. **固定契约**：W5 只需把本类的方法体填成「Lua 预扣 + MQ 事务消息」，接口、分发、
 *    Controller、单测骨架都不用动，改动面收敛到一个文件；
 * 3. **单测可直接断言"V2 未实现"**：这是验收标准「V1/V2 开关可切换」的可执行证据。
 *
 * W5 落地时本类的实现要点（从 spec §6.1 摘录，供下一个 worker 直接照做）：
 * <pre>
 * submit:
 *   ① Sentinel 限流（接口级 + 热点参数 materialId）—— 在 Controller/网关侧，不在本方法
 *   ② 物料存在性：布隆过滤器 + 两级缓存
 *   ③ Redis Lua 原子预扣：KEY pm:stock:{projectId}:{materialId}
 *      available >= qty ? decr + 记录预扣明细(reqNo→hash) : 返回失败
 *   ④ 发 RocketMQ 事务消息（half 消息）
 *        本地事务：单据(已提交) + locked_qty+ / available_qty- + pending 扣减流水
 *        commit → 消费者：DB 乐观锁落库 + stock_log 落分片表 + 状态→已扣减
 *   ⑤ 审批通过 → 出库；驳回 → Lua 恢复 + 冲正流水
 *   ⑥ 超时关单：延迟消息 30 分钟（timeout_time 已在 V1 写入，可直接接管）
 *   ⑦ 对账兜底：XXL-Job 比对 Redis 预扣 vs DB 流水
 * </pre>
 *
 * 注意 ④ 里的「本地事务」与 V1 的关键差别：V1 的扣减在**请求线程**里同步完成（行锁，慢但强一致）；
 * V2 的扣减在**消费者线程**里异步完成（快，最终一致），请求线程只做 Redis 预扣。
 * 这就是 W5 压测要量出来的吞吐差。
 *
 * @author buildcloud
 */
@Component
@Slf4j
public class RequisitionV2SubmitHandler implements RequisitionSubmitHandler {

    @Override
    public RequisitionModeEnum getMode() {
        return RequisitionModeEnum.V2;
    }

    @Override
    public void submit(RequisitionDO requisition, List<RequisitionItemDO> items) {
        // 明确失败，绝不静默回退 V1（见类注释第 1 点）
        log.warn("[submit][V2 链路未实现，拒绝执行 reqNo={}]（W5 交付：Redis Lua 预扣 + MQ 异步落库）",
                requisition == null ? null : requisition.getReqNo());
        throw exception(REQUISITION_MODE_NOT_IMPLEMENTED, RequisitionModeEnum.V2.getMode());
    }

    @Override
    public void cancel(RequisitionDO requisition, List<RequisitionItemDO> items) {
        log.warn("[cancel][V2 链路未实现，拒绝执行 reqNo={}]",
                requisition == null ? null : requisition.getReqNo());
        throw exception(REQUISITION_MODE_NOT_IMPLEMENTED, RequisitionModeEnum.V2.getMode());
    }

}
