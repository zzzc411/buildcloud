# 筑云 BuildCloud · 建筑工程项目管理平台

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17-orange)](#) [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](#) [![Status](https://img.shields.io/badge/status-W2%20进行中%20·%20基座入仓-yellow)](#-里程碑路线图)

> 面向建筑施工企业的工程项目管理平台：微服务架构，覆盖**项目立项、合同资金、材料库存与集中申领（高并发主战场）、多级审批、异步报表**，并内置基于 **RAG** 的企业制度/施工规范知识库问答。

- 📋 总规格（唯一范围与排期依据）：[docs/spec/buildcloud-spec-v1.2.md](docs/spec/buildcloud-spec-v1.2.md)
- 🗂️ 文档中心：[docs/00-index.md](docs/00-index.md) ｜ 开发计划拆分：[docs/plans/00-overview.md](docs/plans/00-overview.md)
- 🤖 人与智能体共同开发约束：[AGENTS.md](AGENTS.md) ｜ 子智能体派发指南：[docs/plans/_dispatch.md](docs/plans/_dispatch.md)

## 项目状态

**W1 已完成（2026-11-25）：中间件 9 容器 + 基座四服务（gateway/system/infra/bpm）本地跑通，管理端可登录（admin/admin123）。** 详见 [docs/ops/deploy-local.md](docs/ops/deploy-local.md)。

**W2 进行中（2026-11-25 起）：基座源码已入仓（`master-jdk17` @ `8af49f7`），全量编译通过；下一步删 15 个无关模块、密钥模板化并建 4 个自研模块骨架。** 计划见 [docs/plans/plan-w2-repo-skeleton.md](docs/plans/plan-w2-repo-skeleton.md)。

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
│  ↓ 以下为基座源码（W2-P1 已入仓，`mvn clean package -DskipTests` 全量通过；
│     15 个无关模块待 W2-P2 删除，故当前目录中仍可见）
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

> 🚧 **W2 进行中：基座源码已入仓且全量编译通过，但一键启动脚本与模板化配置在 W2-P2/P4 交付。** 届时此处更新为：克隆 → 复制 `*-template` 配置填本地值 → `docker compose` 起中间件 → 起服务 → 登录管理端。

```bash
git clone https://github.com/zzzc411/buildcloud.git
cd buildcloud
# 当前（W2-P1）可直接验证基座可编译（需 JDK 17）：
mvn clean package -DskipTests
# W2-P4 后：cp docker/.env.template docker/.env  # 填本地配置（密钥不入库）
# W2-P4 后：docker compose -f docker/middleware.yaml up -d
```

## 里程碑路线图

| 周 | 目标 | 状态 |
| --- | --- | --- |
| W0 | 仓库与文档体系初始化（本阶段） | ✅ 完成（2026-11-24） |
| W1 | Docker 编排中间件 + 基座跑通 + 读码地图 | ✅ 完成（2026-11-25） |
| W2 | 基座裁剪 + 4 个自研模块骨架 + 代码生成器四件套 | 🔄 进行中（P1 ✅ 基座入仓，P2-P4 待做） |
| W3 | 项目域 + 合同域 + 立项审批流 | ⬜ |
| W4 | 材料域 V1（同步链路）+ 申领审批闭环 | ⬜ |
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
| 当前状态 | W2-P1：全量源码已入仓并全量编译通过；W2-P2 起删 15 个无关模块并做配置模板化 |

> 基座代码包名/坐标（`cn.iocoder.yudao.*`）原样保留；自研代码只进 `buildcloud-module-*`（包名 `com.buildcloud.*`），以便上游安全更新可对比、可回流。
