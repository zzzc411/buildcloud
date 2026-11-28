# 筑云 · 文档中心索引

> 本文件是 `docs/` 的唯一入口。新文档必须在此登记；未登记的文档视为不存在。

## 目录结构

```
docs/
├── 00-index.md               # 本文件：文档总入口、版本命名规范、登记表
├── spec/                     # 总规格（范围/架构/里程碑的唯一依据）
│   └── buildcloud-spec-v1.2.md
├── plans/                    # 开发计划（从 spec 按周/专项拆分）
│   ├── 00-overview.md        #   计划总览 + 依赖图 + 状态跟踪
│   ├── _template.md          #   计划模板（所有计划从这里复制，含 Phase 结构）
│   ├── _dispatch.md          #   子智能体派发指南（怎么把 Phase 派给 worker）
│   ├── plan-w{1..8}-*.md     #   周计划（W1-W8 已全部拆分）
│   └── plan-sp-*.md          #   专项计划（密钥治理/造数/面试弹药库）
├── decisions/                # ADR 决策记录（spec §11 之后的新决策）
├── architecture/             # 架构图、ER 图、读码笔记、专项设计（W1 起产出）
├── testing/                  # 压测报告、评测集与测试方案
├── ops/                      # 部署手册、中间件编排、密钥治理、云服务器上线记录
├── interview/                # 面试 Q&A 弹药库（sp-interview-qa，W5 起产出）
└── archive/                  # 历史版本归档（只进不改）
    ├── spec-v0.9-raw.md
    └── buildcloud-spec-v1.0.md
```

## 核心文档（必读顺序）

| 顺序 | 文档 | 说明 |
| --- | --- | --- |
| 1 | [spec v1.2](spec/buildcloud-spec-v1.2.md) | 项目宪法：范围、架构、里程碑、决策记录 D1-D23 |
| 2 | [plans/00-overview.md](plans/00-overview.md) | 计划清单、依赖图与进度 |
| 3 | [plans/_dispatch.md](plans/_dispatch.md) | 子智能体派发指南（整周/单 Phase 派工的操作手册） |
| 4 | [plans/_template.md](plans/_template.md) | 开发计划的标准结构（Phase 派发单元） |
| 5 | 根目录 `README.md` | 项目骨架与快速开始 |
| 6 | 根目录 `AGENTS.md` | 人与智能体共同遵守的开发约束（红线） |

## 版本与命名规范（文档迭代）

**总原则**：仓库内文档分三类，各有不同的迭代方式。

| 类别 | 典型文档 | 迭代方式 | 版本标记 |
| --- | --- | --- | --- |
| **规格类**（沉淀型，改历史即失真） | spec、架构/ER 定稿、压测报告、部署手册 | **升版本 + 归档**：新文件 `xxx-v{major}.{minor}.md`，旧版原样移入 `archive/`，文首版本表 + 文末变更记录留痕 | 文件名内 `v{major}.{minor}` |
| **计划类**（活文档，本来就是工单） | `plan-w*-*.md`、`plan-sp-*.md` | **同文件演进**：状态、任务勾选、进度日志就地更新；仅范围重大变更（如整周砍半）才另立新文件并归档原文件 | 文件头「状态」字段 + 进度日志；**不进文件名** |
| **参考类**（一次性产出或追加型） | 读码笔记、ADR、目录 README | 新建即定稿，小修就地 edit；ADR **只追加不改写**（废弃改状态为「废弃」并注明取代者） | 无版本号 |

细则：

1. **版本号语义**：`major` = 范围/里程碑/架构级变更（须用户确认，记 Dxx/ADR）；`minor` = 描述修正、路径同步、任务细化等不改承诺的变更；
2. **阶段型报告不互相覆盖**：压测报告按阶段命名（如 `perf-report-first-round.md` → `perf-report-final.md`），视为不同文档，不算版本迭代；
3. **归档文件名保持原名**（如 `buildcloud-spec-v1.0.md`）；唯一例外是未按版本命名的 v0.9 原稿（`spec-v0.9-raw.md`）；
4. **禁止**用「最终版/final2/最新/新」等字样命名；**禁止删除**任何已登记文档；被取代走归档；
5. **命名前缀**（全小写 kebab-case）：spec / plan-w{N} / plan-sp / adr-{seq} / perf-report / eval / deploy / reading-notes / design / arch- / middleware-…；日期一律 `YYYY-MM-DD`；
6. **登记即生效**：任何新建/归档/改状态动作，同步更新本文件登记表（一次提交内完成）。

## 文档规则

1. **单一事实来源**：范围/排期/里程碑只看 spec；spec 变更按上表「规格类」方式升版本，禁止原地静默修改；
2. **计划拆分**：进入某周/某专项开发前，计划文件已就位（W1-W8 已拆分，见 `plans/00-overview.md`）；后续新增计划从 `_template.md` 复制；**计划只能细化不扩权**——范围变更必须回 spec（升版本）或记 ADR；
3. **登记制**：新建文档后在本文末尾登记表追加一行（路径、日期、状态）；
4. **归档**：被取代的规格类文档移入 `archive/`，不删除；
5. **语言**：工作文档用中文；对外交付物（README、压测报告）中文为主。

## 文档登记表

| 路径 | 日期 | 状态 | 说明 |
| --- | --- | --- | --- |
| `spec/buildcloud-spec-v1.2.md` | 2026-11-25 | 生效 | 总规格 v1.2（D23：按 W1 读码实证收敛审批回调与网关 Sentinel） |
| `archive/buildcloud-spec-v1.1.md` | 2026-11-24 | 归档 | spec v1.1（被 v1.2 取代） |
| `plans/00-overview.md` | 2026-11-24 | 生效 | 计划总览 + 依赖图（W1-W8 + 3 专项全部拆分） |
| `plans/_template.md` | 2026-11-24 | 生效（v2 结构） | 计划模板（含 Phase 派发单元） |
| `plans/_dispatch.md` | 2026-11-24 | 生效 | 子智能体派发指南与提示词模板 |
| `plans/plan-w0-repo-bootstrap.md` | 2026-11-24 | 已完成 | W0：仓库与文档体系初始化（含工作区迁移同步） |
| `plans/plan-w1-infra-up.md` | 2026-11-24 | 已完成 | W1：环境与基座跑通（P1/P2/P3 全绿，tag `w1`，2026-11-25） |
| `plans/plan-w2-repo-skeleton.md` | 2026-11-24 | 已完成 | W2：基座裁剪 + 4 模块骨架 + 生成器四件套（P1-P4 全绿，tag `w2`） |
| `plans/plan-w3-project-contract.md` | 2026-11-24 | 已完成 | W3：项目域 + 合同域 + 立项/进度款审批（P1-P5 全绿，tag `w3`） |
| `plans/plan-w4-material-v1.md` | 2026-11-24 | 已完成 | W4：材料域 V1 同步链路 + 申领审批闭环（P1-P6 全绿，tag `w4`） |
| `plans/plan-w5-concurrent-v2.md` | 2026-11-24 | 进行中 | W5：高并发改造 V2（Lua/事务消息/延迟关单/对账/缓存，P1 ✅） |
| `plans/plan-w6-consistency-report.md` | 2026-11-24 | 未开始 | W6：Seata + 分库分表 + 异步报表 + 首轮压测 |
| `plans/plan-w7-ai-rag.md` | 2026-11-24 | 未开始 | W7：AI 模块（RAG/SSE/引用溯源/LLM 动态配置） |
| `plans/plan-w8-final-deploy.md` | 2026-11-24 | 未开始 | W8：终版压测 + 云服务器部署上线 + 收官 |
| `plans/plan-sp-key-management.md` | 2026-11-24 | 进行中 | 专项：密钥与敏感信息治理（P1 模板化/P2 扫描 ✅，随 W2-P2） |
| `plans/plan-sp-demo-seeding.md` | 2026-11-24 | 未开始 | 专项：造数脚本与演示数据 |
| `plans/plan-sp-interview-qa.md` | 2026-11-24 | 进行中 | 专项：面试 Q&A 弹药库（P1 ✅ v0.1；八章待 W5-W7 填充） |
| `decisions/001-rpc-mode.md` | 2026-11-25 | 生效 | ADR-001：服务间 RPC=Spring Cloud OpenFeign（W1-P3 定稿） |
| `architecture/codegen-demo-vue/` | 2026-11-26 | 生效 | 代码生成器 Vue 四件套示例（bc_project，W2-P4 精修产物） |
| `architecture/er-diagram-v0.md` | 2026-11-27 | 生效 | 项目域 + 合同域 ER 图 v0（7 表 66 列，W8 定稿） |
| `decisions/004-unique-index-with-logical-delete.md` | 2026-11-27 | 生效 | ADR-004：逻辑删除与业务唯一约束口径（A 类 Service 校验 / B 类保留索引） |
| `testing/demo-script-w4.md` | 2026-11-28 | 生效 | W4 端到端演示走查手册（申领→会签→出库→退料→对账，含 curl 与对账 SQL；**非性能报告**） |
| `decisions/003-bpm-callback-forwarder.md` | 2026-11-27 | 生效 | ADR-003：BPM 审批结果跨服务回调转发器（push，含 pull 降级预案） |
| `decisions/002-nacos-namespace.md` | 2026-11-25 | 生效 | ADR-002：本地 Nacos 命名空间改用 public（临时，W2 再评估） |
| `architecture/arch-overview-v0.md` | 2026-11-25 | 生效 | 架构图 v0（读码定稿版，含与 spec §3.1 差异标注） |
| `architecture/reading-notes-gateway.md` | 2026-11-25 | 生效 | 网关鉴权链路读码笔记 |
| `architecture/reading-notes-rpc.md` | 2026-11-25 | 生效 | 服务间 RPC 读码笔记 |
| `architecture/reading-notes-bpm-integration.md` | 2026-11-25 | 生效 | BPM 集成模式读码笔记（W3/W4 复刻清单） |
| `architecture/reading-notes-starter.md` | 2026-11-25 | 生效 | starter 体系读码笔记（自研引用建议） |
| `ops/deploy-local.md` | 2026-11-25 | 生效 | 本地部署手册 v1（中间件编排 + 服务启动章节） |
| `ops/middleware-limits.md` | 2026-11-25 | 生效 | 中间件内存限额表与分批启动建议 |
| `ops/lessons-learned.md` | 2026-11-26 | 生效（v1.0，追加式） | **实战经验与踩坑记录**（环境/基座/流程三类，W0-W2 沉淀；派发上下文包必读） |
| `interview/qa-bank.md` | 2026-11-25 | 生效 | 面试弹药库 v0.1（九章框架 + 二次开发叙事章） |
| `decisions/000-template.md` | 2026-11-24 | 生效 | ADR 模板 |
| `archive/spec-v0.9-raw.md` | 2026-11-24 | 归档 | spec v0.9 原稿 |
| `archive/buildcloud-spec-v1.0.md` | 2026-11-24 | 归档 | spec v1.0（被 v1.1 取代） |
