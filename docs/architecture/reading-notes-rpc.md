# 读码笔记 ②：服务间 RPC 方式（基座默认 = Spring Cloud OpenFeign）

| 项 | 内容 |
| --- | --- |
| 基线 | yudao-cloud `master-jdk17`（只读参考 `D:\project\yudao-cloud-ref`） |
| 读码日期 | 2026-11-25 |
| 结论去向 | ADR-001（docs/decisions/001-rpc-mode.md），定稿 spec §3.1 的「RPC 方式以基座默认为准」 |

## 1. 结论一句话

**基座默认 RPC = Spring Cloud OpenFeign 同步 HTTP 调用**（负载均衡用 Spring Cloud LoadBalancer，底层 okhttp），接口声明在各模块的 `*-api` 子模块（`@FeignClient` + Spring MVC 注解 + `CommonResult` 返回），服务方用 `@RestController` 实现同一接口；**无 Dubbo/gRPC，全仓库无任何 Feign fallback 实现**。

## 2. 证据链（真实类名/路径）

### 2.1 RPC starter 是什么

`yudao-framework/yudao-spring-boot-starter-rpc`：**纯依赖聚合器**（源码只有 3 个 package-info.java），pom 引入：

- `org.springframework.cloud:spring-cloud-starter-openfeign`
- `io.github.openfeign:feign-okhttp`
- `org.springframework.cloud:spring-cloud-starter-loadbalancer`

package-info 原话：「OpenFeign：提供 RESTful API 的调用」。

### 2.2 接口声明模式（消费方看到的）

以 `yudao-module-system-api/.../api/user/AdminUserApi.java` 为例：

```java
@FeignClient(name = ApiConstants.NAME)   // name = "system-server"，即 spring.application.name
@Tag(name = "RPC 服务 - 管理员用户")
@AutoTrans(namespace = PREFIX, fields = {"nickname"})   // easy-trans 缓存翻译
public interface AdminUserApi {

    String PREFIX = ApiConstants.PREFIX + "/user";       // = "/rpc-api/system/user"

    @GetMapping(PREFIX + "/get")
    CommonResult<AdminUserRespDTO> getUser(@RequestParam("id") Long id);
    // ...default 方法 getUserMap/validateUser 在消费端拼装，减少 RPC 次数
}
```

关键约定：

- `ApiConstants`（各 api 模块 `enums` 包）：`NAME = "system-server"`（Nacos 服务名）、`PREFIX = RpcConstants.RPC_API_PREFIX + "/system"`（`RpcConstants.RPC_API_PREFIX = "/rpc-api"`，位于 `yudao-framework/yudao-common/.../common/enums/RpcConstants.java`）；
- 返回值统一 `CommonResult<T>`，消费端用 `.getCheckedData()` / `.checkError()` 解包；
- 接口上**挂 Swagger 注解**（@Tag/@Operation）——同一接口既是 Feign 客户端又是 OpenAPI 文档；
- `AdminUserApi` 注释原文：`// TODO 芋艿：fallbackFactory =` ——**降级工厂未实现**；
- 全仓库 grep `fallback\s*=` 仅 1 处命中且在 iot 模块测试代码——**业务上零 fallback**，调用失败直接抛异常。

### 2.3 服务方实现模式

以 `yudao-module-system-server/.../api/oauth2/OAuth2TokenApiImpl.java` 为例：

```java
@RestController // 提供 RESTful API 接口，给 Feign 调用
public class OAuth2TokenApiImpl implements OAuth2TokenCommonApi {
    @Override
    @TenantIgnore
    public CommonResult<OAuth2AccessTokenCheckRespDTO> checkAccessToken(String accessToken) { ... }
}
```

system-server 的 `api/` 包下共 16 个 ApiImpl（DeptApiImpl、AdminUserApiImpl、PermissionApiImpl、OAuth2TokenApiImpl 等），全部是 `@RestController implements XxxApi`——**服务方与消费方共享同一接口类**，URL 由接口上的 MVC 注解决定（`/rpc-api/**`），不经网关、服务间直连（Nacos 发现 + LoadBalancer 选实例）。

### 2.4 消费端装配模式

每个 server 模块自己写 `RpcConfiguration`，显式列出 Feign clients。以 `yudao-module-bpm-server/.../framework/rpc/config/RpcConfiguration.java`：

```java
@Configuration(value = "bpmRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {RoleApi.class, DeptApi.class, PostApi.class, AdminUserApi.class,
        SmsSendApi.class, DictDataApi.class, PermissionApi.class})
public class RpcConfiguration { ... }
```

使用处直接 `@Resource private AdminUserApi adminUserApi;`（如 `BpmProcessInstanceServiceImpl` 第 116-118 行注入 AdminUserApi、DeptApi）。

### 2.5 common-biz：给框架层用的 RPC 接口

`yudao-framework/yudao-common/src/main/java/cn/iocoder/yudao/framework/common/biz/` 下另有 7 个 `*CommonApi`（全部 `@FeignClient`）：

| 接口 | 用途 | 典型调用方 |
| --- | --- | --- |
| `OAuth2TokenCommonApi` | token 校验/创建/移除/刷新 | 网关 TokenAuthenticationFilter（WebClient 直拼 URL_CHECK）、下游 TokenAuthenticationFilter |
| `DictDataCommonApi` / `OperateLogCommonApi` / `PermissionCommonApi` / `TenantCommonApi` | 字典/操作日志/数据权限/租户 | 各 starter |
| `ApiAccessLogCommonApi` / `ApiErrorLogCommonApi` | API 访问/错误日志 | web starter |

放在 yudao-common 而非 module-api 的原因：starter（如 security）不能反向依赖业务模块 api，RpcConstants 注释原文：「虽然放在 yudao-spring-boot-starter-rpc 会相对合适，但是每个 API 模块需要使用到，所以暂时只好放在此处」。

## 3. 用户上下文与租户的 RPC 透传

- **LoginUser 透传**：`yudao-spring-boot-starter-security/.../core/rpc/LoginUserRequestInterceptor.java`（`implements feign.RequestInterceptor`）：每次 Feign 调用前，取当前 SecurityContext 的 LoginUser → JSON + URLEncode → 写 `login-user` Header。被调服务的 TokenAuthenticationFilter（buildLoginUserByHeader 分支）直接接住——**用户身份沿 RPC 链路逐跳透传，被调服务无需再校验 token**；
- **租户透传**：`tenant-id` Header（`WebFrameworkUtils.HEADER_TENANT_ID`），由 tenant starter 的上下文传递（BpmHttpRequestUtils 源码可见手工补 tenant-id Header 的同样模式）；
- **数据权限开关透传**：`data-permission-enable` Header（`SecurityFrameworkUtils` 注释：仅允许服务间 RPC 传递，网关层会被 removeInternalHeaders 剔除外部伪造）。

## 4. 一条请求的完整链路（跨服务 RPC）

以「bpm-server 发起流程时查询发起人昵称」（`BpmProcessInstanceServiceImpl#generateProcessInstanceName` → `adminUserApi.getUser(userId)`）为例：

```
bpm-server:48083  BpmProcessInstanceServiceImpl#generateProcessInstanceName
  │  adminUserApi.getUser(userId)                ← @Resource 注入的 Feign 代理
  ▼
LoginUserRequestInterceptor.apply()
  │  当前 LoginUser → login-user Header（用户身份继续透传）
  ▼
Feign(OkHttp) + Spring Cloud LoadBalancer（Nacos 服务发现 system-server）
  │  GET http://{system-server实例}:48081/rpc-api/system/user/get?id=1
  ▼
system-server:48081  AdminUserApiImpl#getUser（@RestController implements AdminUserApi）
  │  → AdminUserService → system_users 表
  ▼
CommonResult<AdminUserRespDTO> 返回
  │  bpm-server 侧 .getCheckedData() 解包；失败直接抛（无 fallback）
  ▼
StrUtil.format(title, ...) 完成流程实例命名
```

## 5. 可选旁路：跨服务流程回调走「负载均衡 RestTemplate」而非 Feign

BPM 的跨服务状态回调（详见 reading-notes-bpm-integration.md §5）用 `RestTemplate`（`loadBalancedRestTemplate` Bean）POST `http://oa-server/rpc-api/oa/leave-apply/update-audit-status`——同样是 Nacos + 负载均衡的 HTTP RPC，只是没走 Feign 接口声明（因为 BPM 不知道业务方的接口类型，只有统一的 `BpmProcessInstanceStatusEvent` JSON 体）。**这不构成第二种 RPC 体系**，本质仍是 HTTP + 服务名寻址。

## 6. 对 buildcloud 的落地要点（W2 骨架直接照抄）

1. 每个自研模块拆 `buildcloud-module-x-api`（纯接口 + DTO + ApiConstants）与 `buildcloud-module-x-server` 两个子模块，groupId `com.buildcloud`（D16）；
2. `ApiConstants`：`NAME = "buildcloud-project"` 等服务名 + `PREFIX = "/rpc-api/project"` 等，模式照抄 `system` 的 ApiConstants；
3. 需要别人调用的能力 → 定义 `XxxApi`（@FeignClient(name=自己的服务名)），本服务 `@RestController implements`；
4. 调用别人（system 的用户/部门/权限、bpm 的流程）→ pom 引对应 `yudao-module-system-api` / `yudao-module-bpm-api`，并在本模块 `RpcConfiguration` 的 `@EnableFeignClients(clients = {...})` 里登记；
5. 高并发链路（W5）禁止在扣减事务内嵌套慢 RPC；Feign 无 fallback 的现状要求：强依赖调用的失败必须显式 try/catch 或设计为可重试，不得假设有静默降级；
6. 降级/容错策略（fallbackFactory、超时重试）属于新决策，将来按 AGENTS.md §6.4 走 ADR，不在本 Phase 范围。
