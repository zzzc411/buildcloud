# 开发计划：plan-w2-repo-skeleton（基座裁剪 + 自研模块骨架）

| 项 | 内容 |
| --- | --- |
| 计划编号 | plan-w2-repo-skeleton |
| 对应 spec 章节 | §4（裁剪方案全部）、§9 W2、D16/D17 |
| 状态 | 进行中（P1 ✅ / P2 ✅ / P3-P4 待做） |
| 计划开始 | 2026-12-07（周一） |
| 计划完成 | 2026-12-13（周末，打 tag `w2`） |
| 前置依赖 | plan-w1-infra-up（✅ 环境、登录、读码地图就位）；plan-sp-key-management（∥ 并行推进，P2 裁剪时必须落地其规范） |
| 分支约定 | `feature/w2-skeleton`（引入基座后主干重，单独分支保护 main） |

## 1. 目标（一句话）

公开仓库落地裁剪后的基座（删 15 模块零残留），4 个 `buildcloud-module-*` 空模块可注册 Nacos、经网关路由返回健康检查，代码生成器跑通「Java+Vue+SQL+单测」四件套，任何人克隆即可按 README 复现启动。

## 2. Phase 清单（每个 Phase = 一次子智能体派发单元）

### Phase 1：基座源码入仓库（裁剪起点）

| 项 | 内容 |
| --- | --- |
| 工作量 | 1d |
| 任务 | clone yudao-cloud master-jdk17 → 拷入本仓库根（保留 LICENSE 与来源注明）；`yudao-module-ai` 保留源码但从构建摘除（D12，设计参考）；提交历史从本次拷入开始（全新历史，D1） |
| 交付物 | 仓库含完整基座源码（未删模块前）+ 根 README「基座来源」注明；`mvn clean package -DskipTests` 全量编译通过（基座原样） |
| 验收标准 | [x] `mvn clean package -DskipTests` 全量编译通过（85 模块，10m23s，2026-11-25 实测）；[x] LICENSE 与来源注明就位（根 `LICENSE` + README「基座来源与许可证」表：仓库/分支/commit=`8af49f7`/入仓方式）；[x] 提交不含本项目真实密钥（docker/.env 值、本机端口 13306/16379、本机内网 IP 扫描 0 命中）；上游公开 demo 默认值（`123456`、`${OPENAI_API_KEY:sk-xxxx}` 等占位/演示值）按 spec §4.3.8 在 P2 由 sp-key Phase 1 统一模板化 |
| 上下文包（必读） | spec §4.1/§4.2/§11 D12-D14；基座根 pom 的 modules 列表 |
| 状态 | ✅（2026-11-25） |

**P1 落地记录（2026-11-25）**

- 来源：`https://github.com/YunaiV/yudao-cloud` 分支 `master-jdk17`，HEAD `8af49f7ce59d44c45289ea267d74fd12b305b1cf`（2026-11-23），经 `git archive` 冻结快照拷入，**不引入上游 Git 历史**（D1）；工作分支 `feature/w2-skeleton`。
- 入仓范围：根 `pom.xml`、`lombok.config`、`LICENSE`、`yudao-dependencies`、`yudao-framework`、`yudao-gateway`、`yudao-server`、`yudao-module-*` 全部 22 个业务模块（含待删的 15 个与仅作参考的 `yudao-module-ai`）、`sql/`、`script/`。
- 未入仓（有意剔除）：上游 `.git/`、`.gitee/`、`.github/`（避免沿用上游 CI 触达本仓库 Secrets）、`.image/`（上游 README 示意图，本仓库 README 已重写）、根 `README.md`（本仓库自有）、`.gitignore`/`.gitattributes`（按本项目规则合并）。
- 构建摘除：根 `pom.xml` 的 `<modules>` 移除 `yudao-module-ai`（D12，源码保留作 RAG 设计参考）。
- 配置例外：`**/application-local.yaml`（24 份）按 R-1 继续由 `.gitignore` 排除（本地真实值只留在工作区，P2 提供 `*-template.yaml`）。
- 编译验证：`JAVA_HOME=D:\project\tools\jdk-17.0.21.1+2` + `mvn clean package -DskipTests` → **BUILD SUCCESS，85 模块，10m23s**（日志 `D:\project\svc-logs\w2-p1-build.log`）。
- **P2 前置（因 GitHub push protection 阻断而提前）**：上游示例数据含云厂商 Key 形态串（`infra_file_config` 示例 S3 配置、`system_sms_channel` 示例短信通道、`yudao-module-ai` 单测内嵌 Key），首次 push 被 GitHub 拒绝。处置：① 按 spec §4.3.3 删除非 MySQL 方言 SQL 目录（db2/dm/highgo/kingbase/oceanbase/opengauss/oracle/postgresql/sqlserver，`sql/` 只留 `mysql/` + `tools/`，上游可随时再取）；② `sql/mysql/ruoyi-vue-pro.sql` 演示 AccessKey 改为 `DEMO_REDACTED_*` 占位；③ `yudao-module-ai` 单测内嵌 Key 改占位符（该模块不参与构建，不影响编译）。索引层复扫 `AKID/LTAI/AKLT/sk-` 形态 0 命中。此条属 R-1 红线合规，非范围扩张（删方言目录本就是 P2 任务）。


### Phase 2：删 15 模块及连带引用（含密钥模板化）

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | 删 member/pay/report/mp/mall/erp/wms/crm/iot/mes/im/hrm/fms/pms/oa 十五个模块；连带：根 pom modules、Nacos 配置清理、`sql/` 只留 system/infra/bpm、网关路由删除无效项、system 菜单初始化 SQL 清死链；**同步执行 plan-sp-key-management 的模板化规范**（所有 application yaml 密钥位改 `${env}`） |
| 交付物 | 裁剪后可编译仓库 + `sql/` 清单 + 网关路由表（docs/architecture 记录） |
| 验收标准 | [x] `mvn clean package -DskipTests` 通过（32 模块 / 1m15s，2026-11-26 实测，lead 独立复验 7.5s compile）；[x] 全局搜索无被删模块编译期引用（pom/网关/Java import 0 命中）、菜单无死链（system_menu 3449→259，仅 system/infra/bpm 三棵树）；[x] 密钥 grep 扫描 0 命中（scripts/scan-secrets.ps1，2277 文件，含 dev/application.yaml 去密钥、6 份 *-template 占位） |
| 上下文包（必读） | spec §4.2/§4.3（8 条连带改动点全做）；`docs/plans/plan-sp-key-management.md`；Phase 1 产出 |
| 状态 | ✅（2026-11-26） |

### Phase 3：建 4 个 buildcloud 模块骨架

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 建 `buildcloud-module-project / -material / -report / -ai`：groupId `com.buildcloud`（D16）；结构照基座模块（framework starter 引用按读码笔记 ④）；project 模块内预建 `com.buildcloud.project.*` / `com.buildcloud.contract.*` 分包（D17）；注册 Nacos + 网关路由 + 一个健康检查/空控制器接口；预留端口接入 Phase 规划表 |
| 交付物 | 4 模块可启动；网关 `/admin-api/buildcloud-{x}/health` 可路由 |
| 验收标准 | [ ] 4 模块注册 Nacos 可见；[ ] 网关路由返回健康检查；[ ] 包结构与 starter 引用符合基座规范（对照读码笔记） |
| 上下文包（必读） | spec §4.2（新增 4 模块行）；读码笔记 starter 篇（W1 产出）；基座任一模块做模板参考（只读） |
| 状态 | ☐ |

### Phase 4：代码生成器四件套 + 复现启动验收

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 用基座 infra 代码生成器对一张业务表（建议 `bc_project`）跑通 Java+Vue+SQL+单测四件套并精修入库；README「快速开始」从占位变实操（克隆→模板配置→compose→起服务→登录）；菜单树初始化 SQL（五大菜单组骨架） |
| 交付物 | 生成器四件套示例 + README 快速开始实操版 + `sql/buildcloud/` 目录规范落地 |
| 验收标准 | [ ] 生成器四件套完整产出且精修后编译/单测通过；[ ] **全新克隆目录按 README 可复现启动**（§4.4 硬条款）；[ ] 密钥扫描 0 命中复验 |
| 上下文包（必读） | spec §4.4（验收清单全文）；基座代码生成器文档；Phase 2/3 产出 |
| 状态 | ☐ |

## 3. 计划级验收标准（出口条件，来自 spec §4.4，逐条）

- [ ] `mvn clean package -DskipTests` 全量编译通过；
- [ ] 一键 docker-compose 起中间件 + 依次起服务，管理端可登录；
- [ ] 被删模块无任何残留引用（编译期依赖 + 菜单死链双查）；
- [ ] 新增空模块注册 Nacos、经网关路由返回健康检查；
- [ ] 代码生成器四件套跑通；
- [ ] 公开克隆按 README 复现启动；
- [ ] 密钥泄漏扫描 0 命中；
- [ ] tag `w2` + `00-overview.md` 状态同步。

## 4. 涉及范围（改动声明）

- 代码模块：仓库引入基座全量 → 删 15 模块 → 增 4 空模块（首次大规模仓库变更）；
- 数据库/SQL：`sql/` 清理 + 菜单树初始化 + `sql/buildcloud/` 目录；
- 配置/Nacos：全部 application yaml 密钥模板化（联动 sp-key-management）；
- 文档：README 重写（实操版）、架构文档路由表、本计划。

## 5. 风险与回退

| 风险 | 对策 |
| --- | --- |
| 裁剪连带引用漏删导致编译失败 | R1 对策：分批删（先 pom 后目录），每批编译验证；卡壳切 mini 路线（spec §4.1 备选，须 ADR） |
| 基座配置含硬编码密码 | Phase 2 强制模板化 + 扫描；漏网即返工 |
| 生成器产物风格漂移 | 精修对照基座示例（AGENTS §6.5） |

## 6. 进度日志（追加式，一行/次）

- 2026-11-24：计划创建（自 spec §9 W2 / §4 拆分）。
- 2026-11-26：**P2 完成** — 删 15 个无关模块（6280 文件）+ 根 pom（22→7 modules）/网关路由（8 条）/knife4j/菜单 SQL 连带清理；sql/ 仅留 mysql（新增 bpm-2025-03-15.sql、bpm-alter-2026-11-25.sql；system_menu 裁剪 3190 行、role_menu 1488 行，只剩 system/infra/bpm 三棵树）；6 份 application-local.yaml → *-template + `${VAR:默认}` 占位，dev/application.yaml 同步去密钥；新增 scripts/start-base.ps1、scripts/scan-secrets.ps1（全仓 0 命中，假密钥样本可检出）；`mvn clean package -DskipTests` BUILD SUCCESS（32 模块 / 1m15s）；lead 独立复核：编译 SUCCESS、扫描 0 命中、diff check OK。下一步：P3 建 4 个 buildcloud 模块骨架（Nacos 注册 + 网关路由 + 健康检查）。
- 2026-11-26：**P1 已推送远端** — `feature/w2-skeleton` @ `bef2530`（GitHub 校验 9605 个文件，LICENSE/README/docs 齐备；本地分支已设 upstream 跟踪）。首次 push 被 GitHub push protection 拦截（上游多方言 SQL 含演示云 Key 形态串），按 spec §4.3.3 先删 9 个非 MySQL 方言 SQL 目录、MySQL 脚本演示 AccessKey 改 `DEMO_REDACTED_*`、`yudao-module-ai` 单测 Key 改占位符后复推通过（详见 P1 落地记录「P2 前置」条）。P2 正由 worker `w2p2-trim` 并行执行。
- 2026-11-25：**P1 完成** — 基座 `master-jdk17` @ `8af49f7` 冻结快照入仓（`feature/w2-skeleton`）：22 个业务模块 + framework/gateway/server/dependencies + sql/script 就位；根 pom 摘除 `yudao-module-ai`（D12）；24 份 `application-local.yaml` 按 R-1 不入库；`mvn clean package -DskipTests` 85 模块 BUILD SUCCESS（10m23s）；README 增「基座来源与许可证」表并更新 W2 状态。下一步：P2 删 15 模块 + 网关路由/菜单/SQL 连带清理 + 配置模板化（联动 sp-key P1），目标 `mvn clean package -DskipTests` 仍通过且密钥扫描 0 命中。
