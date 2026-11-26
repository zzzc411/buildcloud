# 开发计划：plan-w3-project-contract（项目域 + 合同域）

| 项 | 内容 |
| --- | --- |
| 计划编号 | plan-w3-project-contract |
| 对应 spec 章节 | §5.1（项目+合同域设计）、§9 W3 |
| 状态 | 进行中（P1 ✅ / P2-P5 待做） |
| 计划开始 | 2026-12-14（周一） |
| 计划完成 | 2026-12-20（周末，打 tag `w3`） |
| 前置依赖 | plan-w2-repo-skeleton（✅ 骨架 + 生成器就绪） |
| 分支约定 | `feature/w3-domain` |

## 1. 目标（一句话）

项目域（3 表）+ 合同域（4 表）在 `buildcloud-module-project` 内可用：CRUD、数据权限、状态机、立项与进度款两条 Flowable 审批流跑通，台账/对账页面可用，ER 图初版定稿。

## 2. Phase 清单（Phase 2/3 无文件交集，可 ∥ 并行）

### Phase 1：ER 图初版 + 建表 SQL

| 项 | 内容 |
| --- | --- |
| 工作量 | 1d |
| 任务 | `bc_project / bc_project_milestone / bc_project_member` 与 `bc_contract / bc_contract_payment_plan / bc_contract_payment_record / bc_contract_change` 全字段设计（BaseDO 六字段沿用）；Mermaid ER 图；`sql/buildcloud/{project,contract}.sql`（可重复执行）；错误码段位注册（project 1-100 / contract 1-101） |
| 交付物 | `docs/architecture/er-diagram-v0.md` + 两份 SQL + 错误码常量骨架 |
| 验收标准 | [x] 7 表字段覆盖 spec §5.1 全部实体属性（逐项对照 16/6/3/7/5/4/5 全齐）；[x] SQL 幂等可执行（01/03 各连跑 4+2 次 exit=0，表数恒 7）；[x] ER 图登记入索引（279 行，66 列程序化比对完全对齐）；[x] 附加：错误码 contract 1-101 段位 14 条零重叠；中文 HEX 校验 E6B58BE8AF95E59088E5908C 通过 |
| 上下文包（必读） | spec §5.1、§7.1/§7.2；基 BaseDO/错误码规范（读码笔记）；`docs/ops/lessons-learned.md` L2-10（字符集） |
| 状态 | ✅（2026-11-27） |

### Phase 2：项目子域 CRUD + 数据权限 ∥

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | 生成器产出 `com.buildcloud.project.*` 各表四件套并精修：台账查询（分页+多条件）、里程碑维护、成员维护、状态机（筹备→在建⇄停工→竣工）；数据权限=部门（基座）+ 项目成员白名单双维度 |
| 交付物 | 项目子域可用功能（API+前端页面接入菜单树）+ 单测（状态机流转） |
| 验收标准 | [ ] 台账查询分页多条件正确；[ ] 状态机非法流转被拒（单测）；[ ] 数据权限双维度生效（不同角色用户看到项目集合不同，手测脚本留档） |
| 上下文包（必读） | spec §5.1 项目子域；Phase 1 SQL；基座数据权限实现（读码笔记 + 源码只读）；生成器四件套示例（W2） |
| 状态 | ☐ |

### Phase 3：合同子域 CRUD ∥

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | `com.buildcloud.contract.*`：合同登记、计划编制、变更登记、台账对账（计划 vs 实际差异）；合同状态机（草稿→审批中→履行中→已结算，旁路作废） |
| 交付物 | 合同子域可用功能 + 单测（状态机） |
| 验收标准 | [ ] 登记到台账对账闭环可手演；[ ] 状态机单测通过；[ ] 与项目子域仅通过 projectId 关联（分包边界未破） |
| 上下文包（必读） | spec §5.1 合同子域；Phase 1 SQL；W2 生成器示例 |
| 状态 | ☐ |

### Phase 4：Flowable 立项审批流

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 流程定义 `project-approve`：提交→审批→通过回写项目状态「在建」；驳回→退回筹备；复刻基座 OA 请假集成模式（读码笔记 ③ 的清单）；审批结果 MQ 广播 + 站内信 |
| 交付物 | 立项审批全流程可用 + 集成单测 |
| 验收标准 | [ ] 提交-通过-驳回三条路径状态正确；[ ] 站内信到达；[ ] 与 BPM 集成模式与基座一致（无自创轮子） |
| 上下文包（必读） | spec §5.3；`reading-notes-bpm-integration.md`（W1）全文 + 基座 OA 请假源码（只读） |
| 状态 | ☐ |

### Phase 5：进度款审批 + 对账页面收口

| 项 | 内容 |
| --- | --- |
| 工作量 | 1d |
| 任务 | 流程定义 `payment-approve`：进度款申请→审批→通过生成 payment_record 并回写合同累计收付；台账对账页面联动（计划 vs 实际差异展示）；菜单/前端页面精修 |
| 交付物 | 进度款审批闭环 + 对账页面 |
| 验收标准 | [ ] 进度款通过后累计收付数字正确（单测+手演）；[ ] 对账页面展示差异列 |
| 上下文包（必读） | spec §5.1 合同用例；Phase 4 的 BPM 集成模式（复用） |
| 状态 | ☐ |

## 3. 计划级验收标准（出口条件，来自 spec §9 W3）

- [ ] 立项审批全流程演示通过（含驳回路径）；
- [ ] 台账/对账页面可用；
- [ ] 两域功能 + ER 图初版交付；
- [ ] tag `w3` + 状态同步 + 进度日志收尾。

## 4. 涉及范围（改动声明）

- 代码模块：`buildcloud-module-project`（两个子包）；
- 数据库/SQL：`sql/buildcloud/project.sql`、`contract.sql` + 菜单 SQL；
- 配置/Nacos：Flowable 流程定义（`project-approve`、`payment-approve`）；
- 文档：ER 图 v0、手测脚本。

## 5. 风险与回退

| 风险 | 对策 |
| --- | --- |
| Flowable 集成踩坑（R4） | 严格复刻基座模式；卡壳超 1d 记 ADR 决定是否简化（如先驳回调） |
| 数据权限双维度叠加复杂 | 先部门维度，成员白名单二阶段（进度日志记录取舍） |

## 6. 进度日志（追加式，一行/次）

- 2026-11-24：计划创建（自 spec §9 W3 / §5.1 拆分；Phase 2/3 可并行）。
- 2026-11-27：**P1 完成** — 项目域 3 表 + 合同域 4 表 SQL 与 ER 图落地：01-project.sql 扩为 3 表（bc_project 经核对已完整覆盖 spec §5.1 全部 14 项属性，未建多余 al-ter）、新建 03-contract.sql（4 表）、docs/architecture/er-diagram-v0.md（Mermaid 7 实体 + 字段摘要 + 8 条设计取舍：approval_no 存字符串不建 BPM 外键、change_amount 允许负数、不建物理外键、唯一键必带 tenant_id）、contract ErrorCodeConstants（1-101 段位 14 条，与基座及 project 段位零重叠）；bc-mysql 实测各脚本多次执行 exit=0 且表数恒 7，中文 HEX 校验通过，ER 图与库表 66 列程序化比对完全对齐，mvn install BUILD SUCCESS。遗留：H2 单测 create_tables.sql 待 P2 补齐；基座 ServiceErrorCodeRange 注释补五段位待 lead 决定（R-2）。下一步：P2/P3 生成器四件套（可 ∥ 并行）。
