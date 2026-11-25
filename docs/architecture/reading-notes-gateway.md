# 读码笔记 ①：网关鉴权链路（yudao-gateway）

| 项 | 内容 |
| --- | --- |
| 基线 | yudao-cloud `master-jdk17`（revision 2026.10-SNAPSHOT，Spring Boot 3.5.16 / Spring Cloud 2025.0.3 / Spring Cloud Alibaba 2025.0.0.0） |
| 读码范围 | `D:\project\yudao-cloud-ref\yudao-gateway`（只读） + `yudao-framework/yudao-spring-boot-starter-security`（下游解析侧） |
| 读码日期 | 2026-11-25 |
| 关联 | spec §3.1/§3.2；arch-overview-v0 |

## 1. 模块组成（实际文件清单）

网关模块极简，`yudao-gateway/src/main/java/cn/iocoder/yudao/gateway/` 下共 4 个包：

| 文件 | 职责 |
| --- | --- |
| `GatewayServerApplication.java` | 启动类 |
| `filter/security/TokenAuthenticationFilter.java` | **核心**：Token 校验 GlobalFilter |
| `filter/security/LoginUser.java` | 网关侧登录用户模型（id/userType/info/tenantId/scopes/expiresTime） |
| `util/SecurityFrameworkUtils.java` | Token 提取、login-user Header 写入、内部 Header 清洗 |
| `util/WebFrameworkUtils.java` | tenant-id Header 读写、JSON 响应、客户端 IP、Route 获取 |
| `filter/grey/GrayLoadBalancer.java` + `GrayReactiveLoadBalancerClientFilter.java` | 灰度负载均衡（对应路由 URI 的 `grayLb://` 前缀） |
| `filter/logging/AccessLogFilter.java` + `AccessLog.java` | 访问日志 |
| `filter/cors/CorsFilter.java` | CORS |
| `handler/GlobalExceptionHandler.java` | 全局异常→CommonResult |
| `jackson/GatewayJacksonAutoConfiguration.java`、`util/BannerApplicationRunner.java`、`util/EnvUtils.java`、`util/SkyWalkingTraceUtils.java` | 辅助 |

## 2. Token 校验过滤器（链路核心）

`TokenAuthenticationFilter` 实现 `GlobalFilter, Ordered`，`getOrder()` 返回 **-100**（注释：与 Spring Security 过滤器顺序对齐）。行为要点（全部出自该类源码）：

1. **先清洗伪造头**：`SecurityFrameworkUtils.removeInternalHeaders(exchange)` 移除 `login-user`、`data-permission-enable` 两个内部 Header——外部请求不得伪造（该方法注释明确说明 HttpHeaders key 大小写不敏感，同名变体全部移除）。此步在所有分支之前执行。
2. **无 Token 直接放行**：`SecurityFrameworkUtils.obtainAuthorization` 从 `Authorization: Bearer xxx` 提取；无 Token → `chain.filter(exchange)`。
   - 注释原话：「验证不通过，还是会转发给服务。因为，接口是否需要登录的校验，还是交给服务自身处理」——**网关只做"解析+透传"，不做强制鉴权**。
3. **有 Token → 远程校验**：
   - 本地缓存：Guava `LoadingCache<KeyValue<Long,String>, LoginUser>`，`buildAsyncReloadingCache(Duration.ofMinutes(1), ...)`，key = (tenantId, token)，1 分钟异步刷新；
   - 缓存未命中 → `checkAccessToken(tenantId, token)`：**WebClient**（构造时注入 `ReactorLoadBalancerExchangeFilterFunction` 实现负载均衡）GET `OAuth2TokenCommonApi.URL_CHECK`（= `http://system-server/rpc-api/system/oauth2/token/check`，常量定义在 `yudao-framework/yudao-common/.../biz/system/oauth2/OAuth2TokenCommonApi.java`），并透传 `tenant-id` Header；
   - 不走 Feign 的原因写在构造器注释里：Spring Cloud OpenFeign 官方未内置 Reactive 支持，且不想为 tenant header 写 RequestInterceptor。
4. **结果处理**：`buildUser` 解析 `CommonResult<OAuth2AccessTokenCheckRespDTO>`；错误码 401 时返回 `LOGIN_USER_EMPTY`（避免过期 token 因缓存被误判有效）；成功则构建 LoginUser（userId/userType/userInfo/tenantId/scopes/expiresTime）。
5. **透传下游**：校验成功 → `SecurityFrameworkUtils.setLoginUserHeader(builder, user)`：LoginUser → JSON → **URLEncoder 编码** → 写入 `login-user` Header → 用 mutate 后的新 exchange 继续过滤链。

## 3. 路由配置（application.yaml 实际内容）

`yudao-gateway/src/main/resources/application.yaml`：

- 路由前缀 `spring.cloud.gateway.server.webflux.routes`（SB3.5 的属性层级），每条路由形如：

```yaml
- id: system-admin-api
  uri: grayLb://system-server        # grayLb = 灰度负载均衡 scheme，由 GrayReactiveLoadBalancerClientFilter 处理
  predicates:
    - Path=/admin-api/system/**
  filters:
    - RewritePath=/admin-api/system/v3/api-docs, /v3/api-docs
```

- 与本项目相关的路由：`system-server`（48081）、`infra-server`（48082，含 /admin/** Spring Boot Admin、/infra/ws/** WebSocket、/infra/sse/** SSE）、`bpm-server`（48083）；其余 member/pay/mall 等 15 个模块路由 W2 裁剪时删除，并**照此模式新增 buildcloud-* 4 条**；
- `default-filters: DedupeResponseHeader=Access-Control-Allow-Credentials Access-Control-Allow-Origin`（防 CORS 响应头重复）；
- `x-forwarded.prefix-enabled: false`（避免 Swagger 重复前缀）；
- 网关端口 **48080**；knife4j 聚合文档（`knife4j.gateway.routes` 按 service-name 聚合 /v3/api-docs）。

## 4. 下游服务如何接住 login-user（security starter）

`yudao-framework/yudao-spring-boot-starter-security/src/main/java/cn/iocoder/yudao/framework/security/core/filter/TokenAuthenticationFilter.java`（Servlet 侧 `OncePerRequestFilter`，与网关类同名但职责不同）：

1. **情况一（主路径）**：`buildLoginUserByHeader` —— 读 `login-user` Header → `URLDecoder.decode` → JSON 反序列化为 LoginUser → 校验 userType 与路径前缀（/admin-api、/app-api 由 `WebFrameworkUtils.getLoginUserType(request)` 推断）是否匹配，不匹配抛 AccessDeniedException；
2. **情况二（兜底）**：无 Header 时直接解析 Token 并调 `OAuth2TokenCommonApi.checkAccessToken`（Feign）——注释说明这是满足「Nginx 直接转发到 Spring Cloud 服务」的场景；
3. 成功 → `SecurityFrameworkUtils.setLoginUser(loginUser, request)` 写入 SecurityContextHolder（`TransmittableThreadLocalSecurityContextHolderStrategy` 支持 TTL 线程池传递）。
4. 过滤器装配与放行规则在 `YudaoWebSecurityConfigurerAdapter`（同 starter `config` 包）：`@PermitAll` 注解扫描 + `yudao.security.permit-all-urls` + 各模块 `AuthorizeRequestsCustomizer` Bean + 兜底 `anyRequest().authenticated()`；`addFilterBefore(authenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)`；方法级鉴权用 `@PreAuthorize("@ss.hasPermission('xxx')")`（`@EnableMethodSecurity`）。

## 5. 一条请求的完整链路（追踪）

以「管理端查询用户分页 GET /admin-api/system/user/page」为例，全部类名/路径真实：

```
浏览器 yudao-ui-admin-vue3
  │  Authorization: Bearer {token}，Header: tenant-id
  ▼
① yudao-gateway :48080  GatewayServerApplication
  │  路由匹配 Path=/admin-api/system/** → grayLb://system-server
  │    （GrayReactiveLoadBalancerClientFilter 选实例）
  │  TokenAuthenticationFilter.filter()  [order=-100]
  │    ├─ removeInternalHeaders：剔除伪造 login-user / data-permission-enable
  │    ├─ obtainAuthorization 取 Bearer token；缓存未命中
  │    ├─ WebClient(负载均衡) GET http://system-server/rpc-api/system/oauth2/token/check?accessToken=xxx
  │    │    └─ system-server:48081 OAuth2TokenApiImpl#checkAccessToken（@TenantIgnore）
  │    │         → OAuth2TokenService#checkAccessToken → system_oauth2_access_token 表
  │    └─ setLoginUserHeader：LoginUser JSON(URLEncode) → login-user Header
  ▼
② system-server:48081  yudao-spring-boot-starter-security
  │  TokenAuthenticationFilter#doFilterInternal
  │    └─ buildLoginUserByHeader（login-user Header 反序列化 + userType 校验）
  │       → SecurityFrameworkUtils.setLoginUser → SecurityContextHolder
  │  YudaoWebSecurityConfigurerAdapter#filterChain：permitAll 规则 + anyRequest().authenticated()
  ▼
③ SystemXxxController @PreAuthorize("@ss.hasPermission('system:user:query')")
  │  → Service → Mapper → MySQL
  ▼
④ CommonResult JSON 原路返回（网关 AccessLogFilter 记录访问日志）
```

## 6. 与 spec §3.1 的差异点（读码实证）

| spec 表述 | 读码结论 | 处置 |
| --- | --- | --- |
| 网关「路由/鉴权/Sentinel 限流」 | 基座 gateway pom **无 Sentinel 依赖**（全仓库 BOM 亦无，仅 `script/sentinel/dependencies.xml` 参考文件） | 架构图 v0 标注「限流=基座未内置」；W5 引入 Sentinel 时按 AGENTS.md §6.4 记 ADR |
| 「鉴权」 | 网关只做 token 校验+用户信息透传，**不做权限判断**；权限校验在各服务的 Spring Security（@PreAuthorize）+ RBAC | 已在架构图 v0 链路标注 |

## 7. 对 buildcloud 的落地要点

- 新增 4 个服务只需在 gateway `application.yaml`（或 Nacos `gateway-server-local.yaml`）照抄路由模板：`grayLb://buildcloud-project` + `Path=/admin-api/project/**`；
- 自研服务引入 `yudao-spring-boot-starter-security` 即自动获得 login-user 解析（starter 自动装配），无需重复造过滤器；
- 自研模块的 SecurityConfiguration 照抄基座模式：`AuthorizeRequestsCustomizer` Bean 中 permitAll `/rpc-api/xxx/**`、swagger、actuator（见 system/bpm 的 `framework/security/config/SecurityConfiguration.java`）；
- 网关对自研服务零 Java 代码侵入——只加配置不改代码（符合 R-2 基座只读原则）。
