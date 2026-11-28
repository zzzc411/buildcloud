package com.buildcloud.material.service.requisition.listener;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEventListener;
import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import com.buildcloud.material.enums.ApiConstants;
import com.buildcloud.material.enums.requisition.RequisitionBpmConstants;
import com.buildcloud.material.service.requisition.RequisitionApprovalService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 材料申领审批结果监听器（**双模式**：既是进程内 Spring 事件监听器，又是跨进程 HTTP 回调入口）
 *
 * 完全复刻 W3 的 {@code ProjectApprovalListener} / {@code ContractApprovalListener} 与基座
 * {@code oa-server} 的 {@code OaLeaveApplyStatusListener}：同一个类同时承担两种部署形态，
 * 一套代码两种用法（ADR-003 授权的扩展点形态）：
 *
 * | 形态 | 触发路径 | 说明 |
 * | --- | --- | --- |
 * | 单体（同 JVM） | bpm 发布 Spring 事件 → 本类 {@code onApplicationEvent} | 基类按 key 过滤后进 {@code onEvent} |
 * | 微服务（本项目） | bpm-server 的 {@code RequisitionApproveStatusListener} POST 到下面的 @PostMapping → 本类 {@code onEvent} | 基类不参与，由 Spring MVC 直接调用 |
 *
 * 为什么继承而不是组合：基类 {@link BpmProcessInstanceStatusEventListener} 的
 * {@code onApplicationEvent} 是 {@code final} 且已实现「按 processDefinitionKey 过滤」，
 * 继承即复用该过滤逻辑，避免两处各写一遍 key 判断（写漏就是全量事件乱入）。
 *
 * ========== 三处路径必须一致的核对表（写漏一处 → 静默断链，L2-12） ==========
 *
 * 1. 本类 {@code @PostMapping} 路径 = {@link ApiConstants#PREFIX} + {@link RequisitionBpmConstants#CALLBACK_PATH_UPDATE_AUDIT_STATUS}
 *    → {@code /rpc-api/buildcloud-material/requisition/update-audit-status}；
 * 2. bpm-server 侧 {@code RequisitionApproveStatusListener#CALLBACK_URL}
 *    → {@code http://buildcloud-material-server/rpc-api/buildcloud-material/requisition/update-audit-status}；
 * 3. 服务名必须等于 {@code spring.application.name}（由 Nacos 服务发现解析）。
 * 三者任一不一致 → bpm 日志里连 {@code sendHttpRequest} 都没有（或 404），业务状态纹丝不动。
 *
 * @author buildcloud
 */
@RestController
@Validated
@Slf4j
public class RequisitionApprovalListener extends BpmProcessInstanceStatusEventListener {

    @Resource
    private RequisitionApprovalService requisitionApprovalService;

    @Override
    public String getProcessDefinitionKey() {
        // 必须与 BPMN 的 <process id>、流程模型 key、bpm-server 转发器返回值完全一致，
        // 否则本类对进程内事件永远不命中（跨进程 HTTP 回调不受此影响，因为 URL 是固定的）
        return RequisitionBpmConstants.PROCESS_KEY_REQUISITION_APPROVE;
    }

    /**
     * 接收审批结果事件
     *
     * 两个场景共用本方法：
     * - 单体部署：由基类的 {@code onApplicationEvent} 回调（已做过 key 过滤）；
     * - 微服务部署：作为 {@code POST /rpc-api/buildcloud-material/requisition/update-audit-status}
     *   的处理器，由 bpm-server 的转发监听器调用。
     *
     * 请求体就是基座 {@link BpmProcessInstanceStatusEvent} 的序列化结果
     * （bpm 侧 {@code BpmHttpRequestUtils.executeBpmHttpRequest(event, url, restTemplate)}
     * 直接把 event 当 body POST，字段为 id/processDefinitionKey/status/reason/businessKey），
     * 所以这里**不另建 DTO**——另建就要跟着基座字段变动，且容易写错字段名。
     *
     * @param event 审批结果事件（{@code @RequestBody} 让 Spring MVC 反序列化；
     *              进程内事件调用时该注解不生效，无副作用）
     */
    @Override
    @PostMapping(ApiConstants.PREFIX + RequisitionBpmConstants.CALLBACK_PATH_UPDATE_AUDIT_STATUS)
    protected void onEvent(@RequestBody BpmProcessInstanceStatusEvent event) {
        log.info("[onEvent][收到材料申领审批结果事件：id={}, key={}, status={}, businessKey={}, reason={}]",
                event.getId(), event.getProcessDefinitionKey(), event.getStatus(),
                event.getBusinessKey(), event.getReason());

        // 1. businessKey 缺失或非数字：无法定位业务单据，记日志后正常返回（不抛异常）
        //    抛异常会让 bpm 侧收到 HTTP 非 2xx/业务错误，进而把流程结束事务回滚——
        //    一条脏回调不该把审批中心的正常流程搞坏
        if (StrUtil.isBlank(event.getBusinessKey())) {
            log.warn("[onEvent][事件({}) 缺少 businessKey，忽略]", event.getId());
            return;
        }
        Long requisitionId;
        try {
            requisitionId = Long.valueOf(event.getBusinessKey());
        } catch (NumberFormatException e) {
            log.warn("[onEvent][事件({}) 的 businessKey({}) 不是合法的申领单编号，忽略]",
                    event.getId(), event.getBusinessKey());
            return;
        }

        // 2. 状态分流：只有三种终态需要处理；RUNNING/NOT_START 说明还没到结果节点，直接忽略
        //    三个终态必须**把原始状态值传下去**（3 与 4 在 approval_status 上是有区别的）
        Integer status = event.getStatus();
        if (ObjectUtil.equal(status, BpmProcessInstanceStatusEnum.APPROVE.getStatus())
                || ObjectUtil.equal(status, BpmProcessInstanceStatusEnum.REJECT.getStatus())
                || ObjectUtil.equal(status, BpmProcessInstanceStatusEnum.CANCEL.getStatus())) {
            requisitionApprovalService.handleApprovalResult(requisitionId, event.getId(), status, event.getReason());
            return;
        }
        log.info("[onEvent][事件 status={} 非终态，无需回写业务状态，requisitionId={}]", status, requisitionId);
    }

}
