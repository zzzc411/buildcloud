package com.buildcloud.project.service.project.listener;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEventListener;
import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import com.buildcloud.project.enums.ApiConstants;
import com.buildcloud.project.enums.project.ProjectBpmConstants;
import com.buildcloud.project.service.project.ProjectApprovalService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 立项审批结果监听器（**双模式**：既是进程内 Spring 事件监听器，又是跨进程 HTTP 回调入口）
 *
 * 完全复刻基座 {@code oa-server} 的 {@code OaLeaveApplyStatusListener}（读码笔记 §3.5 末段）：
 * 同一个类同时承担两种部署形态，一套代码两种用法：
 *
 * | 形态 | 触发路径 | 说明 |
 * | --- | --- | --- |
 * | 单体（同 JVM） | bpm 发布 Spring 事件 → 本类 {@code onApplicationEvent} | 基类按 key 过滤后进 {@code onEvent} |
 * | 微服务（本项目） | bpm-server 的转发监听器 POST 到下面的 @PostMapping → 本类 {@code onEvent} | 基类不参与，直接由 Spring MVC 调用 |
 *
 * 为什么继承而不是组合：基类 {@link BpmProcessInstanceStatusEventListener} 的
 * {@code onApplicationEvent} 是 {@code final} 且已实现「按 processDefinitionKey 过滤」，
 * 继承即可复用该过滤逻辑，避免两处各写一遍 key 判断（写漏就是全量事件乱入）。
 *
 * @author buildcloud
 */
@RestController
@Validated
@Slf4j
public class ProjectApprovalListener extends BpmProcessInstanceStatusEventListener {

    @Resource
    private ProjectApprovalService projectApprovalService;

    @Override
    public String getProcessDefinitionKey() {
        // 必须与 BPMN 的 <process id> 和流程模型的 key 完全一致，
        // 否则本类对进程内事件永远不命中（跨进程 HTTP 回调不受此影响，因为 URL 是固定的）
        return ProjectBpmConstants.PROCESS_KEY_PROJECT_APPROVE;
    }

    /**
     * 接收审批结果事件
     *
     * 两个场景共用本方法：
     * - 单体部署：由基类的 {@code onApplicationEvent} 回调（已做过 key 过滤）；
     * - 微服务部署：作为 {@code POST /rpc-api/buildcloud-project/project/update-audit-status}
     *   的处理器，由 bpm-server 的转发监听器调用。
     *
     * 请求体结构就是基座的 {@link BpmProcessInstanceStatusEvent} 序列化结果
     * （bpm 侧 {@code BpmHttpRequestUtils.executeBpmHttpRequest(event, url, restTemplate)}
     * 直接把 event 对象当 body POST，字段为 id/processDefinitionKey/status/reason/businessKey），
     * 所以这里**不另建 DTO**——另建 DTO 就要跟着基座字段变动，且容易写错字段名。
     *
     * 语义映射（业务侧只关心「结果」，不关心 id 是实例编号）：
     * - {@code APPROVE(2)} → approved=true；
     * - {@code REJECT(3)} / {@code CANCEL(4)} → approved=false（取消同样不通过立项，项目留在筹备）。
     *
     * @param event 审批结果事件（{@code @RequestBody} 让 Spring MVC 反序列化；
     *              进程内事件调用时该注解不生效，无副作用）
     */
    @Override
    @PostMapping(ApiConstants.PREFIX + ProjectBpmConstants.CALLBACK_PATH_UPDATE_AUDIT_STATUS)
    protected void onEvent(@RequestBody BpmProcessInstanceStatusEvent event) {
        log.info("[onEvent][收到立项审批结果事件：id={}, key={}, status={}, businessKey={}, reason={}]",
                event.getId(), event.getProcessDefinitionKey(), event.getStatus(),
                event.getBusinessKey(), event.getReason());

        // 1. businessKey 缺失或非数字：无法定位业务单据，记日志后正常返回（不抛异常）
        //    抛异常会让 bpm 侧收到 HTTP 非 2xx/业务错误，进而把流程结束事务回滚——
        //    一条脏回调不该把审批中心的正常流程搞坏
        if (StrUtil.isBlank(event.getBusinessKey())) {
            log.warn("[onEvent][事件({}) 缺少 businessKey，忽略]", event.getId());
            return;
        }
        Long projectId;
        try {
            projectId = Long.valueOf(event.getBusinessKey());
        } catch (NumberFormatException e) {
            log.warn("[onEvent][事件({}) 的 businessKey({}) 不是合法的项目编号，忽略]",
                    event.getId(), event.getBusinessKey());
            return;
        }

        // 2. 状态分流：只有三种终态需要处理；RUNNING/NOT_START 说明还没到结果节点，直接忽略
        Integer status = event.getStatus();
        if (ObjectUtil.equal(status, BpmProcessInstanceStatusEnum.APPROVE.getStatus())) {
            projectApprovalService.handleApprovalResult(projectId, event.getId(), true, event.getReason());
            return;
        }
        if (ObjectUtil.equal(status, BpmProcessInstanceStatusEnum.REJECT.getStatus())) {
            projectApprovalService.handleApprovalResult(projectId, event.getId(), false, event.getReason());
            return;
        }
        if (ObjectUtil.equal(status, BpmProcessInstanceStatusEnum.CANCEL.getStatus())) {
            // 取消：业务语义等同「不产生立项结果」，项目保持筹备；不调用状态机
            projectApprovalService.handleApprovalResult(projectId, event.getId(), false, event.getReason());
            return;
        }
        log.info("[onEvent][事件 status={} 非终态，无需回写业务状态，projectId={}]", status, projectId);
    }

}
