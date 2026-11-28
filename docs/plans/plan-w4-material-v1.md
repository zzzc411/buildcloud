# 开发计划：plan-w4-material-v1（材料域 V1 + 审批闭环）

| 项 | 内容 |
| --- | --- |
| 计划编号 | plan-w4-material-v1 |
| 对应 spec 章节 | §5.2（材料域设计）、§6.1（V1 定义）、§9 W4 |
| 状态 | 进行中（P1/P2/P3/P4/P5 ✅ / P6 待做） |
| 计划开始 | 2026-12-21（周一） |
| 计划完成 | 2026-12-27（周末，打 tag `w4`） |
| 前置依赖 | plan-w3-project-contract（✅ 项目数据与 BPM 集成模式可复用） |
| 分支约定 | `feature/w4-material` |

## 1. 目标（一句话）

材料域 7 表落地，申领 **V1 同步链路**（`SELECT ... FOR UPDATE` 事务扣减）+ 申领审批流（会签/驳回）+ 出库/退料全闭环可演示，V1 可被压测，核心状态机有单测。

## 2. Phase 清单（每个 Phase = 一次子智能体派发单元）

### Phase 1：材料域 ER + 建表 SQL

| 项 | 内容 |
| --- | --- |
| 工作量 | 0.5d |
| 任务 | `bc_material_category / bc_material / bc_supplier / bc_material_stock / bc_requisition / bc_requisition_item / bc_stock_log` 建表（stock：available_qty/locked_qty/version 乐观锁；requisition：req_no 唯一索引；stock_log：biz_type/biz_no/数量±/变动后余额）；错误码段 material 1-102；ER 图并入 `er-diagram` |
| 交付物 | `sql/buildcloud/material.sql` + ER 图更新 + 错误码常量 |
| 验收标准 | [x] 7 表字段覆盖 spec §5.2（逐字段对照全齐）；[x] 唯一约束与乐观锁列就位（`uk_project_material(tenant_id,project_id,material_id)` + `version`；`uk_req_no`；`bc_stock_log` 分片键 project_id + `idx_project_create`）；[x] SQL 幂等（连跑 5 次 exit=0，表数恒 14）；[x] 附加：ER 图追加材料域（v0→v0.1，+7 实体 +6 条设计取舍）、错误码 1-102 段位 13 条、中文 HEX 校验通过 |
| 上下文包（必读） | spec §5.2、§7.2；W3 Phase 1 的 ER 规范 |
| 状态 | ✅（2026-11-27） |

### Phase 2：基础数据 CRUD（目录/物料/供应商）

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 树形目录维护、物料（编码/规格/单位）、供应商；前端页面 + 菜单接入 |
| 交付物 | 三类基础数据维护功能 + 单测 |
| 验收标准 | [x] 树形目录增删改正确（一次查全表 + 内存 O(n) 组树，`/tree` 嵌套 + `/list` 扁平；孤儿节点提升为根、叶子 children 空数组）；[x] 物料编码唯一校验（含逻辑删除行探测，见 ADR-004）；[x] 分页查询可用（name/material_no/spec 模糊 + category 筛选）；[x] 附加：单测 53 个（category 24/material 17/supplier 12）；经网关 21 条 curl 全通过；**联调修掉逻辑删除 vs 唯一索引 500 缺陷** |
| 上下文包（必读） | spec §5.2；基座树形组件参考（只读）；W2 生成器示例 |
| 状态 | ✅（2026-11-27） |

### Phase 3：入库与库存管理

| 项 | 内容 |
| --- | --- |
| 工作量 | 1d |
| 任务 | 入库：同一事务写 `bc_material_stock`（项目+物料 唯一，version 乐观锁）+ `bc_stock_log` 入库流水；库存查询 |
| 交付物 | 入库功能 + 库存流水单测（含并发乐观锁重试） |
| 验收标准 | [x] 入库后 stock 与 stock_log 余额一致（单测断言 `available_qty == SUM(quantity)`）；[x] 乐观锁冲突可重试（显式 CAS `WHERE version=#{version}`，影响行 0 → 重试 3 次退避；实测日志 `version=0 → Updates: 0` 后重试成功，总量 115=100+10+5 无丢量）；[x] 同项目同物料库存行唯一（uk 保证）；[x] 附加：单测 24 个；网关联调 inbound 100 + 50.5 → availableQty=150.5、流水 2 条 balanceAfter 递增 |
| 上下文包（必读） | spec §5.2 实体、§6.1（V1 事务边界）；Phase 1 SQL |
| 状态 | ✅（2026-11-27） |

### Phase 4：申领 V1 同步链路（压测对照组）

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 提交申领：同一事务内 `SELECT ... FOR UPDATE` 锁 stock 行 → 校验扣减 → 写 requisition(已扣减)/item + stock_log；实现申领状态机（草稿→已提交→已扣减→已出库→已完成，旁路取消/关闭/部分回退——本 Phase 只做正向+驳回取消）；**V1/V2 配置开关预留**（同一接口两实现，spec §6.1） |
| 交付物 | 申领 V1 完整实现 + 状态机单测（全流转） |
| 验收标准 | [x] 申领→扣减→流水一致（单测 30 条断言 + 真库核对）；[x] 库存不足事务回滚无脏数据（单测 + 网关实证）；[x] 状态机非法流转被拒（canTransfer 全图 21 条边钉死）；[x] V1 开关可切换（V2 占位返回 1-102-070-000，启动参数实测） |
| 上下文包（必读） | spec §5.2 状态机、§6.1 V1 定义全文；Phase 3 产出 |
| 状态 | ✅（2026-11-28） |

### Phase 5：申领审批流 + 出库/退料闭环

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 流程定义 `requisition-approve`（**含会签示例**、驳回至发起人）；审批通过→出库（状态→已出库）；驳回→回滚扣减+冲正流水；退料→部分回退；审批结果 MQ 广播 + 站内信 |
| 交付物 | 申领审批闭环（会签/驳回/出库/退料）+ 集成单测 |
| 验收标准 | [x] 会签多人全过才通过（`approvers=[1,100]` → `ACT_RU_TASK` 2 行；admin 批后剩 1 任务、业务状态仍 1；第 2 人批后才 1→2）；[x] 驳回后库存恢复+冲正流水正确（状态 9/审批 3，库存 140→200 回补，流水 `biz_type=4 quantity=+60`，净额 0）；[x] 退料部分回退余额正确（退 20 → 状态 5、库存 170、流水 `3\|20\|170`；超量退 40 报 1102050012「可退 30」；再退 30 → 状态 4、库存 200）；[x] 附加：**审批通过与出库都不动库存**（V1 提交已扣，最易错点已断言）；重复回调零副作用；单测 40 个（模块 149 全绿） |
| 上下文包（必读） | spec §5.2/§5.3；W3 Phase 4 的 BPM 集成模式（复用）；会签参考基座 BPM 示例（只读） |
| 状态 | ✅（2026-11-28） |

### Phase 6：单测补强 + 演示脚本

| 项 | 内容 |
| --- | --- |
| 工作量 | 1d |
| 任务 | 库存扣减边界（0/负数/并发竞争）、状态机全路径、退料回退数学正确性单测；W4 演示脚本（手演路径清单） |
| 交付物 | 单测套件 + `docs/testing/demo-script-w4.md` |
| 验收标准 | [ ] 申领→审批→出库手动演示通过（演示脚本走查）；[ ] V1 可被压测（接口稳定、参数就绪）；[ ] 单测全绿 |
| 上下文包（必读） | spec §8.1；Phase 3/4/5 产出 |
| 状态 | ☐ |

## 3. 计划级验收标准（出口条件，来自 spec §9 W4）

- [ ] 申领→审批→出库手动演示通过；
- [ ] V1 可被压测（对照组就绪）；
- [ ] 材料域 V1 完整闭环交付（含单测）；
- [ ] tag `w4` + 状态同步 + 进度日志收尾。

## 4. 涉及范围（改动声明）

- 代码模块：`buildcloud-module-material`（本计划唯一 Java 模块）；
- 数据库/SQL：`sql/buildcloud/material.sql` + 菜单；
- 配置/Nacos：`requisition-approve` 流程定义；
- 文档：ER 图更新、演示脚本、单测报告说明。

## 5. 风险与回退

| 风险 | 对策 |
| --- | --- |
| 会签流程配置复杂 | 参考基座示例逐步复刻；不行先单人审批+记 ADR 延后会签到 W5 |
| V1 行锁性能差 | 预期之内（对照组），不做优化（spec §6.1 明确） |

## 6. 进度日志（追加式，一行/次）

- 2026-11-27：**P1 完成** — 材料域 7 表建表 SQL（`sql/buildcloud/07-material.sql`，285 行，全 `CREATE TABLE IF NOT EXISTS`；`bc_material_stock` uk(tenant_id,project_id,material_id) + version 乐观锁；`bc_requisition` uk_req_no + timeout_time + approval_status；`bc_stock_log` 分片键 project_id + idx_project_create；数量统一 decimal(18,4)）+ ER 图追加材料域（v0→v0.1，+7 实体 + §3.9-3.14 六条设计取舍）+ 错误码 1-102 段位 13 条。MySQL 实测：连跑 5 次 exit=0 且表数恒 14；中文 HEX 正确。
- 2026-11-27：**P2 完成**（worker w4p2-basedata）— 目录/物料/供应商三套四件套（25 main + 8 test）：DO 全 extends TenantBaseDO（R-8）、路径 /buildcloud-material/{category,material,supplier}（R-9）；树形 = 一次查全表 + 内存 O(n) 组树（非递归查库），/tree 嵌套 + /list 扁平，孤儿节点提升为根；单测 53 个全绿（模块 77 含 P3）；经网关 21 条 curl 全通过。**修掉一个真 bug**：逻辑删除行仍占 uk 唯一索引 → 删后重建同编码返 500 不可自助；三套 Mapper 加原生 @Select 探测已删行 + Service 抛可读 400，补 3 个回归用例。**该问题的全仓口径已由 lead 决策为 ADR-004**（A 类 Service 校验 / B 类保留索引），W4 收尾出 `08-unique-index-realign.sql` 统一处理。
- 2026-11-27：**P3 完成**（worker w4p3-stock）— 库存与流水（13 main + 2 test）：入库在同一事务内 upsert stock + 写 stock_log（biz_type=1、quantity 正、balance_after=更新后余额）；**乐观锁用作用域最小的显式 CAS**（`UPDATE ... WHERE id=? AND version=?`，影响行 0 判冲突，重试 3 次退避）——基座未注册 OptimisticLockerInnerInterceptor，注册它会改基座且影响全模块 update（R-2），故选显式 CAS；单测 24 个，实测冲突重试 `version=0→Updates:0` 后成功、总量 115 无丢量；一致性 `available_qty == SUM(quantity)` 真库复核 CONSISTENT；网关联调 inbound(100)+inbound(50.5) → availableQty=150.5、流水 2 条 balanceAfter 100→150.5。下一步：P4 申领 V1 同步链路（压测对照组）。
- 2026-11-28：**P4 完成**（worker w4p4-requisition）— **申领 V1 同步链路（W5 压测对照组）**：16 个新文件（状态机枚举 3 个 + DO 2 + Mapper 2 + Service + 策略模式 4 个 + Controller + VO 3），单测 32 个（模块 109 全绿）。**V1 故意用 `SELECT ... FOR UPDATE` 行锁同步扣减、不做 CAS 重试**——因为 spec §6.1 明确 V1 是对照组，若偷偷优化成乐观锁则 V1/V2 差异被抹平、W5 压测失效；正确性由行锁保证，`WHERE available_qty >= qty` 留作第二道保险，`version` 仅可观测。**V1/V2 用策略模式**（`RequisitionSubmitHandler` 接口 + V1/V2 两实现 + Factory 按 `buildcloud.material.requisition-mode` 分发），V2 抛 1102070000 零副作用。**并发单测**：库存 50、10 线程各申领 10 → 成功 5/失败 5、stock=0 无超卖、流水 5 条 SUM=-50；另证明**行锁拦不住「同一单扣两次」**（须靠单据状态 CAS）。**V1 基线实测**（非正式压测，仅供 W5 对照）：同热点库存行下 1/5/20 路并发吞吐 12.6/19.0/13.6 rps——**吞吐不随并发扩展甚至回落**，正是行锁串行化的实证。联调全通过（入库→建单→submit→stock 减→流水负数→不足回滚→cancel 回补+冲正 biz_type=4）。**本 Phase 沉淀 5 条新经验**（L1-1f repackage.skip 静默损坏运行中 jar、L1-1g 注解 SQL null 时间参数需 jdbcType、L3-10 只查库断言漏响应体缺陷、L3-11 时间精度需容差）。下一步：P5 申领审批流（含 bpm 转发器）+ 出库/退料。
- 2026-11-28：**P4 完成**（worker w4p4-requisition）— 申领 V1 同步链路（16 main + 2 test = 18 文件）：`RequisitionStatusEnum` 状态机（0草稿/1已提交/2已扣减/3已出库/4已完成/5部分回退/8已关闭/9已取消，`canTransfer` 单点实现 21 条边 + `canCancel` 窄口径）；`submitRequisition` 同一事务内 ①状态0→1 CAS ②逐条 `SELECT ... FOR UPDATE` 锁库存行 ③校验 available>=qty ④`available_qty -= qty`（**行锁下直接更新，无 CAS 无重试**——spec §6.1 明确 V1 是对照组，改写会毁掉 W5 基线）⑤`biz_type=2` 流水 quantity 负 ⑥状态1→2 CAS；取消 = **状态 CAS + 行锁回补 + biz_type=4 冲正流水**（净额归零）。**V1/V2 开关**：`buildcloud.material.requisition-mode` + 接口两实现 + 工厂，V2 抛 1-102-070-000 **不静默回退**（否则 W5 对照失效）；实测启动参数 `--buildcloud.material.requisition-mode=V2` 生效、submit 返 1102070000 且零副作用。**单测 32 个全绿**（模块 109）；并发用例实测 **10 线程各申领 10 / 库存 50 → 成功 5、失败 5、stock=0、无超卖**，另一用例「同一单 5 并发提交」→ 仅 1 成功（靠状态 CAS 而非行锁拦下）。**网关联调全通过**：入库 100 → 建单 30 → submit → stock 250.5→220.5、流水 quantity=-30 balanceAfter=220.5 biz_no=req_no → 库存不足 99999 返 `1102040001` 且**库存/流水/状态全无变化**（草稿仍 0）→ cancel → 回补 250.5 + biz_type=4 (+30)。真库终态核对：`SUM(stock_log.quantity)=2155.5 == available_qty=2155.5`。**V1 行锁实测基线（W5 对照用）**：直连 48085 同一热点库存行，20 请求 → 并发 1 路 12.6 rps / 5 路 19.0 rps / 20 路 13.6 rps（**吞吐不随并发扩展，锁排队饱和**），avg 79/53/74 ms。修掉 2 个真 bug（列表页见 L4-x）：cancel 响应体 approvalStatus 不同步、timeoutTime 两处 now() 不一致。下一步：P5 申领审批流 + 出库/退料闭环。
- 2026-11-28：**P5 完成**（worker w4p5-approval 脚手架 + w4p5b-service 续做 Service 层）— **申领审批（会签）+ 出库/退料闭环**。① **08 SQL 先跑通**（`docker cp` + 容器内重定向 + `--default-character-set=utf8mb4`，L1-1b/L2-10），连跑 2 次 exit=0 幂等，四列（`approvers`/`actual_out_time`/`actual_return_time`/`returned_qty`）+ `idx_process_instance_id` 就位。② **审批与 V1 扣减的衔接（本 Phase 最贵的口径）**：V1 的 `submit` 是「提交即扣减」；审批链路是**另一条路径**——`approval/submit` 复用 `RequisitionV1SubmitHandler` 的行锁扣减（**同一段代码，不重写**）后推进到已提交(1)，**审批通过只 1→2、绝不碰库存**（重复扣=同一单扣两遍，驳回只补一遍，账永远不平），**驳回才回补 + biz_type=4 冲正**。③ 新增 6 个文件（Service 接口/实现、Listener、Controller、3 个 VO、`framework/rpc/config/RpcConfiguration`），Mapper 加 6 条 CAS SQL（`startApprovalCas`/`updateProcessInstanceIdIfAbsent`/`finishApprovalCas`/`outboundCas`/`returnCas`/`increaseReturnedQtyReturnCheck`）。**幂等主闸门是数据库 CAS 而非内存判断**——`WHERE approval_status=1` 让两个并发回调只有一个能命中，回补只发生一次。④ **会签**：`approvers` 走流程变量 + BPMN `candidateStrategy=60`，实例在 `ACT_RU_TASK` 上**真建 2 个任务、assignee 分别是 1 和 100**；admin 批第一个后剩 1 个任务、业务状态仍 1；yudao(100) 再批 → 任务归零、回调 `status=2`、状态 1→2、**库存 150 未变**。⑤ **单测 40 个全绿**（模块 149），含「通过后库存与流水条数完全不变」「重复驳回不重复回补」「迟到回调不改终态」「退料超量整体回滚」「返回值==库值（L3-10）」等。⑥ **端到端两条路径实测**：通过路径 入库200→申领50→会签→出库(库存仍150)→退20(→5,库存170,流水`3|20|170`)→退40 **报错可退30**→退30(→4,库存200)，`SUM(quantity)=200=available_qty`；驳回路径 会签 1 批 1 驳 → 状态 9/审批 3、库存 140→**200 回补**、流水`4|60|200`、净额 0；**重放 APPROVE/REJECT 回调各一次均 HTTP 200 且零副作用**（库存仍 200、流水仍 3 条、状态仍 9/3）。⑦ **修掉 2 个真缺陷**：bpm-server `RpcConfiguration` 缺 `RequisitionApproveStatusListener` 的 import（脚手架漏了，编译即失败）；material 缺 `@EnableFeignClients(clients={BpmProcessInstanceApi.class})`（加依赖不够，启动报「required a bean」）。⑧ **新增 3 条经验 L2-15/L2-16/L2-17**（Feign 需显式 EnableFeignClients、会签中途状态要查 `ACT_RU_TASK`、`Set-Content -Encoding utf8` 加 BOM + 编码漂移炸 Java 源码）。未提交（worker 不执行 git 写操作，L3-1）。
