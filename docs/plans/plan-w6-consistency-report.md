# 开发计划：plan-w6-consistency-report（一致性 + 分库分表 + 报表 + 首轮压测）

| 项 | 内容 |
| --- | --- |
| 计划编号 | plan-w6-consistency-report |
| 对应 spec 章节 | §6.3（分库分表）、§6.4（Seata AT）、§6.5/§5.4（异步报表）、§8（压测）、§9 W6 |
| 状态 | 未开始 |
| 计划开始 | 2027-01-04（周一） |
| 计划完成 | 2027-01-10（周末，打 tag `w6`） |
| 前置依赖 | plan-w5-concurrent-v2（✅ V2 链路就绪）；plan-sp-demo-seeding（**百万流水部分必须在 Phase 5 压测前完成**） |
| 分支约定 | `feature/w6-consistency`（分片）/ `feature/w6-report`（报表，可并行分支） |

## 1. 目标（一句话）

Seata AT 跨服务场景 + ShardingSphere 4库8表（单机多 schema）+ 异步导出与汇总看板全部落地，JMeter 首轮压测（V1/V2 对照）出具初版报告——**V2 优于 V1 有数据支撑，0 超卖断言通过**。

## 2. Phase 清单（Phase 1/2/3 文件集不相交，可 ∥ 并行；Phase 5 汇总压测最后）

### Phase 1：Seata AT 跨服务场景

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 场景：审批通过回调链路——material 出库扣减 + project 项目成本归集更新（跨服务）；集成 Seata AT；演示 undo_log 与回滚；本地消息表 + 对账 Job 兜底说明（取舍写文档） |
| 交付物 | Seata 场景可用 + 回滚演示记录 + `docs/architecture/design-seata.md` |
| 验收标准 | [ ] 跨服务写入同成败（正常+故意失败回滚各一单测）；[ ] undo_log 可观测（截图/日志留档）；[ ] 设计文档登记 |
| 上下文包（必读） | spec §6.4；基座 Seata 集成（只读）；W3/W4 的跨服务调用点 |
| 状态 | ☐ |

### Phase 2：ShardingSphere 分库分表 ∥

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | `bc_stock_log` 按 project_id 哈希：逻辑 4 库（单机 MySQL 多 schema `pm_log_0..3`）× 每库 8 表 = 32 分片；分片配置、雪花 ID（ASSIGN_ID）；迁移脚本（存量按 project_id 重分布）+ 校验对账脚本；全局查询取舍落地（必须带项目条件 + 分片并行聚合） |
| 交付物 | 分片生效 + 迁移/校验脚本 + `docs/architecture/design-sharding.md`（含迁移四步与回滚预案） |
| 验收标准 | [ ] 路由正确（单测：不同 project_id 落不同 schema/table）；[ ] 造数 100 万流水分布均匀（验证脚本输出）；[ ] 查询必须带分片键（无键查询被拒绝或显式聚合） |
| 上下文包（必读） | spec §6.3 全文、D18；sp-demo-seeding 产出（造数）；Phase（W5）流水写入点 |
| 状态 | ☐ |

### Phase 3：异步导出 + 汇总看板 ∥

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | `bc_report_job` 任务表 + 状态机；XXL-Job 分片广播按项目分片查询 → EasyExcel 流式写 → MinIO 上传 → 站内信通知；失败重试与幂等（分片断点）；三类汇总查询（项目成本/材料消耗/收付款）+ 看板聚合接口 |
| 交付物 | report 模块功能 + `docs/architecture/design-async-report.md` |
| 验收标准 | [ ] 百万流水导出不 OOM（内存监控留档）；[ ] 失败重试幂等；[ ] 三类汇总数字与明细一致（对账单测） |
| 上下文包（必读） | spec §5.4/§6.5；基座 XXL-Job/MinIO/站内信接入（只读）；sp-demo-seeding 数据 |
| 状态 | ☐ |

### Phase 4：专项设计文档定稿（三份）

| 项 | 内容 |
| --- | --- |
| 工作量 | 1d |
| 任务 | 高并发链路（W5 产出整理成 `design-concurrent.md`）+ 分片 + 异步报表 + Seata 四份设计文档定稿（面试弹药格式：背景→方案→替代方案→线上怎么办） |
| 交付物 | `docs/architecture/design-{concurrent,sharding,async-report,seata}.md` 定稿 |
| 验收标准 | [ ] 每份含「替代方案与取舍」章节；[ ] 数字引用全部指向 `docs/testing/`（R-6 红线） |
| 上下文包（必读） | spec §6 全部；W5 冒烟报告；Phase 1-3 产出 |
| 状态 | ☐ |

### Phase 5：JMeter 首轮压测（V1/V2 对照）

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | 按 spec §8.2 矩阵执行：申领 V1 / V2 各 100→500→1000 阶梯 5min；物料目录 1000；混合 80/20；异步导出 10 并发大任务；环境记录（CPU/内存/JDK/容器限额）+ 0 超卖断言 + Redis 命中率 + MQ 堆积 |
| 交付物 | `docs/testing/perf-report-first-round.md`（初版报告，全场景）+ JMeter 脚本入库 |
| 验收标准 | [ ] V2 QPS/P99 优于 V1 有数据支撑（spec §1.5 目标值以实测为准）；[ ] **0 超卖断言通过**；[ ] 环境记录完整（简历数字唯一出处） |
| 上下文包（必读） | spec §8 全部、§2.3；sp-demo-seeding（数据就绪确认）；Phase 2 分片 + W5 全链路 |
| 状态 | ☐ |

## 3. 计划级验收标准（出口条件，来自 spec §9 W6）

- [ ] V2 优于 V1 有数据支撑（首轮压测报告出具）；
- [ ] 分片数据分布与路由正确（验证脚本输出留档）；
- [ ] 百万流水导出不 OOM；
- [ ] 专项设计文档 4 份 + 压测初版报告交付；
- [ ] tag `w6` + 状态同步 + 进度日志收尾。

## 4. 涉及范围（改动声明）

- 代码模块：material（分片路由）、report（新功能）、project（Seata 场景）、网关（压测准备）；
- 数据库/SQL：`bc_report_job` 建表、4 schema 分片 DDL、迁移脚本；
- 配置/Nacos：ShardingSphere、Seata、导出任务参数；
- 文档：设计文档 4 份、压测初版报告、JMeter 脚本。

## 5. 风险与回退

| 风险 | 对策 |
| --- | --- |
| 分片配置与基座动态数据源冲突 | 先单模块集成验证；冲突则记 ADR 决定接入方式（spec §9 裁剪预案：分片可退化为单表+索引+归档，设计文档保留） |
| 压测环境不稳 | 环境快照（容器限额固定）+ 分批跑场景；数据用造数脚本可重灌 |
| Seata 与 RocketMQ 事务消息叠加混乱 | 场景隔离：Seata 只做审批回调同步链路，异步链路维持事务消息（spec §6.4 取舍已定） |

## 6. 进度日志（追加式，一行/次）

- 2026-11-24：计划创建（自 spec §9 W6 / §6.3-6.5 / §8 拆分；Phase 1/2/3 可并行）。
