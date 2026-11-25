# 读码笔记 ④：starter 体系（yudao-framework 清单 + 四服务实际引用 → 自研模块引用方案）

| 项 | 内容 |
| --- | --- |
| 基线 | yudao-cloud `master-jdk17`（只读参考 `D:\project\yudao-cloud-ref`） |
| 读码日期 | 2026-11-25 |
| 依据 | `yudao-framework/` 目录 + `yudao-dependencies/pom.xml` + 四个服务的 pom.xml（实际依赖声明） |

## 1. yudao-framework 全量 starter 清单（18 个）

| starter | 核心内容（pom 实际依赖 / 源码包） |
| --- | --- |
| `yudao-common` | CommonResult/PageResult/全局异常、RpcConstants、common-biz（OAuth2TokenCommonApi 等 7 个 @FeignClient）、工具类（JsonUtils/CollectionUtils） |
| `yudao-spring-boot-starter-web` | spring-web + validation + knife4j(openapi3) + springdoc + jsoup XSS + GlobalExceptionHandler、WebProperties（admin-api/app-api 前缀） |
| `yudao-spring-boot-starter-security` | spring-security + web starter + rpc starter + bizlog-sdk(mzt-biz-log 操作日志)；TokenAuthenticationFilter（login-user 解析）、YudaoWebSecurityConfigurerAdapter、@ss.hasPermission |
| `yudao-spring-boot-starter-rpc` | **纯聚合器**：openfeign + feign-okhttp + loadbalancer（无 Java 代码） |
| `yudao-spring-boot-starter-mybatis` | mybatis-plus(spring-boot3) 3.5.18.1 + mybatis-plus-join + druid 1.2.28 + dynamic-datasource 4.6.0 + easy-trans + 多数据库驱动（mysql/postgres/达梦/人大金仓等） |
| `yudao-spring-boot-starter-redis` | redisson-spring-boot-starter（redisson-spring-data-35）+ spring-cache |
| `yudao-spring-boot-starter-mq` | rocketmq-spring-boot-starter +（可选 kafka/rabbit 依赖，默认关闭） |
| `yudao-spring-boot-starter-job` | xxl-job-core 2.4.0（执行器自动装配） |
| `yudao-spring-boot-starter-monitor` | spring-boot-admin-client + micrometer-prometheus + SkyWalking logback/trace toolkit + opentelemetry |
| `yudao-spring-boot-starter-env` | **服务基座必引**：spring-boot-starter + nacos-discovery + spring-cloud-loadbalancer + 环境工具（EnvUtils） |
| `yudao-spring-boot-starter-excel` | fesod-sheet（EasyExcel 系）导入导出 |
| `yudao-spring-boot-starter-test` | spring-boot-starter-test + h2 + jedis-mock + mockito-inline + podam（单测基建） |
| `yudao-spring-boot-starter-biz-tenant` | 多租户：TenantContextHolder/tenant-id Header、表/SQL 租户隔离 |
| `yudao-spring-boot-starter-biz-data-permission` | 数据权限（依赖 security + mybatis + rpc starter；DeptDataPermissionRespDTO） |
| `yudao-spring-boot-starter-biz-ip` | ip2region 离线 IP 库（区域归属） |
| `yudao-spring-boot-starter-protection` | lock4j-redisson 分布式锁 + 幂等/限流注解（**system/infra/bpm pom 中均被注释掉，未启用**） |
| `yudao-spring-boot-starter-websocket` | spring websocket + mq（infra 的 /infra/ws 用） |
| `yudao-spring-boot-starter-elasticsearch` | easy-es（基座四服务均未引用） |

依赖链要点：`security → web + rpc`；`biz-data-permission → security + mybatis + rpc`。引 security 即自动带 web/rpc 能力。

## 2. 四个服务的 pom 实际引用（读码实证）

### 2.1 yudao-gateway（`yudao-gateway/pom.xml`）

- WebFlux 反应栈：`spring-cloud-starter-gateway-server-webflux`（**不引 web/security starter**）；
- `knife4j-gateway-spring-boot-starter`（文档聚合）、`spring-cloud-starter-loadbalancer`（WebClient 校验 token 用）、nacos discovery/config；
- `yudao-spring-boot-starter-monitor` + skywalking apm-toolkit（webflux/trace）；
- `yudao-module-system-api`（只为拿到 common-biz 的 DTO/常量，exclusion 掉 springdoc-openapi-webmvc-core）；
- guava、caffeine；**无 Sentinel、无 security starter**。

### 2.2 yudao-module-system-server（`pom.xml`）

env / biz-data-permission / biz-tenant / biz-ip / security / mybatis / redis / rpc / job / mq / excel / monitor / test(test scope) + nacos discovery/config + 三方：spring-boot-starter-mail、JustAuth、wx-java（公众号/小程序）、captcha（验证码）、hutool-extra。依赖 `yudao-module-system-api`（自身）+ `yudao-module-infra-api`。

### 2.3 yudao-module-infra-server（`pom.xml`）

env / biz-tenant（**无 biz-data-permission、无 biz-ip**）/ security / **websocket** / mybatis（+ mybatis-plus-generator 代码生成）+ velocity / redis / rpc / job / mq / excel / monitor / test + nacos + 三方：commons-net(ftp)、jsch(sftp)、awssdk-s3(MinIO 等对象存储)、tika。依赖 `yudao-module-infra-api`。

### 2.4 yudao-module-bpm-server（`pom.xml`）

env / biz-data-permission / biz-tenant / security / mybatis / redis / rpc / excel / monitor / **test（非 test scope，编译期即依赖）** + nacos + **flowable-spring-boot-starter-process + flowable-spring-boot-starter-actuator（8.0.0）**。依赖 `yudao-module-bpm-api` + `yudao-module-system-api`。**不引 job/mq starter**（bpm 当前未用 xxl-job/rocketmq；application.yaml 中 xxl-job 段仅留空配置）。

### 2.5 引用矩阵

| starter | gateway | system | infra | bpm | 候选自研 |
| --- | :-: | :-: | :-: | :-: | --- |
| env | ✔ | ✔ | ✔ | ✔ | 全部 |
| web（经 security 传递） | ✖(webflux) | ✔ | ✔ | ✔ | 全部 |
| security | ✖ | ✔ | ✔ | ✔ | 全部 |
| rpc | (loadbalancer 直引) | ✔ | ✔ | ✔ | 全部 |
| mybatis / redis | ✖ | ✔ | ✔ | ✔ | 全部 |
| nacos discovery/config | ✔ | ✔ | ✔ | ✔ | 全部 |
| monitor | ✔ | ✔ | ✔ | ✔ | 全部 |
| biz-tenant | ✖ | ✔ | ✔ | ✔ | 全部（表带 tenant_id） |
| biz-data-permission | ✖ | ✔ | ✖ | ✔ | project（数据权限） |
| job（xxl-job） | ✖ | ✔ | ✔ | ✖ | report（分片导出）、material（对账 Job）、project/material |
| mq（rocketmq） | ✖ | ✔ | ✔ | ✖ | material（事务消息/延迟）、report |
| excel | ✖ | ✔ | ✔ | ✔ | 全部业务模块 |
| websocket | ✖ | ✖ | ✔ | ✖ | 暂无（站内信用基座） |
| biz-ip / protection / es / test | ✖ | ✔ip / ✖protection | ✖ | ✖ | 按需；test 全部(test scope) |

## 3. 自研模块（buildcloud-*）应引用哪些——推荐 pom 模板

对照上表与 spec §3.2/§5 各域需求（W2 建骨架时照此落 pom，groupId `com.buildcloud`，父 pom 版本对齐基座 `${revision}` 线）：

**全部 4 个自研服务必引（照抄 bpm-server 模式）**：

```
yudao-spring-boot-starter-env          → Nacos 注册/环境
yudao-spring-boot-starter-security     → 登录态解析 + 方法鉴权（传递引入 web/rpc）
yudao-spring-boot-starter-mybatis      → MP + Druid + 多数据源
yudao-spring-boot-starter-redis        → Redisson（W5 Lua/布隆/分布式锁的底座）
yudao-spring-boot-starter-biz-tenant   → BaseDO tenant_id 隔离
yudao-spring-boot-starter-excel        → 导入导出
yudao-spring-boot-starter-monitor      → actuator/prometheus
yudao-spring-boot-starter-test (test)  → 单测基建（bpm-server 把它放 compile 是特例，勿抄）
com.alibaba.cloud:nacos-discovery / nacos-config
对应 *-api：yudao-module-system-api（用户/部门/权限/字典/站内信）、yudao-module-bpm-api（审批流）
```

**按域追加**：

| 服务 | 追加 |
| --- | --- |
| buildcloud-module-project | `biz-data-permission`（按部门数据权限，spec §5.1）；调 bpm 用 |
| buildcloud-module-material | `mq`（W5 事务消息/延迟关单）、`job`（对账 Job）、`protection`（lock4j/幂等——注意基座自身未启用，引入属自研决策，需 ADR） |
| buildcloud-module-report | `job`（XXL-Job 分片广播导出，spec §5.4） |
| buildcloud-module-ai | 暂不引基座 ai 模块（D12 自研）；pgvector 用 PostgreSQL 驱动（mybatis starter 已带 postgresql 驱动） |

**明确不引**：websocket（无场景）、elasticsearch（未用）、biz-ip（无场景）。

## 4. 一条请求的完整链路（从 starter 视角）

以「POST /admin-api/material/requisition/create 落到 buildcloud-material」为例，标注每一步由哪个 starter 承载：

```
网关(gateway-server-webflux + 自写 TokenAuthenticationFilter + loadbalancer)
  → login-user Header 透传
Nacos discovery(env starter) 找到 buildcloud-material 实例
  → servlet 栈：
     security starter 的 TokenAuthenticationFilter   ← login-user Header → SecurityContext
     YudaoWebSecurityConfigurerAdapter               ← @PreAuthorize 鉴权（security starter）
     web starter 的 WebProperties/全局异常/WebFilter ← /admin-api 前缀约定、CommonResult 包装
  → controller：@PreAuthorize("@ss.hasPermission('material:requisition:create')")
  → service：biz-tenant starter 维护 TenantContextHolder（tenant_id 自动写入 BaseDO）
  → dal：mybatis starter（MP + Druid + 动态数据源）
  → 返回 CommonResult（common 包）
```

## 5. 版本锚点（yudao-dependencies BOM 实测值，W2 对齐用）

| 组件 | 版本 |
| --- | --- |
| Spring Boot | 3.5.16 |
| Spring Cloud | 2025.0.3 |
| Spring Cloud Alibaba | 2025.0.0.0 |
| Flowable | 8.0.0 |
| MyBatis Plus | 3.5.18.1（join / jsqlparser 配套） |
| Druid | 1.2.28 |
| dynamic-datasource | 4.6.0 |
| Redisson | 4.8.0 |
| XXL-Job | 2.4.0 |
| springdoc / knife4j | 2.8.17 / 4.5.0 |
| 基座 revision | 2026.10-SNAPSHOT |

> 注：spec §3.3 写「Seata(AT)/ShardingSphere 自研引入」——基座 BOM 中未含 seata/shardingsphere 坐标（W5/W6 引入时按 AGENTS.md §6.4 记 ADR 并锁定版本）；RocketMQ 由 `rocketmq-spring-boot-starter`（mq starter）承载。
