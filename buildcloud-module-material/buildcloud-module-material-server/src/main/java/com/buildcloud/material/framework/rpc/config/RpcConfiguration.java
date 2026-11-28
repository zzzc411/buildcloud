package com.buildcloud.material.framework.rpc.config;

import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * material 模块的 RPC 配置（W4-P5 新增；W5-P4 追加站内信客户端）
 *
 * 与 buildcloud-project-server 的 {@code projectRpcConfiguration} 同构：
 * {@code @EnableFeignClients} 必须**显式列出**要用的 Feign 客户端接口，
 * 否则 {@link BpmProcessInstanceApi} 只是依赖包里的普通 interface，不会被注册成代理 Bean，
 * 注入时报 {@code A component required a bean of type 'BpmProcessInstanceApi' that could not be found}
 * ——这正是本类存在的原因（**W4-P5 实证**：加了 yudao-module-bpm-api 依赖还不够，
 * 启动即失败；见 lessons-learned L2-15）。
 *
 * 本模块调用的基座 RPC：
 * 1. {@link BpmProcessInstanceApi}（bpm-server，/rpc-api/bpm/process-instance）——发起申领审批流程实例。
 *    审批结果的回流**不走 Feign**，而是由 bpm-server 的转发器主动 POST 到本服务的 /rpc-api 回调接口
 *    （见 {@code RequisitionApprovalListener}，ADR-003）。
 * 2. {@link NotifyMessageSendApi}（system-server，/rpc-api/system/notify/send）——W5-P4 对账差异的站内信告警。
 *    它与第 1 条一样属于「加了 yudao-module-system-api 依赖还不够」的情形（L2-15 同款），
 *    故必须在此显式声明；不显式声明时启动报
 *    {@code required a bean of type 'NotifyMessageSendApi' that could not be found}。
 *    调用方 {@code ReconcileAlertService} 对它的失败**一律降级**为 error 日志（不发不等于账有问题）。
 *
 * bean 名显式取 {@code materialRpcConfiguration}：与 project 模块的 {@code projectRpcConfiguration} 区分，
 * 将来若做单体聚合部署（两个模块同 JVM）也不会撞名。
 *
 * @author buildcloud
 */
@Configuration(value = "materialRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {BpmProcessInstanceApi.class, NotifyMessageSendApi.class})
public class RpcConfiguration {
}
