# 筑云 BuildCloud · 建筑工程项目管理平台

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![JDK](https://img.shields.io/badge/JDK-17-orange)](#) [![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-brightgreen)](#) [![Status](https://img.shields.io/badge/status-规划完成%20·%20W1%20待开工-yellow)](#-里程碑路线图)

> 面向建筑施工企业的工程项目管理平台：微服务架构，覆盖**项目立项、合同资金、材料库存与集中申领（高并发主战场）、多级审批、异步报表**，并内置基于 **RAG** 的企业制度/施工规范知识库问答。

- 📋 总规格（唯一范围与排期依据）：[docs/spec/buildcloud-spec-v1.0.md](docs/spec/buildcloud-spec-v1.0.md)
- 🗂️ 文档中心：[docs/00-index.md](docs/00-index.md) ｜ 开发计划拆分：[docs/plans/00-overview.md](docs/plans/00-overview.md)
- 🤖 人与智能体共同开发约束：[AGENTS.md](AGENTS.md)

## 项目状态

**规划完成，代码未动工（W0 文档与仓库初始化阶段）。** 业务代码自 2026-11-30（W1）起按周推进，每周计划与验收见 [docs/plans/00-overview.md](docs/plans/00-overview.md)。

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
│   ├── spec/                      #   总规格（v1.0 生效）
│   ├── plans/                     #   开发计划（从 spec 按周/专项拆分）
│   ├── decisions/                 #   ADR 决策记录
│   ├── architecture/              #   架构图 / ER 图 / 读码笔记
│   ├── testing/                   #   压测报告 / 评测集
│   ├── ops/                       #   部署手册 / 云服务器上线记录
│   └── archive/                   #   历史版本归档
├── docker/                        # 中间件 docker-compose 编排（W1）
├── sql/                           # 基座脚本 + sql/buildcloud/ 自研 bc_* 表（W2 起）
│
│  ↓ 以下为 W2 基座裁剪落地后的目标结构（当前尚未进入仓库）
├── yudao-dependencies/            # 依赖 BOM（基座）
├── yudao-framework/               # 框架 starters（基座）
├── yudao-gateway/                 # 网关（基座）
├── yudao-server/                  # 独立启动器（基座）
├── yudao-module-system/           # 系统管理（基座）
├── yudao-module-infra/            # 基础设施（基座）
├── yudao-module-bpm/              # Flowable 工作流（基座）
├── yudao-module-ai/               # 仅作 RAG 设计参考，不参与构建（基座）
└── buildcloud-module-project/     # 自研①：项目 + 合同（包内分包 com.buildcloud.project/contract）
    buildcloud-module-material/    # 自研②：材料（高并发核心）
    buildcloud-module-report/      # 自研③：异步报表
    buildcloud-module-ai/          # 自研④：RAG 知识库
```

## 快速开始

> 🚧 **当前仓库仅含文档体系，尚无可运行代码。** 一键启动能力在 W2（基座裁剪验收）后交付，届时此处更新为：克隆 → 配置模板填本地值 → `docker-compose up` 起中间件 → 起服务 → 登录管理端。

```bash
git clone https://github.com/zzzc411/buildcloud.git
cd buildcloud
# W2 后：cp docker/.env.template docker/.env  # 填本地配置（密钥不入库）
# W2 后：docker compose -f docker/middleware.yaml up -d
```

## 里程碑路线图

| 周 | 目标 | 状态 |
| --- | --- | --- |
| W0 | 仓库与文档体系初始化（本阶段） | ✅ 完成（2026-11-24） |
| W1 | Docker 编排中间件 + 基座跑通 + 读码地图 | ⬜ |
| W2 | 基座裁剪 + 4 个自研模块骨架 + 代码生成器四件套 | ⬜ |
| W3 | 项目域 + 合同域 + 立项审批流 | ⬜ |
| W4 | 材料域 V1（同步链路）+ 申领审批闭环 | ⬜ |
| W5 | 高并发改造 V2（Lua 预扣 / 事务消息 / 延迟关单 / 对账） | ⬜ |
| W6 | Seata + 分库分表 + 异步报表 + 首轮压测 | ⬜ |
| W7 | AI 模块（RAG / SSE / 引用溯源 / 动态 LLM 配置） | ⬜ |
| W8 | 终版压测 + 云服务器部署上线 + 文档收官 | ⬜ |

每周验收标准见 spec §9；实时状态见 [docs/plans/00-overview.md](docs/plans/00-overview.md)。

## 许可证

本项目基于 [yudao-cloud](https://github.com/YunaiV/yudao-cloud)（MIT）二次开发，遵循 MIT 协议（W2 引入基座代码时随附 LICENSE 文件并注明来源）。
