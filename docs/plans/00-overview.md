# 开发计划总览（依赖图 + 状态跟踪）

> 本文件跟踪从 [spec v1.2](../spec/buildcloud-spec-v1.2.md) 拆分出的全部开发计划。每个计划 = 一个可独立验收的工作包，内部按 **Phase** 切分（一次子智能体派发的最小单元）。
> 状态：`未开始 / 进行中 / 已完成 / 已裁剪 / 阻塞`。裁剪 = 触发 spec §9 超支预案。派发方式见 [_dispatch.md](_dispatch.md)。

## 1. 拆分原则

1. **一计划一出口**：每个计划的验收标准直接来自 spec 对应表格行；
2. **Phase 即派发单元**：每个 Phase 0.5d~2d、文件集封闭、出口可验证，可整周派发也可单 Phase 派发给子智能体；
3. **不扩权**：计划只细化任务，不新增范围；新增范围 = 回 spec 升版本或记 ADR（`docs/decisions/`）；
4. **依赖单向**：Phase 间、计划间依赖只指向已完成者，禁止循环。

## 2. 依赖关系图

```
spec v1.2（范围与排期唯一依据）
   │
 W0 ✅ 仓库与文档体系初始化（含 D22 工作区迁移同步）
   │
 W1 ── 中间件编排 → 基座跑通 → 读码地图        [环境就绪]
   │
 W2 ── 基座入仓 → 删15模块 → 4模块骨架 → 生成器   [仓库就绪]
   │            └──∥ sp-key-management（密钥治理，W2 周内落地）
   │
 W3 ── ER/SQL → (项目域 ∥ 合同域) → 立项审批 → 进度款审批
   │
 W4 ── ER/SQL → 基础数据 → 入库/库存 → 申领V1 → 审批闭环 → 单测
   │
 W5 ── Lua预扣 → 事务消息 → 延迟关单 → 对账Job ┐
   │        └──∥ Sentinel/布隆/两级缓存 ────────┤→ 开关联动+冒烟
   │                                            [V2 就绪]
   │
   ├── sp-demo-seeding（W5 后启动 ─┐
   │        演示数据 → 百万流水 ────┤ **硬约束：W6-P5 压测前完成**）
   │                               │
 W6 ── (Seata ∥ 分片 ∥ 异步报表) → 设计文档定稿 → 首轮压测
   │
 W7 ── pgvector数据层 → 解析向量化 → RAG问答 → 评测
   │        └──∥ LLM动态配置/限流（依赖 sp-key 的 Key 方案）
   │
 W8 ── (终版压测 ∥ 云部署 ∥ 弹药库) → 文档定稿 → 仓库终检收官
   │
 W9（缓冲，不预排）
   │
 sp-interview-qa（长线：W5 起随专项积累 → W8 定稿）
```

**关键路径**：W1 → W2 → W3 → W4 → W5 →（sp-demo-seeding）→ W6 → W8；W7 可在 W6 期间并行提前（见下）。

## 3. 并行窗口（主控派发时可利用）

| 窗口 | 可并行内容 | 前提 |
| --- | --- | --- |
| W1 周内 | 中间件编排（P1）∥ 读码地图（P3） | 文件集不相交；P3 依赖基座参考克隆 `D:\project\yudao-cloud-ref`（已就绪） |
| W2 周内 | 裁剪（P2-P4）∥ 密钥治理专项 | 文件集不相交（配置模板 vs 模块骨架） |
| W3 周内 | 项目域 CRUD ∥ 合同域 CRUD（两子包） | 分包隔离，无共享文件 |
| W5 周内 | 申领链路（P1-P4、P6）∥ 防护组件（P5） | P5 只碰网关/缓存组件 |
| **W6 ∥ W7 前半** | W6 的 Seata/分片/报表 ∥ W7 的 Phase 1/2（pgvector、文档解析） | **AI 域与报表/分片零文件交集**；W7-P3 起需串行注意力 |
| W8 周内 | 终版压测 ∥ 云部署 ∥ 弹药库 | 三线独立（P3 依赖云部署完成） |

## 4. 周计划清单

| 计划 | 周次（日期） | 前置依赖 | Phase 数 | 状态 | 完成 |
| --- | --- | --- | --- | --- | --- |
| [plan-w1-infra-up.md](plan-w1-infra-up.md) | W1（11-30 ~ 12-06） | W0 ✅ | 3 | ✅ 已完成（P1/P2/P3 全绿，tag `w1`） | — |
| [plan-w2-repo-skeleton.md](plan-w2-repo-skeleton.md) | W2（12-07 ~ 12-13） | W1；∥ sp-key | 4 | ✅ 已完成（P1-P4 全绿，tag `w2`） | 2026-11-26 |
| [plan-w3-project-contract.md](plan-w3-project-contract.md) | W3（12-14 ~ 12-20） | W2 | 5（P2∥P3） | ✅ 已完成（P1-P5 全绿，tag `w3`） | 2026-11-27 |
| [plan-w4-material-v1.md](plan-w4-material-v1.md) | W4（12-21 ~ 12-27） | W3 | 6 | 🔄 进行中（P1 ✅） | — |
| [plan-w5-concurrent-v2.md](plan-w5-concurrent-v2.md) | W5（12-28 ~ 01-03） | W4 | 6（P5∥） | 未开始 | — |
| [plan-w6-consistency-report.md](plan-w6-consistency-report.md) | W6（01-04 ~ 01-10） | W5；sp-seeding（硬） | 5（P1-P3∥） | 未开始 | — |
| [plan-w7-ai-rag.md](plan-w7-ai-rag.md) | W7（01-11 ~ 01-17） | W2 骨架；sp-key；可提前并行 | 5（P4∥） | 未开始 | — |
| [plan-w8-final-deploy.md](plan-w8-final-deploy.md) | W8（01-18 ~ 01-24） | W6；W7；sp-interview 初稿 | 5（P1/P2/P4∥） | 未开始 | — |
| W9（缓冲） | 01-25 ~ | 视偏差 | — | 不预排 | — |

> 每周日打 tag `w{N}`（spec §7.3）；日期来自 spec §9（W1 = 2026-11-30 开工）。

## 5. 专项计划清单

| 计划 | 触发时点 | 前置依赖 | 状态 | 关键约束 |
| --- | --- | --- | --- | --- |
| [plan-sp-key-management.md](plan-sp-key-management.md) | W2 周内（规范先行） | W2-P1 | 进行中（P1 模板化 ✅ / P2 扫描 ✅；注入文档待补） | 此后长期执行；W7 依赖其 Key 方案 |
| [plan-sp-demo-seeding.md](plan-sp-demo-seeding.md) | W3 后启动 | W3/W4/W5 | 未开始 | **W6-P5 压测前必须完成**（硬时间点） |
| [plan-sp-interview-qa.md](plan-sp-interview-qa.md) | W5 起长线积累 | 各专项产出 | 进行中（P1 ✅ v0.1 框架） | W8 定稿；数字只引自压测报告 |

## 6. 使用方式（主控视角）

- **整周派发**：把该周计划文件 + `_dispatch.md` 模板交给一个子智能体，令其按 Phase 顺序自主推进、逐 Phase 汇报；
- **单 Phase 派发**：按 `_dispatch.md` §4 模板组装上下文包（必读清单里已写好）派给一个 worker；
- **并行派发**：只派 §3 表中列出的并行组合，各 worker 独立分支，主控按序合并；
- 计划进行中：worker 每完成一个 Phase 在计划「进度日志」追加一行；
- 完成后：验收清单全勾 → 状态改「已完成」→ `00-overview.md` 本表与 `docs/00-index.md` 登记表同步 → tag `w{N}`。

## 7. 状态看板（周粒度，随进度更新）

| 周 | 计划 | 状态 | Phase 完成/总数 | 备注 |
| --- | --- | --- | --- | --- |
| W0 | repo-bootstrap | ✅ 已完成 | 7/7 | 2026-11-24；含 D22 迁移同步 |
| W1 | infra-up | ✅ 已完成 | 3/3 | P1/P2/P3 全绿（2026-11-25，tag `w1`）；MinIO 已切 Bitnami 归档镜像；可登录演示（2048） |
| W2 | repo-skeleton | ✅ 已完成 | 4/4 | P1 入仓 → P2 裁剪+模板化 → P3 四模块（48084-48087）→ P4 生成器四件套；44 模块编译通过；修复 .gitignore 阻断缺陷；tag `w2` |
| W3 | project-contract | ✅ 已完成 | 5/5 | P1 表/ER → P2 项目子域 → P3 合同子域 → **P4 立项审批** → **P5 进度款审批**；173 tests 全绿；两条审批链端到端真实跑通；tag `w3` |
| W4 | material-v1 | 🔄 进行中 | 1/6 | P1 ✅ 7 表 SQL（14 表）+ ER v0.1 + 错误码 1-102；P2/P3 可 ∥ |
| W5 | concurrent-v2 | ⬜ 未开始 | 0/6 | P5∥ |
| W6 | consistency-report | ⬜ 未开始 | 0/5 | P1-P3∥；依赖 sp-seeding |
| W7 | ai-rag | ⬜ 未开始 | 0/5 | 可提前并行 |
| W8 | final-deploy | ⬜ 未开始 | 0/5 | P1/P2/P4∥ |
