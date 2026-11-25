# ADR-001: 服务间 RPC 方式定稿为 Spring Cloud OpenFeign（沿用基座默认）

- 日期：2026-11-25
- 状态：接受
- 关联：spec §3.1（「服务间 RPC（方式以基座默认为准，W1 定稿）」）/ plan-w1-infra-up Phase 3 / reading-notes-rpc.md / arch-overview-v0

## 背景

spec §3.1 架构图中「服务间 RPC 方式以基座默认为准，W1 定稿」。W1 Phase 3 对基座 `master-jdk17` 全量读码（`D:\project\yudao-cloud-ref` 只读）确认了基座的默认 RPC 实现：

- `yudao-framework/yudao-spring-boot-starter-rpc` 是纯依赖聚合器（spring-cloud-starter-openfeign + feign-okhttp + spring-cloud-starter-loadbalancer，无 Java 代码）；
- 接口声明在各模块 `*-api` 子模块：`@FeignClient(name = ApiConstants.NAME)` + Spring MVC 注解 + `CommonResult` 返回（如 `AdminUserApi`、`BpmProcessInstanceApi`）；
- 服务方以 `@RestController implements XxxApi` 提供实现（如 system-server 的 16 个 ApiImpl）；
- 消费方在自己的 `RpcConfiguration` 用 `@EnableFeignClients(clients = {...})` 显式登记后 `@Resource` 注入；
- 全仓库无 Dubbo/gRPC 坐标（BOM 核实），且**无任何 Feign fallback 实现**（`AdminUserApi` 注释为 `TODO 芋艿：fallbackFactory =`）。

约束：R-2 基座只读不改包名；AGENTS.md §6.4 依赖保守——自研引入新 RPC 框架需要额外 ADR 且破坏「基座能力复用」原则；D4 高并发链路需要的是 RocketMQ 异步解耦而非同步 RPC 升级。

## 决策

**buildcloud 全部服务间同步调用沿用基座默认：Spring Cloud OpenFeign**。自研模块按基座模式拆 `buildcloud-module-x-api`（@FeignClient 接口 + DTO + ApiConstants）与 `buildcloud-module-x-server`（@RestController 实现），RPC 路径统一 `/rpc-api/<module>/**`；异步链路（库存扣减、审批结果广播等）按 spec §6.1 用 RocketMQ，不用 RPC 承载。

## 理由与替代方案

**为何选基座默认 OpenFeign：**
1. 零新增依赖——starter-rpc 已聚合 openfeign/okhttp/loadbalancer，引 security starter 即传递可用；
2. 与基座模式完全一致：一套「api 子模块 + @RestController 实现 + CommonResult」风格贯通基座与自研，代码生成器与团队心智零切换；
3. 读码确认基座自身的跨服务协作全部走此模式（bpm→system 的用户查询、gateway→system 的 token 校验、bpm→oa 的审批回调），可整体复用 LoginUser/tenant-id 透传机制（LoginUserRequestInterceptor、WebFrameworkUtils）。

**替代方案与放弃原因：**
| 方案 | 放弃原因 |
| --- | --- |
| Dubbo 3（Triple/gRPC） | 基座 BOM 无坐标，引入需同时改基座依赖体系（违背 R-2/依赖保守）；收益（性能/治理）在本项目规模（8 服务、演示级 QPS）不成立 |
| 直接 HTTP + RestTemplate 手写 | bpm→oa 回调确有此用法，但仅限「事件广播」场景；常规调用放弃它是因为失去接口契约共享、Swagger 文档与类型安全 |
| Spring Cloud Gateway 统一转发替代服务间直连 | 读码确认基座服务间是 Nacos 直连（不经网关），经网关会引入多余一跳且破坏基座默认行为 |

**已知风险（记录在案，不阻塞决策）：** 基座 Feign 无 fallbackFactory——调用失败会直接抛异常。自研强依赖调用（如发起审批）须显式处理失败；是否补 fallback/超时/重试策略属新决策，届时另立 ADR。

## 影响

1. **代码结构（W2 骨架）**：4 个自研模块各拆 `-api` / `-server` 子模块；`ApiConstants`（NAME/PREFIX=RpcConstants.RPC_API_PREFIX + 模块名）；跨服务调用登记进各模块 `RpcConfiguration` 的 `@EnableFeignClients`；
2. **依赖清单**：自研服务统一引用 `yudao-spring-boot-starter-rpc`（经 security starter 传递已含）；调用 system/bpm 能力需引 `yudao-module-system-api` / `yudao-module-bpm-api`（见 reading-notes-starter.md §3）；
3. **文档**：spec §3.1 的「W1 定稿」占位由此 ADR 关闭；架构图 v0 中 RPC 边标注为 OpenFeign；
4. **测试**：W4 起核心 RPC 调用方（申领发起审批、审批回调）需有 mock Feign 的单测（starter-test 基建）；
5. **无排期影响**：不新增工作包，W2 建骨架时按本模式落地。
