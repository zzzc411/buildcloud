# 实战经验与踩坑记录（lessons-learned）

| 项 | 内容 |
| --- | --- |
| 用途 | 沉淀 W0-W8 开发过程中**真实踩到的坑**与验证过的做法，供后续 Phase、子智能体派发上下文包、面试复盘复用 |
| 版本 | v1.2（覆盖 W0-W4，2026-11-28；40 条） |
| 迭代方式 | **追加式**：每个 Phase 收尾时追加条目，不删旧条目（已失效的标记「已失效」并注明原因） |
| 定位 | 不是教程，是「本项目在这台机器、这套基座上，什么会真的坏」的证据清单 |

> 与 AGENTS.md 的分工：AGENTS 是**必须遵守的红线**（结果导向），本文是**为什么红线长这样**（过程证据）。派发 worker 时把对应条目贴进上下文包，比只说「注意兼容性」有效得多。

---

## 一、环境类（Windows / PowerShell / JDK / Docker）

### L1-1 PowerShell 调用 Maven：`-D` 参数必须加引号

**现象**：`mvn -DskipTests -Dmaven.test.skip=true package` 报
`Unknown lifecycle phase ".test.skip=true"` / `LifecyclePhaseNotFoundException`。

**原因**：PowerShell 把 `-Dmaven.test.skip=true` 拆成两个 token。

**修复（分两种调用姿势，别混用）**：
- **PowerShell 直接调用**：`mvn -B -ntp '-DskipTests' '-Dmaven.test.skip=true' package`（必须加引号）；
- **`cmd /c "..."` 包裹**：`cmd /c "mvn -B -ntp -DskipTests -Dmaven.test.skip=true package"`（**外层双引号已足够，内层再加单引号会被 cmd 原样传给 Maven，报 `Unknown lifecycle phase "'-DskipTests'"`**）。

**影响面**：所有构建脚本、worker 派发指令都要写明调用姿势。已写入 AGENTS §6.7。
（W3-P1 实证：两种姿势混用会互相抵消，是高频误踩点。）

### L1-1b PowerShell 不支持 `<` 输入重定向

**现象**：`docker exec ... mysql ... < file.sql` 报 `RedirectionNotSupported`。
**修复**：`docker cp file.sql bc-mysql:/tmp/x.sql` + `docker exec ... sh -c 'mysql ... < /tmp/x.sql'`（重定向放进容器内 shell）。
**影响面**：所有「导入 SQL 到容器」的操作。已用于 W2/W3 的建表与菜单导入。

### L1-1c 运行中的 jar 会阻塞 `install` 的 repackage

**现象**：`install` 在 `spring-boot:repackage` 阶段失败 `Unable to rename ...jar to ...jar.original`。
**原因**：另一个进程正持有该 jar 文件句柄（服务还在跑）。
**修复**：**先停自己的服务**再打包。**不要为了打包去 kill 别人的/生产进程**——先问。

> ⚠️ **本条曾被错误地建议为「临时加 `-Dspring-boot.repackage.skip=true`」——该做法有害，见 L1-1f。**
> **正解**：停服务 → 完整打包（不带 skip）→ 重启验证。

### L1-1f `-Dspring-boot.repackage.skip=true` 会静默损坏运行中的服务（比直接失败更危险）

**现象**：服务进程还活着、端口还监听，但**所有请求超时**；`logs/*.err.log` 出现 `NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`；**jar 从 ~150MB 变成 156KB**。
**根因**：`repackage.skip` 只跳过 `repackage` 阶段，**不跳 `jar:jar`**。`jar:jar` 会把 `target/classes` 打成 **thin jar** 并**覆盖同名文件**。运行中的 JVM 已加载的类还在内存里，但**懒加载的类**（如只在异常日志路径才用的 `ThrowableProxy`）之后去 classpath 找已经找不到——所以不是启动即崩，而是**跑了很久后某条冷路径才炸**。
**修复三步**：① 停自己的服务；② 不带 skip 重打 fat jar（确认 150MB 左右）；③ 重启并 `curl health` 验证。
**影响面**：所有「一边跑服务一边 install」的联调。**这条修正了 L1-1c 的旧表述**——skip 不是「安全地只编译」，是「静默把 fat jar 换成 thin jar」。

### L1-1g MyBatis 注解 SQL 传 null 时间参数必须声明 `jdbcType`

**现象**：`updateStatusCas(..., timeoutTime=null)` 报 `JdbcType OTHER` 无法绑定。
**修复**：`COALESCE(#{timeoutTime,jdbcType=TIMESTAMP}, timeout_time)`。
**影响面**：所有「注解 SQL + 可空参数」。H2 下必现，MySQL 下偶发。

### L1-1d PowerShell 5.1 的 `Invoke-RestMethod` 会把中文 body 转成 `?`

**现象**：`Invoke-RestMethod -Body '{"name":"滨江商务中心"}'` 落库变 `????????`（HEX `3F3F…`）。
**原因**：PS 5.1 默认按 ISO-8859-1 编码 body，中文字符被替换。
**修复**：显式 UTF-8 字节体——
```powershell
$bytes = [Text.Encoding]::UTF8.GetBytes($json)
Invoke-RestMethod -Uri $u -Method Post -Headers @{'tenant-id'='1';'Content-Type'='application/json; charset=utf-8'} -Body $bytes
```
或用 `curl.exe --data-binary "@file.json"`（文件以 UTF-8 无 BOM 保存）。
**判别**：**这是客户端问题，不是服务端编码缺陷**——同一数据用 curl 提交时 `HEX()` 正常。W3-P2/P3 联调均踩到。

### L1-1e 多子域模块的 `@MapperScan` 扫描根

**现象**：project 服务含 `com.buildcloud.project` 与 `com.buildcloud.contract` 两个顶层域，测试配置把 `yudao.info.base-package` 写成 `com.buildcloud.project` 时，**contract 的 Mapper 扫不到**（`No qualifying bean`）。
**坑**：不能只改 base-package —— 它同时被 `mybatis-plus.type-aliases-package: ${yudao.info.base-package}.dal.dataobject` 引用，改成 `com.buildcloud` 会让 type-aliases 指向不存在的包。
**正确修法（两处一起改）**：
```yaml
yudao.info.base-package: com.buildcloud
mybatis-plus.type-aliases-package: ${yudao.info.base-package}.project.dal.dataobject,${yudao.info.base-package}.contract.dal.dataobject
```
（与主 `application.yaml` 写法一致）。改完即可删掉测试侧的临时补扫类。
**教训**：并行 worker 各自加「测试专属绕过」时，收尾要统一回正解，否则技术债会固化。

### L1-2 本机无 JDK 17，PATH 默认是 JDK 21

**现象**：`java -version` 是 21，基座 master-jdk17 编译/运行异常或行为不符。

**修复**：显式 `$env:JAVA_HOME='D:\project\tools\jdk-17.0.21.1+2'`（Temurin 17.0.21.1 已落盘）。基线：**构建与运行基座都必须用 17**。

### L1-3 `.env` / `.ps1` 必须 UTF-8；`.ps1` 必须带 BOM

**现象 A**：`docker compose` 报 `invalid UTF-8 text`。原因：PowerShell 5.1 的 `Set-Content` 默认 ANSI。
**修复 A**：`[IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`（无 BOM）。

**现象 B**：`.ps1` 里中文注释让 PS 5.1 报 `Unexpected token`。
**修复 B**：`.ps1` 存为 **UTF-8 with BOM**（与 `.env` 相反，务必区分）。

### L1-4 `Start-Process -RedirectStandardOutput` 在本机必失败

**现象**：`已添加项。字典中的关键字:"NO_PROXY"…:"no_proxy"`。
**原因**：Windows 环境变量存在 `NO_PROXY`/`no_proxy` 大小写重名，PS 5.1 的 Environment provider 崩溃。
**修复**：改用 `.NET ProcessStartInfo`（`CreateNoWindow` + `cmd /c chcp 65001` 重定向），行为等价。

### L1-5 `-Services a,b` 传参被绑成单个字符串

**现象**：脚本报「未知的 -Services 取值：system,infra」。
**原因**：`powershell -File script.ps1 -Services system,infra` 把 `system,infra` 作为**一个**字符串绑到 `[string[]]`。
**修复**：脚本内二次按 `[,; ]` 拆分。

### L1-6 Docker Desktop 会随空闲/过夜自动退出

**现象**：`failed to connect to the docker API at npipe://...`。
**修复**：任何依赖 Docker 的派发前置先 `docker info` 探测并拉起；worker 上下文包写明「先探测守护进程」。

### L1-7 中间件容器内存预算（16G 机器）

实测：mysql 508MB / nacos 900MB / rocketmq broker 762MB / namesrv 232MB / redis 8MB / seata 545MB / xxl-job 535MB / pg 24MB / minio 212MB。
**关键约束**：`yudao-spring-boot-starter-mq` 启动期即建 Producer，**缺 namesrv 直接启动失败**；`xxl-job/seata/postgres/minio` 在 local profile 下不需要，可停掉省约 1.5GB。4 个 Java 服务约 1.73GB。

---

## 二、基座（yudao-cloud master-jdk17）类

### L2-1 `.gitignore` 裸目录规则会误伤 Java 包名 —— **最隐蔽的坑**

**现象**：本地一切正常，`git clone` 到干净目录后 `yudao-module-system-server` 编译失败：
`找不到符号 DictDataPageReqVO`。

**原因**：`.gitignore` 第 40 行裸 `data/` 规则按**任意层级**匹配，把 Java 包目录
`controller/admin/dict/vo/data/` 整层忽略了——`DictData*VO` 四个类**从未入仓**。本地因文件还在磁盘上，一直看不出来。

**修复**：改为 `/data/`（锚定仓库根）。

**教训（已写入 AGENTS）**：**`git clone` 到全新目录编译必须作为「仓库完整性」标准动作**，每个里程碑做一次。本地工作区状态会系统性掩盖 `.gitignore` 误伤。

### L2-2 DO 必须继承 `TenantBaseDO`（不是 `BaseDO`）

**现象**：生成器产出的 DO `extends BaseDO`，多租户隔离失效（查得到别家租户数据）。
**原因**：基座多租户插件只对 `TenantBaseDO` 的子类自动拼 `tenant_id` 条件。
**决策**：只要表有 `tenant_id` 列，DO 就必须 `extends TenantBaseDO`。生成器不会替你判断，**精修时必检**。

### L2-3 Controller URL 段 = 服务/模块标识，与基座约定一致

**现象**：生成器产出 `@RequestMapping("/project/project")`，直连 48084 用 `/admin-api/project/project/page` 正常，但**经网关 404**。
**原因**：基座 `WebProperties.adminApi = new Api("/admin-api", "**.controller.admin.**")` 只加 `/admin-api` 前缀，模块名不进 URL；网关靠 `Path=/admin-api/<svc>/**` 区分服务。基座自身 Controller 都写成 `/system/dict-type`（首段=服务名）。
**修复**：自研 Controller 一律 `@RequestMapping("/buildcloud-<svc>/<domain>")`，如 `/buildcloud-project/project`。

### L2-4 `security` starter 里 `rpc` 是 optional，不传递

**现象**：新模块启动失败 `Parameter 0 of method globalExceptionHandler … ApiErrorLogCommonApi could not be found`。
**原因**：`yudao-spring-boot-starter-security` 的 rpc 依赖是 `<optional>true</optional>`，但 `YudaoApiLogRpcAutoConfiguration` 要用它注册 Feign 客户端。基座 4 个 server pom 都**显式声明** rpc。
**修复**：自研 server 模块显式引 `yudao-spring-boot-starter-rpc`。

### L2-5 健康探活不能用 `/actuator/health`

**现象**：脚本轮询 `/actuator/health` 一直超时。
**原因**：`yudao-spring-boot-starter-monitor` 里 `spring-boot-starter-actuator` 是 optional，不传递；基座 system/infra/gateway 的 jar 里没有 actuator（bpm 有是因为 Flowable 传递）。
**修复**：脚本改用 **TCP 端口探测**（不依赖服务端实现）。

### L2-6 健康接口要加 `yudao.tenant.ignore-urls`

**现象**：`/admin-api/buildcloud-*/health` 缺 `tenant-id` 头报 400；带上又因 `validTenant` 依赖 system-server 报 500。
**修复**：在 `yudao.tenant.ignore-urls` 加入健康路径，探活不依赖 system-server。

### L2-7 BPM 表 DDL 不在开源仓库里

**事实**：`sql/mysql/ruoyi-vue-pro.sql` 中 `bpm_*` 建表数 **0**；官方仅通过星球附件分发（`bpm-2025-10-04.sql.zip`，成员授权）。
**本项目做法**：用户本地 `D:\project\yudao\芋道（2025年三月份更新）` 有 `bpm-2025-03-15.sql.zip`，解压得 8 张表；与当前 DO 对比后**只缺 7 列**，用 `sql/mysql/bpm-alter-2026-11-25.sql` 补齐。
**授权注意**：该 SQL 文件头已注明「成员授权，勿对外分发」。**公开仓库嵌入前需评估**——若要彻底规避，改为按 DO + 单测 DDL 自行还原 8 张表（待办）。

### L2-8 Flowable 首启会建 45 张表

`ACT_*` 39 张 + `FLW_*` 6 张，由 `flowable.database-schema-update=true` 自动完成，**约 60 秒**，勿误判卡死。

### L2-9 基座默认 Nacos 命名空间 `dev` 在 Nacos 3.0.3 下不可用

**原因**：容器 `NACOS_AUTH_ENABLE=false` 但 `console.enabled/admin.enabled=true` 且无初始用户 → 控制台与 v3 API 全 403，v1/v2 已移除（410），无法建 `dev`。
**处置**：local 配置用 `public`（ADR-002，临时）。

### L2-10 中文落库必须带 `--default-character-set=utf8mb4`

**现象**：`项目主表` 变 `é¡¹ç®ä¸»è¡¨`，**写入后不可修复只能重导**。
**验证法**：`SELECT HEX(name)` 应为 `E9A1B9E79BAEE4B8BBE8A1A8`（正常中文 GBK/UTF8 形态可辨）。

### L2-11 单模块 `package` 前要先 `install` 对应 `-api`

**现象**：`-pl <server> package` 报 `Could not resolve com.buildcloud:<module>-api`。
**修复**：`install -pl <api>,<server>`，或 `-am`（但 `-am` 会把整条依赖链重编，慢）。

### L2-12 BPM 跨服务审批回调：基座**对每条业务流程都要一个转发器**（W3-P4 实证）

**现象**：项目立项审批流程能正常发起（`ACT_HI_PROCINST` 有实例、`ACT_RU_TASK` 有「部门经理审批」待办），
bpm 的 `/admin-api/bpm/task/approve` 也返回成功、流程正常结束，但业务表 `bc_project.approval_status`
**停在 1（审批中）永不变化**，`buildcloud-project-server` 日志里连一条回调请求都没有。

**原因**：审批结果的回流是 `Flowable 事件 → BpmProcessInstanceEventListener → Spring ApplicationEvent`，
`ApplicationEvent` **只在发布它的 JVM 内传播**。跨服务必须由 bpm-server 那个 JVM 里再有一个监听器
HTTP 转发过去。基座 `yudao-module-bpm-server/.../api/event/` 下有 16 个这样的转发器，但
**是「每个流程 key 一个类」的硬编码结构**，没有通用配置化转发（无「按 key 查 URL 表」实现）。
所以基座没为你的流程 key 写过转发器时，链路就是断的——**而且断得很安静**：全程无报错，
只有业务状态不动。

**判别法（3 秒定位）**：审批完看业务表状态没变 → 去 bpm-server 日志搜
`sendHttpRequest` / `PROCESS_INSTANCE_HTTP_CALL_ERROR`：
- 有 `sendHttpRequest` 且 URL 是你的业务服务 → 转发器在，问题在接收端；
- **完全没有 `sendHttpRequest`** → 转发器不存在，就是本条（W3-P4 实测就是这种）。

**修复**：照抄基座同类（如 `OaLeaveApplyStatusListener`）新增一个转发类
（`getProcessDefinitionKey()` 返回你的流程 key + `BpmHttpRequestUtils.executeBpmHttpRequest(event, url, restTemplate)`），
并在 `framework/rpc/config/RpcConfiguration` 用 `@Bean @ConditionalOnMissingBean` 追加注册。
业务侧按双模式实现接收端（`extends BpmProcessInstanceStatusEventListener` 且同时 `@RestController`）。
**改基座记 ADR**（本项目 = ADR-003）。W4/W5 的 `payment-approve`/`requisition-approve` 各自还要再加一处。

### L2-13 BPMN 部署 500：单出线排他网关**不允许带条件表达式**（W3-P4 实证）

**现象**：`POST /admin-api/bpm/model/deploy` 返回 `code=500 msg=系统异常`，日志根因：
```
org.flowable.common.engine.api.FlowableException: Errors while parsing:
[Validation set: 'flowable-executable-process'
 | Problem: 'flowable-exclusive-gateway-condition-not-allowed-on-single-seq-flow']
: Exclusive gateway has only one outgoing sequence flow. This is not allowed to have a condition.
```
**原因**：BPMN 里写成了 `userTask → exclusiveGateway → 单条带 CDATA[${true}] 的 sequenceFlow → endEvent`。
为了「避免多出线无条件分支报错」而给单出线加 `${true}` 条件，反而触发另一条校验规则。
**修复**：**审批通过/驳回不需要网关**——通过由 `approveTask` 的 `taskService.complete` 正常流出，
驳回由 `rejectTask` 的 `moveTaskToEnd` 直接把任务搬到 `EndEvent`（`BpmnModelUtils#getEndEvent` 取
流程里第一个 EndEvent）。**去掉网关、userTask 直连单个 endEvent** 最简，也保证 `moveTaskToEnd`
取到的结束事件唯一。只有真正需要「按变量走不同分支」时才用排他网关，且那时必须是**多出线**。

### L2-14 部署脚本「复用已有模型」时必须刷新 bpmnXml（W3-P4 实证）

**现象**：改了 `sql/buildcloud/bpm/project-approve.bpmn20.xml` 再跑部署脚本，deploy 报的**还是改之前的错**，
像是文件没生效。
**原因**：`POST /admin-api/bpm/model/create` 在 key 已存在时返回 `1009002000 MODEL_KEY_EXISTS`，
脚本走「复用已有模型」分支拿 `modelId` 后直接 `deploy`——但**复用的模型里存的是当初创建时的旧 bpmnXml**，
`deploy` 部署的是模型内容，不是磁盘文件。
**修复**：复用分支必须显式把最新 XML 写回模型，再 deploy：
`PUT /admin-api/bpm/model/update-bpmn`，body `{ id, bpmnXml }`（该接口标了 `@Deprecated` 但仍可用；
若将来移除，改走 `PUT /admin-api/bpm/model/update` 带完整 metaInfo）。
**影响面**：所有「BPMN 作为仓库内源文件、用脚本部署」的项目；`scripts/bpm-deploy-project-approve.ps1` 已修。

### L2-15 引了 `yudao-module-bpm-api` 依赖 ≠ Feign 客户端可用：还必须 `@EnableFeignClients(clients=...)`（W4-P5 实证）

**现象**：material 服务加上 `yudao-module-bpm-api` 依赖后编译通过，但启动即失败：
```
APPLICATION FAILED TO START
A component required a bean of type 'cn.iocoder.yudao.module.bpm.api.task.BpmProcessInstanceApi'
that could not be found.
```
**原因**：`BpmProcessInstanceApi` 上只有 `@FeignClient`，**依赖包里的接口不会自动被注册成代理 Bean**。
必须由使用方显式声明 `@EnableFeignClients(clients = {BpmProcessInstanceApi.class})`（基座 oa-server 与
本项目 project-server 都是这么做的，各自有一个 `framework/rpc/config/RpcConfiguration`）。
`@SpringBootApplication` 默认只扫 `com.buildcloud.material` 包，扫不到基座包下的 Feign 接口。
**修复**：在业务模块内新增
```java
@Configuration(value = "materialRpcConfiguration", proxyBeanMethods = false)
@EnableFeignClients(clients = {BpmProcessInstanceApi.class})
public class RpcConfiguration {}
```
bean 名带模块前缀，避免将来聚合部署时与 project 模块的同名配置类冲突。
**判别**：启动日志里是 `required a bean of type ... could not be found` 且**只缺 Feign 接口**（不是缺 Mapper/DataSource）
→ 先查 `@EnableFeignClients`，不要去查依赖坐标。
**影响面**：任何「新增自研服务 + 要调基座 RPC」的场景（W5 的 report/ai 服务同样会遇到）。
**另一面**：本类改动只加了一行 import，不改变任何既有行为。

### L2-16 会签的「多次回调」在业务侧只表现为一次：中途状态要靠 `ACT_RU_TASK` 而非业务回调验证（W4-P5 实证）

**背景**：基座 `BpmParallelMultiInstanceBehavior` 把会签节点改写为并行多实例，
**实例结束事件只在 `nrOfCompletedInstances >= nrOfInstances` 时发布**。
因此「会签 2 人只批了 1 个」在业务侧**根本没有回调**——不是回调丢了，是流程还合法地在跑。
**踩坑姿势**：把「驳回后状态没变」误判为链路断开（L2-12 那条的误报）。
**正确验证口径（三项一起看）**：
1. `SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_=?` —— 2 人会签应当是 2，批一个减一个；
2. `SELECT ASSIGNEE_ FROM ACT_RU_TASK` —— 两个任务应当是**两个不同的人**（验证 `${approvers}` 表达式取人生效）；
3. 业务表 `bc_requisition.status / approval_status` —— 只在任务数归零后才允许变化。
**实测（W4-P5 联调）**：approvers=[1,100] → `ACT_RU_TASK` 2 行，assignee 分别为 `1` 与 `100`；
admin(1) 批准后剩 1 行（assignee=100）、业务状态仍是 1；用 yudao(100) 登录再批准 → 任务归零、
bpm 转发器回调 `status=2`、业务状态 1→2、**库存未变**。
**附加坑**：基座 `/admin-api/bpm/task/approve` 是 **`PUT`** 而非 POST（用 POST 会得到
`code=405 请求方法不正确`，看着像接口不存在）；且**不能代替别人审批**——
用 admin 去批 assignee=100 的任务会返回 `1009005001 该任务的审批人不是你`。

### L2-17 PowerShell `Set-Content -Encoding utf8` 会给文件加 BOM，把 Java 源码编译炸掉（W4-P5 实证）

**现象**：对刚生成的 `.java` 做一次 `(Get-Content x -Raw) -replace ... | Set-Content -NoNewline -Encoding utf8 x`
之后编译报 `非法字符: '\ufeff'` + 后续几十条「需要 ')';」「非法类型开始」，错误行号指向中文字符串内部。
**双重根因**：
1. PS 5.1 的 `-Encoding utf8` 写入 **UTF-8 with BOM**，Java 编译器把 BOM 当非法字符；
2. 更隐蔽的是**读写往返的编码漂移**：`Get-Content -Raw` 按当前 ANSI 代码页读、再按 UTF-8 写回，
   中文注释被二次编码（`申请单编号` → `鐢抽鍗曠紪鍙`），从此**任何编译都不可能过**。
**修复**：改 Java 源文件一律用
`[IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)` + `[IO.File]::WriteAllText($p, $text, (New-Object System.Text.UTF8Encoding($false)))`，
且**优先用编辑工具而不是 shell**；改完抽查首三字节应为 `112,97,99`（`pac`，即 `package`），
**绝不能是 `239,187,191`**（UTF-8 BOM）。
**与 L1-3 的关系**：`.ps1` 需要 BOM、`.env` 需要无 BOM、**`.java`/`.sql` 必须无 BOM**——三者相反，容易混。

---

## 三、流程与协作类

### L3-1 并行 worker 一律不执行 git 写操作

共享工作区下并发 git 操作互相锁 `.git/index.lock`。规则：**worker 只改文件，主控统一提交**。已写入 `_dispatch.md` §6。

### L3-2 「运行时自动重跑」会造出重复 worker

**现象**：已完成的 worker 被重新拉起，可能与新 Phase worker 同时跑。
**处置**：发现后立刻 `send_message` 停止指令；**同一时间只允许一个阶段 worker**。

### L3-3 worker 报告必须独立复核，不轻信

复核清单（主控固定动作）：
1. `git status` 范围 ⊆ 声明 writeScope（超出要追问理由，如 L2-1 属合理超范围）；
2. 独立跑一次编译与单测（worker 说成功可能是缓存）；
3. 独立跑 `scripts/scan-secrets.ps1`；
4. 抽查关键产出（如 Controller 路径、DO 基类）；
5. 看 diff 是否动了基座（R-2）。

### L3-4 「验收标准」要写成可执行的命令

反例：「管理端可登录」。正例：「`POST /auth/login` 返回 `code=0` 且 `menus=28`」。
W1-P2 正是因为验收写成命令，才能发现 `code=404/401` 是 HTTP 200 + JSON body（基座统一契约），避免误判失败。

### L3-5 派发上下文包要带「环境事实」与「已知坑」

有效上下文包结构（见 `_dispatch.md` §3）：身份约束 → 任务定位 → 必读清单（spec **章节号** 而非全文）→ 环境事实（JDK 路径、端口、内存约束）→ 交付物 → 验收标准 → 完成动作。
W2-P2/P4 worker 能自主发现 3 个脚本缺陷，部分得益于上下文里给了「本机 PS 5.1」这类事实。

### L3-6 文档版本纪律的实践收益

W1 读码发现 spec 两处与基座现状不符 → 升 v1.2（D23）而不是偷偷改代码；W2 裁剪后 `.gitignore` 缺陷 → 单独 `fix:` 提交而非混进 `feat:`。
**价值**：面试讲「我怎么处理需求与现实冲突」时，有 ADR 与变更记录作为证据链。

### L3-7 逻辑删除 + 唯一索引 = 500 灾难（W4-P2 实证，全仓口径）

**现象**：删除材料目录 `STEEL` 后重建同编码 → `{"code":500,"msg":"系统异常"}`，用户无法自助。
**根因链**：① 逻辑删除（`deleted=1`）；② **MySQL 唯一索引不感知 `deleted`**，已删行仍占位；③ Service 的 `selectByCode` 被逻辑删除插件过滤看不到已删行 → 校验通过 → INSERT 撞索引 → 500。
**关键实证**：基座 `system_users`/`system_role`/`system_dict_type` **只有 PRIMARY，没有任何业务唯一索引**——基座唯一性 100% 靠 Service 层。**这个坑是我们「比基座更严」引入的。**
**处置**：见 **ADR-004** 混合口径——A 类（编码/单号，人可重建）用 Service 校验**查已删行** + 可读报错；B 类（结构唯一，如 `stock(project,material)`）保留索引作**并发防线**（高并发热点表必须有硬约束）。
**A 类探测 SQL 必须绕过逻辑删除插件**：
```java
@Select("SELECT COUNT(*) FROM bc_xxx WHERE code = #{code}")  // 原生 SQL 能看到已删行
Long countByCodeIncludeDeleted(@Param("code") String code);
```
**影响面**：`bc_project.uk_project_no`、`bc_contract.uk_contract_no`、`bc_material*.uk_*`、`bc_supplier.uk_supplier_no`、`bc_requisition.uk_req_no`。W4 收尾需出 `08-unique-index-realign.sql` 统一口径。

### L3-8 空的 `.sql` 脚本会让 Spring 直接炸

**现象**：H2 单测 50 个全 ERROR，`IllegalArgumentException: 'script' must not be null or empty`。
**原因**：`ScriptUtils.splitSqlScript` 去掉注释后内容为空即抛错——**注释不算内容**。
**修复**：`create_tables.sql`/`clean.sql` 即使「本模块不建业务表」，也必须留一条无副作用的 `SELECT 1;`。

### L3-9 并行 worker 会互相停掉对方起的服务

**现象**：W4-P3 联调时发现 48080/48081/48082/48083 被另一 worker 停掉（对方要打包，怕 jar 占用）。
**处置**：并行派发时，**在上下文包里明确「不要杀别人的服务进程；端口已监听就复用」**；或给 worker 指定不同端口段。
**根因**：L1-1c（运行中 jar 阻塞 repackage）的正确解法是「停自己的服务」而非「杀所有 java 进程」。

### L3-10 「先查库再断言」会漏掉响应体缺陷（本阶段最有价值的一条）

**现象**：`cancel` 接口响应体返回 `approvalStatus: -1`，而库里是 `4`。单测原本 30 个全绿——因为断言写的是 `mapper.selectById(id)`（**重查库，天然正确值**）。
**根因**：状态推进走 CAS SQL 直接改库，Service 内存 DO 只回填了 `status`，忘了 `approvalStatus`；`timeoutTime` 两处各调 `LocalDateTime.now()` 导致值不一致。
**修复**：逐字段回填 + 时间只算一次复用；补 2 个**只断言 Service 返回值**的回归用例。
**通用教训**：**验收必须至少有一条「断言 Service 返回值 == 库值」**，否则「库里对、响应体错」整类缺陷完全不可见。
**影响面**：所有走 CAS / 手写 SQL 改状态的 Service（W3 的审批回调、W5 的扣减都有此风险）。

### L3-11 时间字段跨库往返会掉精度，断言必须用容差

**现象**：`assertEquals(库值, 返回值)` 差 200 纳秒失败（`...358167` vs `...358167200`）。
**根因**：Java `LocalDateTime` 纳秒精度 vs MySQL `datetime` 秒精度 / H2 `timestamp` 微秒精度。
**修复**：`Duration.between(a, b).abs().toMillis() < 1` 之类容差比，不用 `assertEquals`。
**影响面**：所有时间字段的断言。

---

## 四、待验证 / 已知未闭环

| # | 事项 | 状态 | 计划 |
| --- | --- | --- | --- |
| 1 | `bpm_category`/`bpm_form`/`bpm_user_group` 为空，流程分类下拉为空 | 未闭环 | W3 补数据 |
| 2 | BPM SQL 的星球授权与公开仓库合规 | 待决策 | W3 前评估（或自行还原 DDL） |
| 3 | Nacos 是否改回 `dev` 命名空间 | 临时方案 | W2 后评估（ADR-002 升级） |
| 4 | 前端仍为 `yudao-demo` 预编译版（含无关菜单） | 临时 | W3 起换 `yudao-ui-admin-vue3` 源码裁剪 |
| 5 | `/actuator/health` 是否统一补齐 actuator | 已绕过 | 视需要（当前用 TCP 探测） |
| 6 | 启动脚本仍在仓库外（`D:\project\tmp\start-all.ps1`） | 已入库 `scripts/start-base.ps1` | 已完成 |
| 7 | `_tmp_outline.json`、`_tmp_txt/` 两个未跟踪杂项 | 未处理 | 待用户确认删除或忽略 |

---

## 五、追加约定

- 新增条目用 `L{类别}-{序号}`，类别：1=环境、2=基座、3=流程；
- 每条必须包含：**现象**（可观测）/ **原因**（根因）/ **修复**（可执行）/ **影响面**（谁要遵守）；
- 只有**实际踩到并验证修复**的才写进来，不写「可能存在的理论风险」；
- 失效条目保留并标记 `【已失效：原因】`，不删除。
