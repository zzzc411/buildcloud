# ADR-002: 本地 Nacos 命名空间改用 public（dev 不可用时）

- 日期：2026-11-25
- 状态：接受（**临时方案**，W2 再评估是否改回 dev）
- 关联：plan-w1-infra-up Phase 2 / spec §3.3 / deploy-local.md §6.3

## 背景

基座各服务 `application-*.yaml` 默认 `spring.cloud.nacos.discovery/config.namespace: dev`。当前 Nacos 3.0.3 容器配置：`NACOS_AUTH_ENABLE=false`，但 `nacos.core.auth.console.enabled=true`、`nacos.core.auth.admin.enabled=true` 且无初始用户——v3 控制台与 `/v3/admin/core/namespace/*`、`/v3/console/...` 全部 403，v1/v2 API 已移除（410），**无法通过 API 自动创建 `dev` 命名空间**；官方 quick-start 要求手工在控制台建 dev。

## 决策

W1 本地验证阶段，参考克隆 4 份 `application-local.yaml` 的 `namespace` 改为 `public`，服务注册/配置拉取正常（`nacos:xxx-local.yaml is empty` 为 optional 配置未发布的正常 WARN）。

## 理由与替代方案

- 不改 `docker/middleware.yaml` 的 Nacos 参数（R-4：改 Nacos 公共配置须人批），保持中间件编排可复现；
- 替代方案（留待 W2 决策）：① 给 nacos 挂 `application.properties` 或用 env 关闭 console/admin 鉴权后建 dev；② 保留 public（简单）；③ 首次手工登录控制台初始化管理员再建 dev。

## 影响

- W2 配置模板化时统一命名空间选择（若改回 dev，需按本 ADR 升级 + 改 compose + 重新验证注册）；
- 不影响业务代码、网关路由与本地登录演示。
