# 筑云 BuildCloud · 建筑工程项目管理平台

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17-orange)](#) [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](#) [![Status](https://img.shields.io/badge/status-W2%20完成%20·%2044%20模块-yellow)](#-里程碑路线图)

> 面向建筑施工企业的工程项目管理平台：微服务架构，覆盖**项目立项、合同资金、材料库存与集中申领（高并发主战场）、多级审批、异步报表**，并内置基于 **RAG** 的企业制度/施工规范知识库问答。

- 📋 总规格（唯一范围与排期依据）：[docs/spec/buildcloud-spec-v1.2.md](docs/spec/buildcloud-spec-v1.2.md)
- 🗂️ 文档中心：[docs/00-index.md](docs/00-index.md) ｜ 开发计划拆分：[docs/plans/00-overview.md](docs/plans/00-overview.md)
- 🤖 人与智能体共同开发约束：[AGENTS.md](AGENTS.md) ｜ 子智能体派发指南：[docs/plans/_dispatch.md](docs/plans/_dispatch.md)

## 项目状态

**W1 已完成（2026-11-25）：中间件 9 容器 + 基座四服务（gateway/system/infra/bpm）本地跑通，管理端可登录（admin/admin123）。** 详见 [docs/ops/deploy-local.md](docs/ops/deploy-local.md)。

**W2 已完成（2026-11-26）：基座裁剪为 7 个模块（system/infra/bpm + framework/gateway）+ 4 个自研模块骨架（project/material/report/ai，端口 48084-48087），配置全模板化，44 个 Maven 模块全量编译通过，代码生成器四件套（Java+SQL+Vue+单测）跑通并精修入库，全新克隆按下方 README 可复现启动。** 计划见 [docs/plans/plan-w2-repo-skeleton.md](docs/plans/plan-w2-repo-skeleton.md)。

> **关于二次开发（诚信声明）**：本项目基于开源微服务基座 [yudao-cloud](https://github.com/YunaiV/yudao-cloud)（master-jdk17 分支，MIT 协议）裁剪二次开发——用户/权限/工作流等通用能力来自基座；**六个建筑业务域（项目/合同/材料/审批/报表/AI）、高并发申领链路、RAG 知识库、部署上线**为自研设计与实现。

## 架构概览

```
 yudao-ui-admin-vue3（裁剪适配）
        │
 yudao-gateway（路由/鉴权/Sentinel 限流）
        │
 ┌──────┴───────────────────────────────────────┐
 │ yudao-module-system  用户/租户/RBAC/字典      │ 基座保留
 │ yudao-module-infra   文件(MinIO)/生成/监控    │ 基座保留
 │ yudao-module-bpm     Flowable 工作流         │ 基座保留
 │ buildcloud-module-project  项目+合同资金      │ 自研
 │ buildcloud-module-material 材料/库存/申领     │ 自研（高并发主战场）
 │ buildcloud-module-report   异步导出/看板      │ 自研
 │ buildcloud-module-ai       RAG 知识库/问答    │ 自研
 └──────────────────────────────────────────────┘
 Nacos │ MySQL 8(逻辑4库分片) │ Redis(Redisson) │ RocketMQ
 Seata │ XXL-Job │ MinIO │ PostgreSQL+pgvector
```

**高并发专项**：材料集中申领链路 V1（DB 行锁同步扣减）→ V2（Redis Lua 原子预扣 + RocketMQ 事务消息异步落库 + 延迟关单 + 对账兜底）对照实现与压测（0 超卖可验证）。详见 spec §6.1。

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言/框架 | JDK 17 · Spring Boot 3.5 · Spring Cloud Alibaba |
| 微服务治理 | Nacos（注册/配置）· Spring Cloud Gateway + Sentinel · Seata（AT） |
| 数据 | MySQL 8（ShardingSphere-JDBC 逻辑 4 库×8 表分片）· PostgreSQL + pgvector · Redis + Redisson · MyBatis Plus |
| 消息/任务 | RocketMQ（事务消息/延迟消息）· XXL-Job（分片广播）· Flowable（工作流） |
| AI | Spring AI · OpenAI 兼容 API（主 DeepSeek，供应商/模型/Key 动态配置） |
| 存储/观测 | MinIO · EasyExcel · SkyWalking(可选) · Spring Boot Admin · Arthas |
| 前端 | yudao-ui-admin-vue3（Vue3，裁剪适配） |

## 仓库骨架

```
buildcloud/
├── README.md / AGENTS.md / LICENSE / .gitignore
├── docs/                          # 文档中心（现已就绪，见 docs/00-index.md）
│   ├── spec/                      #   总规格（v1.2 生效）
│   ├── plans/                     #   开发计划（W1-W8 + 专项已拆分；含 00-overview/_template/_dispatch）
│   ├── decisions/                 #   ADR 决策记录
│   ├── architecture/              #   架构图 / ER 图 / 读码笔记
│   ├── testing/                   #   压测报告 / 评测集
│   ├── ops/                       #   部署手册 / 云服务器上线记录
│   └── archive/                   #   历史版本归档
├── docker/                        # 中间件 docker-compose 编排（W1）
├── sql/                           # 基座脚本（W2-P2 裁剪为 system/infra/bpm）+ sql/buildcloud/ 自研 bc_* 表
│
│  ↓ 以下为基座源码（W2-P1 入仓，W2-P2 已裁掉 15 个无关模块；`mvn clean package -DskipTests` 全量通过）
├── yudao-dependencies/            # 依赖 BOM（基座）
├── yudao-framework/               # 框架 starters（基座）
├── yudao-gateway/                 # 网关（基座）
├── yudao-server/                  # 独立启动器（基座）
├── yudao-module-system/           # 系统管理（基座）
├── yudao-module-infra/            # 基础设施（基座）
├── yudao-module-bpm/              # Flowable 工作流（基座）
├── yudao-module-ai/               # 仅作 RAG 设计参考，已从根 pom 摘除（D12，基座）
└── buildcloud-module-project/     # 自研①：项目 + 合同（W2-P3 建骨架，包内分包 com.buildcloud.project/contract）
    buildcloud-module-material/    # 自研②：材料（高并发核心，W2-P3）
    buildcloud-module-report/      # 自研③：异步报表（W2-P3）
    buildcloud-module-ai/          # 自研④：RAG 知识库（W2-P3）
```

## 快速开始

> 环境：Windows 10/11 + PowerShell（macOS/Linux 把 `\` 换 `/`，脚本需按需改写）。全流程约 15-20 分钟，
> 其中首次 `mvn package` 约 3-5 分钟、中间件首次拉镜像约 3-8 分钟。

### ① 前置准备

| 依赖 | 版本/要求 | 说明 |
| --- | --- | --- |
| JDK | **17**（必需） | 本机 PATH 若默认 JDK 21 会编译失败，须显式指定 `JAVA_HOME`。示例：`D:\project\tools\jdk-17.0.21.1+2` |
| Maven | 3.9+ | 用 `mvn -v` 确认运行时用的是 JDK 17 |
| Docker Desktop | 4.x，**内存 ≥ 10GB** | 中间件共 5 个容器（MySQL/Redis/Nacos/RocketMQ namesrv+broker）。资源限额见 [docs/ops/middleware-limits.md](docs/ops/middleware-limits.md) |
| 管理端前端 | 见第 ⑧ 步 | 后端仓库**不含**前端工程 |

```powershell
# 让当前终端用 JDK 17（每次新开终端都要设一次）
$env:JAVA_HOME = 'D:\project\tools\jdk-17.0.21.1+2'   # 换成你自己的 JDK 17 路径
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version   # 应输出 17.x
```

### ② 克隆并进入仓库

```powershell
git clone https://github.com/zzzc411/buildcloud.git
cd buildcloud
```

### ③ 生成配置文件并填本地密码（不入库）

```powershell
Copy-Item docker\.env.template docker\.env
notepad docker\.env     # 改 MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD / REDIS_PASSWORD 等
```

> ⚠️ **`docker/.env` 与各模块的 `application-local.yaml` 已被 gitignore，永远不要提交**（AGENTS.md R-1）。
> 提交前务必跑 `powershell -ExecutionPolicy Bypass -File scripts\scan-secrets.ps1 -Staged`。

### ④ 启动中间件

```powershell
docker compose -f docker/middleware.yaml up -d
# 等健康（MySQL 首启初始化约 30 秒）
docker compose -f docker/middleware.yaml ps
```

内存紧张时分批启动（compose 服务名是小写的 `mysql`/`redis`/`nacos`/`rocketmq-namesrv`/`rocketmq-broker`，
容器名才带 `bc-` 前缀）：

```powershell
docker compose -f docker/middleware.yaml up -d mysql redis nacos
```

### ⑤ 导入 SQL

> MySQL 容器首次启动时会自动执行 `docker/mysql/init/01-create-databases.sql`，建好 `ruoyi-vue-pro`
> 与分片预留库 `pm_log_0..3`，**无需手动 CREATE DATABASE**。下面只导表结构 + 数据。

```powershell
# <PWD> 换成 docker/.env 里的 MYSQL_ROOT_PASSWORD
# 基座主库（表结构 + 初始化数据，必须最先导）
Get-Content sql/mysql/ruoyi-vue-pro.sql -Raw -Encoding UTF8 | docker exec -i bc-mysql mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro
# 工作流 Flowable 表
Get-Content sql/mysql/bpm-2025-03-15.sql -Raw -Encoding UTF8 | docker exec -i bc-mysql mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro
Get-Content sql/mysql/bpm-alter-2026-11-25.sql -Raw -Encoding UTF8 | docker exec -i bc-mysql mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro
# 筑云自研表（bc_ 前缀）+ 菜单树
Get-Content sql/buildcloud/01-project.sql -Raw -Encoding UTF8 | docker exec -i bc-mysql mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro
Get-Content sql/buildcloud/02-menu.sql -Raw -Encoding UTF8 | docker exec -i bc-mysql mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro
```

> 脚本 `sql/buildcloud/*.sql` 均可重复执行（`CREATE TABLE IF NOT EXISTS` + 固定号段先删后插），
> 详见 [sql/buildcloud/README.md](sql/buildcloud/README.md)。
> **`--default-character-set=utf8mb4` 不能省**，否则中文注释/菜单名会乱码。

### ⑥ 编译整个仓库

```powershell
mvn -B -ntp '-DskipTests' '-Dmaven.test.skip=true' package
```

> PowerShell 下 **必须给 `-D` 参数加引号**，否则会被拆成多个参数导致构建异常。

### ⑦ 启动服务

```powershell
# 起基座四服务（system → infra → bpm → gateway，逐个等端口就绪）
powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1 -Services system,infra,bpm,gateway

# 再起四个自研服务（48084-48087）
powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1 -Services buildcloud

# 也可以一次起全部
powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1 -Services all
```

脚本动作：读 `docker/.env` 注入环境变量 → 缺 `application-local.yaml` 时从 `.template` 复制 →
后台启动 jar → 等端口可连接。日志落在 `logs\<服务名>.out.log`。

> **健康探活为何看端口**：基座服务的 actuator 依赖在 `yudao-spring-boot-starter-monitor` 中为 `optional`，
> 不传递到业务服务，`/actuator/health` 通常 404，因此脚本用 TCP 端口探测判断就绪。

| 服务 | 端口 | 健康检查 |
| --- | --- | --- |
| gateway | 48080 | `http://127.0.0.1:48080` |
| system | 48081 | `GET /admin-api/system/auth/login`（POST 登录） |
| infra | 48082 | `GET /admin-api/infra/file/list`（需登录） |
| bpm | 48083 | `GET /admin-api/bpm/model/list`（需登录） |
| project / material / report / ai | 48084-48087 | `GET /admin-api/buildcloud-{project,material,report,ai}/health` |

### ⑧ 登录管理端

后端跑通后，前端二选一（本仓库不含前端源码）：

- **方式 A（推荐，零构建）**：用基座官方预编译演示前端 `yudao-ui-admin-vue3` 的 dist，配置网关地址
  `http://127.0.0.1:48080`（`VITE_BASE_URL` + `VITE_API_URL` 均指向该地址）；或直接访问
  `http://127.0.0.1:48080/doc.html` 看接口文档验证后端。
- **方式 B（要改前端）**：clone [yudao-ui-admin-vue3](https://github.com/yudaocode/yudao-ui-admin-vue3)，
  按项目 README 配 `.env.local` 指向本地网关，`pnpm i && pnpm dev`。

默认账号 `admin / admin123`（基座初始化数据，**仅本地演示用，上线前必须改**）。
登录后左侧应能看到「项目管理 / 合同资金 / 材料管理 / 报表中心 / AI 助手」五个菜单组（来自
`sql/buildcloud/02-menu.sql`；新菜单需在「角色管理」里给角色授权后才可见）。

> 登录接口必须带租户头 `tenant-id: 1`，否则返回 `code=400 请求的租户标识未传递`。

### 验证清单

```powershell
# 1. 自研模块经网关可达
Invoke-RestMethod http://127.0.0.1:48080/admin-api/buildcloud-project/health

# 2. 密钥零泄漏（应输出 [通过] ... 0 命中）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\scan-secrets.ps1
```

## 里程碑路线图

| 周 | 目标 | 状态 |
| --- | --- | --- |
| W0 | 仓库与文档体系初始化（本阶段） | ✅ 完成（2026-11-24） |
| W1 | Docker 编排中间件 + 基座跑通 + 读码地图 | ✅ 完成（2026-11-25） |
| W2 | 基座裁剪 + 4 个自研模块骨架 + 代码生成器四件套 | ✅ 完成（2026-11-26：P1 基座入仓 / P2 裁剪+配置模板化 / P3 四模块骨架 / P4 生成器四件套+README 实操） |
| W3 | 项目域 + 合同域 + 立项审批流 | ✅ 完成（2026-11-27） |
| W4 | 材料域 V1（同步链路）+ 申领审批闭环 | ✅ 完成（2026-11-28） |
| W5 | 高并发改造 V2（Lua 预扣 / 事务消息 / 延迟关单 / 对账） | ⬜ |
| W6 | Seata + 分库分表 + 异步报表 + 首轮压测 | ⬜ |
| W7 | AI 模块（RAG / SSE / 引用溯源 / 动态 LLM 配置） | ⬜ |
| W8 | 终版压测 + 云服务器部署上线 + 文档收官 | ⬜ |

每周验收标准见 spec §9；实时状态见 [docs/plans/00-overview.md](docs/plans/00-overview.md)。

## 基座来源与许可证

本项目基于开源微服务基座 [yudao-cloud](https://github.com/YunaiV/yudao-cloud)（MIT 协议）二次开发，遵循 MIT 协议，[LICENSE](LICENSE) 随附于仓库根目录。基座代码按以下版本入仓（可追溯复现）：

| 项 | 值 |
| --- | --- |
| 上游仓库 | https://github.com/YunaiV/yudao-cloud （镜像：https://gitee.com/zhijiantianya/yudao-cloud） |
| 分支 | `master-jdk17` |
| 入仓 commit | `8af49f7ce59d44c45289ea267d74fd12b305b1cf`（2026-11-23） |
| 入仓方式 | 冻结快照拷入（不引入上游 Git 历史，D1），保留 `LICENSE` 与包名/坐标（R-2） |
| 当前状态 | W2 完成：保留 7 个基座模块（dependencies/framework/gateway/server/system/infra/bpm），已删除 15 个无关模块；`yudao-module-ai` 源码保留但不参与构建（D12） |

> 基座代码包名/坐标（`cn.iocoder.yudao.*`）原样保留；自研代码只进 `buildcloud-module-*`（包名 `com.buildcloud.*`），以便上游安全更新可对比、可回流。
