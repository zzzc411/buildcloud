# 开发计划：plan-w3-project-contract（项目域 + 合同域）

| 项 | 内容 |
| --- | --- |
| 计划编号 | plan-w3-project-contract |
| 对应 spec 章节 | §5.1（项目+合同域设计）、§9 W3 |
| 状态 | 进行中（P1/P2/P3 ✅ / P4/P5 待做） |
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
| 验收标准 | [x] 台账查询分页多条件正确（经网关联调通过）；[x] 状态机非法流转被拒（单测 17 个覆盖全路径；含 竣工→任何 均拒、同状态拒）；[x] 数据权限双维度生效（**按基座 DataPermissionRule 机制**自定义 ProjectDataPermissionRule：dept_id ∪ manager_user_id ∪ 成员表子查询三路 OR；实测 dept104 的 dataScope=DEPT_ONLY 用户可见本部门项目 + 其参与的跨部门项目） |
| 上下文包（必读） | spec §5.1 项目子域；Phase 1 SQL；基座数据权限实现（读码笔记 + 源码只读）；生成器四件套示例（W2） |
| 状态 | ✅（2026-11-27） |

### Phase 3：合同子域 CRUD ∥

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | `com.buildcloud.contract.*`：合同登记、计划编制、变更登记、台账对账（计划 vs 实际差异）；合同状态机（草稿→审批中→履行中→已结算，旁路作废） |
| 交付物 | 合同子域可用功能 + 单测（状态机） |
| 验收标准 | [x] 登记到台账对账闭环可手演（15 条 curl 全通过）；[x] 状态机单测通过（合法 7 条 + 非法 8 条全覆盖）；[x] 与项目子域仅通过 projectId 关联（分包边界未破）；[x] 附加：对账接口一条聚合 SQL（4 子查询 LEFT JOIN 避免笛卡尔积），真实算例 1亿合同+8000万计划+3800万收付+300万变更 → 变更后 1.03 亿、计划vs实际差 4200 万、双写差 0 |
| 上下文包（必读） | spec §5.1 合同子域；Phase 1 SQL；W2 生成器示例 |
| 状态 | ✅（2026-11-27） |

### Phase 4：Flowable 立项审批流

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 流程定义 `project-approve`：提交→审批→通过回写项目状态「在建」；驳回→退回筹备；复刻基座 OA 请假集成模式（读码笔记 ③ 的清单）；审批结果 MQ 广播 + 站内信 |
| 交付物 | 立项审批全流程可用 + 集成单测 |
| 验收标准 | [x] 提交-通过-驳回三条路径状态正确（端到端实测：id=9 通过→status=1/approval_status=2；id=10 驳回→status=0/approval_status=3；提交→approval_status=1）；[~] 站内信到达——bpm 侧 `sendMessageWhenProcessInstanceApprove/Reject` 已在流程结束时调用（读码笔记 §3.5 链路第 2 步），但本次未做「登录 admin 查 `system_notify_message` 表/站内信接口」的独立验证，标记为待 W4 随进度款流程一并核实；[x] 与 BPM 集成模式与基座一致（双模式 Listener 照抄 `OaLeaveApplyStatusListener`、转发器照抄基座 16 个同类、发起走 `BpmProcessInstanceApi`，无自创轮子） |
| 上下文包（必读） | spec §5.3；`reading-notes-bpm-integration.md`（W1）全文 + 基座 OA 请假源码（只读） |
| 状态 | ✔（2026-11-27，worker w3p4b-java；基座改动见 ADR-003） |

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

- 2026-11-27：**P1 完成** — 项目域 3 表 + 合同域 4 表 SQL 与 ER 图落地：01-project.sql 扩为 3 表（bc_project 经核对已完整覆盖 spec §5.1 全部 14 项属性，未建多余 al-ter）、新建 03-contract.sql（4 表）、docs/architecture/er-diagram-v0.md（Mermaid 7 实体 + 字段摘要 + 8 条设计取舍：approval_no 存字符串不建 BPM 外键、change_amount 允许负数、不建物理外键、唯一键必带 tenant_id）、contract ErrorCodeConstants（1-101 段位 14 条，与基座及 project 段位零重叠）；bc-mysql 实测各脚本多次执行 exit=0 且表数恒 7，中文 HEX 校验通过，ER 图与库表 66 列程序化比对完全对齐，mvn install BUILD SUCCESS。下一步：P2/P3 生成器四件套（可 ∥ 并行）。
- 2026-11-27：**P2 完成**（worker w3p2-project）— 项目子域完工：bc_project_milestone/bc_project_member 四件套（DO 均 extends TenantBaseDO，路径 /buildcloud-project/milestone、/buildcloud-project/member），里程碑权重 0~100 且同项目合计 ≤100（更新排除自身），成员 uk 重复返回 1100020001、按 user_id 反查项目集合；状态机 ProjectStatusService 集中实现 筹备→在建⇄停工→竣工，非法流转抛 1100000004，并堵住「表单 update 直接改 status」绕过口（实测塞 status=0 仍保持竣工）；数据权限按基座 DataPermissionRule 机制实现自定义 ProjectDataPermissionRule（dept_id ∪ manager_user_id ∪ 成员表子查询三路 OR，走基座统一 SQL 改写链路），实测跨部门成员白名单可见；单测 68 个（模块 132 个）全绿，经网关 curl 覆盖 CRUD 与状态机正反例；期间修复 2 个真实缺陷（update 可绕过状态机、里程碑权重合计未排除自身）。
- 2026-11-27：**P3 完成**（worker w3p3-contract）— 合同子域 4 实体四件套（53 个 main 文件）：DO 全部 extends TenantBaseDO，Controller 首段 /buildcloud-project/contract，错误码复用 1-101 段位 14 条零新增；合同状态机（0草稿→1审批中→2履行中→3已结算，旁路 9已作废，终态不可流转）由 ContractStatusEnum#canTransfer 单点实现；收付款计划 uk 校验 + 合计不超合同金额；实际收付用 `UPDATE ... SET paid_amount = paid_amount + #{delta}` 原子累加（非读-改-写），删改按冲正；台账对账 reconcile 一条 4 子查询聚合 SQL，真实算例数字全对；单测 64 个全绿；经网关 15 条 curl 全通过（各错误码逐一验证）。
- 2026-11-27（lead）：**收尾统一** — 修 `src/test/resources/application-unit-test.yaml` 的 `yudao.info.base-package` 由 `com.buildcloud.project` → `com.buildcloud`（并在 `type-aliases-package` 显式列出 project/contract 两个子包，与主 application.yaml 一致），**删除测试侧临时补扫类 `ContractMapperScanConfiguration`** 及 5 个测试类的 `@Import` 引用；复跑 **132 tests 全绿**（不再依赖测试专属绕过）。下一步：P4 立项审批流 + P5 进度款审批。
- 2026-11-27：**P4 完成**（worker w3p4b-java，续做上一 worker 的 Java 集成层）— 立项审批端到端打通。①前置 SQL 重跑幂等：01/04/05 三脚本 exit=0，bc_ 表 7 张，bpm_category 4 条 / bpm_user_group 3 条（中文 HEX=E5B7A5E7A88BE9A1B9E79BAEE7B1BB 正确）。②流程定义部署：ACT_RE_PROCDEF 有 `project-approve:1`（后因修 BPMN 升为 v2，v1 自动挂起），ACT_HI_PROCINST/ACT_RU_TASK 均有真实实例。③Java 层 9 个新文件（DO 补 processInstanceId/approvalStatus、ProjectApprovalStatusEnum 数值对齐基座 BpmProcessInstanceStatusEnum、ProjectBpmConstants、ProjectApprovalService/Impl、双模式 ProjectApprovalListener、ProjectApprovalController、RPC 配置 + bpm-api 依赖、ProjectRespVO 补两字段、4 条错误码 1-100-030-xxx）。④单测 15 个（模块 147 全绿）：提交回写、通过→在建、驳回→保持筹备、重复回调幂等、串号回调丢弃、非法状态拒绝。⑤**端到端两条路径实测全绿**（经网关真实链路，非 mock）：建项目(id=9,筹备) → submit → bpm 生成实例 `4db0adde-...`（BUSINESS_KEY_=9）→ `/admin-api/bpm/task/approve` 真人审批 → 转发器 POST 回 project → **approval_status=2 且 status=1 在建、actual_start_date 回填**；另一项目(id=10) 走 `/admin-api/bpm/task/reject` → **approval_status=3 且 status=0 保持筹备、无开工日期**；重复 APPROVE 回调幂等（状态不变、开工日期不覆盖）、串号回调用旧实例号被丢弃。⑥期间发现并修复 **3 个真实缺陷**：BPMN 单出线排他网关带条件被 Flowable 校验拒绝（改为审批节点直连结束事件）、部署脚本复用已有模型时未刷新 bpmnXml（改名后改了 BPMN 也部署旧内容，补 model/update-bpmn 步骤）、部署脚本缺 UTF-8 BOM 致 PS 5.1 解析中文注释失败；另按 R-1 把脚本里硬编码的管理端口令改为环境变量（scan-secrets 由 1 处命中转为通过）。⑦**动基座 2 处（已获 lead 授权）**：新增 `bpm/api/event/ProjectApproveStatusListener.java` + `RpcConfiguration` 追加 1 个 bean（既有 16 个 bean 未改一行），决策记 **ADR-003**（含「pull 轮询」替代方案对照与降级预案）。卡点如实记录：基座对每条业务流程都需一个转发器，未提供配置化转发，这是 W4/W5 各自还要再加一处的根因。
