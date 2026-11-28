package cn.iocoder.yudao.module.bpm.api.event;

import cn.iocoder.yudao.module.bpm.framework.flowable.core.util.BpmHttpRequestUtils;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.client.RestTemplate;

/**
 * 材料申领审批结果的 HTTP 转发监听器
 *
 * 【本项目改动说明 — 非基座原生类】
 * 本文件由筑云 buildcloud 在基座 master-jdk17 上**新增**，用于把 requisition-approve 流程的
 * 审批结果从 bpm-server 转发到独立的 buildcloud-material-server 进程。
 * 结构完全照抄基座同类 {@code OaLeaveApplyStatusListener} 与本项目的
 * {@link ProjectApproveStatusListener}、{@link PaymentApproveStatusListener}，
 * 只替换流程定义 key 与回调 URL；不修改基座任何既有代码，理由与影响面见
 * {@code docs/decisions/003-bpm-callback-forwarder.md}（AGENTS.md R-2 要求，
 * 该 ADR「影响」第 2 条已预先登记「W4/W5 各需再加一处」，本条即 W4 的 requisition-approve）。
 *
 * 背景：审批结果事件 {@code BpmProcessInstanceStatusEvent} 是 Spring ApplicationEvent，
 * 只在发布它的 JVM（bpm-server）内传播；buildcloud-material-server 是另一个进程，
 * 必须由本 JVM 内的监听器主动 HTTP 转发过去（读码笔记
 * 《reading-notes-bpm-integration.md》§3.5 / §5.2，经验 lessons-learned L2-12：
 * 基座对每条业务流程都要一个转发器，没写过就是链路**静默**断开）。
 *
 * 生效条件：在 {@code framework/rpc/config/RpcConfiguration} 中注册为 Bean，
 * 且业务模块自己若已声明同名 Bean（单体部署时）则本转发器不生效
 * （{@code @ConditionalOnMissingBean}，与基座对 OA/CRM 的处理一致）。
 *
 * @author buildcloud
 */
public class RequisitionApproveStatusListener extends BpmProcessInstanceStatusEventListener {

    /**
     * 目标服务的回调地址
     *
     * 组成说明（改任何一段都要同步改 buildcloud-material-server 侧）：
     * - {@code http://buildcloud-material-server} —— 服务名即 spring.application.name，
     *   由 loadBalancedRestTemplate + Nacos 服务发现解析为实际 IP:端口；
     * - {@code /rpc-api/buildcloud-material} —— 基座 RPC 统一前缀（RpcConstants.RPC_API_PREFIX）
     *   加业务模块标识，与 material 模块 ApiConstants.PREFIX 一致；
     * - {@code /requisition/update-audit-status} —— 回调接口路径，
     *   见 RequisitionApprovalListener 的 @PostMapping
     *   （与 RequisitionBpmConstants.CALLBACK_PATH_UPDATE_AUDIT_STATUS 一致）。
     */
    private static final String CALLBACK_URL =
            "http://buildcloud-material-server/rpc-api/buildcloud-material/requisition/update-audit-status";

    @Resource
    private RestTemplate loadBalancedRestTemplate;

    @Override
    public String getProcessDefinitionKey() {
        // 必须与 buildcloud 的 BPMN 流程定义 key（<process id="requisition-approve">）完全一致，
        // 否则本监听器永远不命中，业务侧收不到任何回调
        return "requisition-approve";
    }

    @Override
    public void onEvent(@RequestBody @Valid BpmProcessInstanceStatusEvent event) {
        BpmHttpRequestUtils.executeBpmHttpRequest(event, CALLBACK_URL, loadBalancedRestTemplate);
    }

}
