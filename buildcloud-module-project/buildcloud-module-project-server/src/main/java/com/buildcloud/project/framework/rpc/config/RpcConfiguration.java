package com.buildcloud.project.framework.rpc.config;

import cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

/**
 * project 模块的 RPC 配置
 *
 * 与基座 oa-server 的 {@code oaRpcConfiguration} 同构（读码笔记 §5.1 复刻清单）：
 * {@code @EnableFeignClients} 必须**显式列出**要用的 Feign 客户端接口，
 * 否则该接口只是被依赖包里的普通 interface，不会被注册为代理 Bean，注入时报找不到 bean。
 *
 * 本模块目前只调用一个基座 RPC：
 * {@link BpmProcessInstanceApi}（bpm-server，/rpc-api/bpm/process-instance）——发起与撤回流程实例。
 * 审批结果的回流不走 Feign，而是由 bpm-server 主动 POST 到本服务的 /rpc-api 回调接口
 * （见 ProjectApprovalListener）。
 *
 * @author buildcloud
 */
@Configuration(value = "projectRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {BpmProcessInstanceApi.class})
public class RpcConfiguration {
}
