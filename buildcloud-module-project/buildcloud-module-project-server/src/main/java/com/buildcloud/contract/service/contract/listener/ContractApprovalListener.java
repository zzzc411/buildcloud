package com.buildcloud.contract.service.contract.listener;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEvent;
import cn.iocoder.yudao.module.bpm.api.event.BpmProcessInstanceStatusEventListener;
import cn.iocoder.yudao.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import com.buildcloud.contract.enums.contract.ContractBpmConstants;
import com.buildcloud.contract.service.contract.ContractApprovalService;
import com.buildcloud.project.enums.ApiConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进度款审批结果监听器（**双模式**：既是进程内 Spring 事件监听器，又是跨进程 HTTP 回调入口）
 *
 * 完全复刻项目立项审批的 {@code ProjectApprovalListener}（W3-P4）与基座
 * {@code oa-server} 的 {@code OaLeaveApplyStatusListener}（读码笔记 §3.5 末段）：
 * 同一个类同时承担两种部署形态，一套代码两种用法：
 *
 * | 形态 | 触发路径 | 说明 |
 * | --- | --- | --- |
 * | 单体（同 JVM） | bpm 发布 Spring 事件 → 本类 {@code onApplicationEvent} | 基类按 key 过滤后进 {@code onEvent} |
 * | 微服务（本项目） | bpm-server 的 {@code PaymentApproveStatusListener} POST 到下面的 @PostMapping → 本类 {@code onEvent} | 基类不参与，由 Spring MVC 直接调用 |
 *
 * 为什么继承而不是组合：基类 {@link BpmProcessInstanceStatusEventListener} 的
 * {@code onApplicationEvent} 是 {@code final} 且已实现「按 processDefinitionKey 过滤」，
 * 继承即可复用该过滤逻辑，避免两处各写一遍 key 判断（写漏就是全量事件乱入）。
 *
 * 回调 URL 的路径段必须与 bpm-server 侧 {@code PaymentApproveStatusListener.CALLBACK_URL} 的尾段
 * 以及 {@link ContractBpmConstants#CALLBACK_PATH_UPDATE_AUDIT_STATUS} 三处一致
 * （/rpc-api/buildcloud-project/contract/update-audit-status），否则转发 404、业务状态静默不动
 * （lessons-learned L2-12 的典型现象）。
 *
 * @author buildcloud
 */
@RestController
@Validated
@Slf4j
public class ContractApprovalListener extends BpmProcessInstanceStatusEventListener {

    @Resource
    private ContractApprovalService contractApprovalService;

    @Override
    public String getProcessDefinitionKey() {
        // 必须与 BPMN 的 <process id>、流程模型 key、bpm-server 转发器返回值完全一致，
        // 否则本类对进程内事件永远不命中（跨进程 HTTP 回调不受此影响，因为 URL 是固定的）
        return ContractBpmConstants.PROCESS_KEY_PAYMENT_APPROVE;
    }

    /**
     * 接收审批结果事件
     *
     * 两个场景共用本方法：
     * - 单体部署：由基类的 {@code onApplicationEvent} 回调（已做过 key 过滤）；
     * - 微服务部署：作为 {@code POST /rpc-api/buildcloud-project/contract/update-audit-status}
     *   的处理器，由 bpm-server 的转发监听器调用。
     *
     * 请求体结构就是基座的 {@link BpmProcessInstanceStatusEvent} 序列化结果
     * （bpm 侧 {@code BpmHttpRequestUtils.executeBpmHttpRequest(event, url, restTemplate)}
     * 直接把 event 对象当 body POST，字段为 id/processDefinitionKey/status/reason/businessKey），
     * 所以这里**不另建 DTO**——另建 DTO 就要跟着基座字段变动，且容易写错字段名。
     *
     * 语义映射（业务侧只关心「结果」）：
     * - {@code APPROVE(2)} → 生成流水 + 累加累计收付 + 合同转履行中；
     * - {@code REJECT(3)} / {@code CANCEL(4)} → 不生成流水，合同退回（详见
     *   {@code ContractApprovalServiceImpl#handleRejected}）。
     * 三者都必须把**原始状态值**传下去，因为 approval_status 要落对应值（3 与 4 有区别）。
     *
     * @param event 审批结果事件（{@code @RequestBody} 让 Spring MVC 反序列化；
     *              进程内事件调用时该注解不生效，无副作用）
     */
    @Override
    @PostMapping(ApiConstants.PREFIX + ContractBpmConstants.CALLBACK_PATH_UPDATE_AUDIT_STATUS)
    protected void onEvent(@RequestBody BpmProcessInstanceStatusEvent event) {
        log.info("[onEvent][收到进度款审批结果事件：id={}, key={}, status={}, businessKey={}, reason={}]",
                event.getId(), event.getProcessDefinitionKey(), event.getStatus(),
                event.getBusinessKey(), event.getReason());

        // 1. businessKey 缺失或非数字：无法定位业务单据，记日志后正常返回（不抛异常）
        //    抛异常会让 bpm 侧收到 HTTP 非 2xx/业务错误，进而把流程结束事务回滚——
        //    一条脏回调不该把审批中心的正常流程搞坏
        if (StrUtil.isBlank(event.getBusinessKey())) {
            log.warn("[onEvent][事件({}) 缺少 businessKey，忽略]", event.getId());
            return;
        }
        Long contractId;
        try {
            contractId = Long.valueOf(event.getBusinessKey());
        } catch (NumberFormatException e) {
            log.warn("[onEvent][事件({}) 的 businessKey({}) 不是合法的合同编号，忽略]",
                    event.getId(), event.getBusinessKey());
            return;
        }

        // 2. 状态分流：只有三种终态需要处理；RUNNING/NOT_START 说明还没到结果节点，直接忽略
        Integer status = event.getStatus();
        if (ObjectUtil.equal(status, BpmProcessInstanceStatusEnum.APPROVE.getStatus())
                || ObjectUtil.equal(status, BpmProcessInstanceStatusEnum.REJECT.getStatus())
                || ObjectUtil.equal(status, BpmProcessInstanceStatusEnum.CANCEL.getStatus())) {
            contractApprovalService.handleApprovalResult(contractId, event.getId(), status, event.getReason());
            return;
        }
        log.info("[onEvent][事件 status={} 非终态，无需回写业务状态，contractId={}]", status, contractId);
    }

}
