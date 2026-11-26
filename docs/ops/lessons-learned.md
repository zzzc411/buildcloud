# 实战经验与踩坑记录（lessons-learned）

| 项 | 内容 |
| --- | --- |
| 用途 | 沉淀 W0-W8 开发过程中**真实踩到的坑**与验证过的做法，供后续 Phase、子智能体派发上下文包、面试复盘复用 |
| 版本 | v1.0（覆盖 W0-W2，2026-11-26） |
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
**修复**：先停服务；或临时加 `-Dspring-boot.repackage.skip=true`（仅验证编译时）。**不要为了打包去 kill 生产/演示进程**——先问。 

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
