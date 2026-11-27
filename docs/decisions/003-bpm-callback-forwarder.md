# ADR-003: BPM 审批结果跨服务回调转发器

- 日期：2026-11-27
- 状态：接受
- 关联：spec v1.2 §5.3 / ADR-001 / plan-w3-project-contract Phase 4 / 读码笔记 `docs/architecture/reading-notes-bpm-integration.md` §3.5、§5.2

## 背景

筑云采用「4 基座 + 4 自研」微服务拓扑（spec §3.2），审批引擎在 `bpm-server`（48083）进程内，
业务单据在独立进程 `buildcloud-project-server`（48084）。基座的审批结果回流链路是：

```
Flowable 引擎事件（PROCESS_COMPLETED / PROCESS_CANCELLED）
  → BpmProcessInstanceEventListener（bpm-server 内）
  → BpmProcessInstanceServiceImpl#processProcessInstanceCompleted
  → BpmProcessInstanceEventPublisher#sendProcessInstanceResultEvent
  → Spring ApplicationEvent（BpmProcessInstanceStatusEvent）
```

`ApplicationEvent` **只在发布它的 JVM 内传播**。因此跨服务场景下必须由 bpm-server 这个 JVM 里的
一个监听器把事件 HTTP 转发到业务服务——基座自己就是这么做的：
`yudao-module-bpm-server/.../api/event/` 下已有 16 个转发监听器，逐个覆盖 OA 与 CRM 的每个流程 key
（`oa_leave_apply`、`crm_contract` 等），并在 `framework/rpc/config/RpcConfiguration` 里用
`@Bean @ConditionalOnMissingBean` 逐 key 注册。

**约束**：这套转发器是「每个业务流程 key 一个类」的硬编码结构，未提供通用配置化转发
（基座没有「按 key 查 URL 表」的实现）。所以新增一条业务流程，就必须在基座侧新增一个转发监听器。
这使本项目 W3-P4 的 `project-approve` 流程**无法零改动复用基座**——W3-P4 worker 实测确认：
流程可正常发起、可正常审批结束、bpm 侧 Spring 事件也正常发布，但 `buildcloud-project-server`
收不到任何回调，`bc_project.approval_status` 停在 1（审批中）不动。

这正是读码笔记 §5.2 预先标注的「**唯一需要动基座代码的点**」，AGENTS.md R-2 允许在记 ADR 的前提下
做这种必要改动。

## 决策

在 `yudao-module-bpm-server` 做**两处最小改动**，不改任何既有逻辑：

1. **新增** `cn.iocoder.yudao.module.bpm.api.event.ProjectApproveStatusListener`
   —— 结构与注释风格照抄基座同类 `OaLeaveApplyStatusListener`，只替换两处内容：
   - `getProcessDefinitionKey()` 返回 `"project-approve"`；
   - 回调 URL 为
     `http://buildcloud-project-server/rpc-api/buildcloud-project/project/update-audit-status`。
2. **在** `framework/rpc/config/RpcConfiguration` **追加**一个
   `@Bean @ConditionalOnMissingBean(name = "projectApproveStatusListener")`，写法和该文件里
   OA/CRM 的 16 个 bean 完全一致（不修改、不删除任何既有行）。

业务侧（`buildcloud-module-project-server`）按基座双模式实现接收端：
`ProjectApprovalListener extends BpmProcessInstanceStatusEventListener` 且同时是 `@RestController`，
单体部署收 Spring 事件、微服务部署收 HTTP POST，一套代码两种形态——与基座 `OaLeaveApplyStatusListener`
的业务侧写法一一对应。

## 理由与替代方案

### 选择 push（bpm 主动转发）

1. **与基座完全一致**：OA/CRM 的 15 条业务流程都是这个模式；W4（进度款）、W5（材料申领）可直接
   照抄本项目这次的改动，不需要二次设计；
2. **响应实时**：审批人点「通过」的瞬间业务表状态就更新，审批中心与项目台账不会短时不一致；
3. **前端体验**：业务页面刷新即见最新审批状态，无需等待轮询窗口；
4. **面试可讲**：用的是框架预留扩展点（`@ConditionalOnMissingBean` 的单体/微服务双形态设计），
   而不是绕过框架另起一套机制——这本身是「读码能力 + 尊重既有架构」的证据；
5. **改动面可控**：新增 1 个文件 + 追加 4 行注册代码，不触碰转发链路本身的任何实现。

### 放弃的替代方案 B：改为 pull 模式（完全不改基座）

**做法**：`buildcloud-project-server` 侧不接收回调，改为①定时任务轮询所有
`approval_status = 1` 的项目，调 `BpmProcessInstanceApi` / bpm 的查询接口拉取实例状态并回写；
或②读时懒更新——打开项目详情页时先查一次 bpm 的实例状态，若已结束则顺手回写业务表。

**为什么本次不选**：

| 维度 | push（本次） | pull（备选） |
| --- | --- | --- |
| 响应实时性 | 秒级（审批结束即回调） | 取决于轮询周期（分钟级），或用户必须打开页面才更新 |
| 基座改动 | 1 文件 + 4 行 | 0 |
| 与基座一致性 | 一致（OA/CRM 同模式） | 自创轮询机制，基座无此模式 |
| 实现成本 | 低（照抄现成类） | 中（要写定时任务 + 分页扫描 + 状态幂等 + 与 MQ/调度 starter 的集成） |
| 已知风险 | 上游同步冲突（见「影响」） | 轮询扫描全表（项目量大时是隐性负载）；懒更新在无人访问时状态长期不更新，报表口径失真 |
| 附加能力 | 无 | 需额外解决「谁来触发」「错过窗口怎么办」 |

其中「懒更新在无人访问时状态不更新」是本次不选 pull 的决定性理由：项目台账/报表域（W4 起）
会按项目状态做统计，状态延迟会直接污染报表口径，而 R-6 要求报表数字必须可信。

**保留为降级预案**：若上游 master-jdk17 同步时该改动被判定不可接受（例如团队决定基座零改动），
pull 模式可在不改变业务侧接口的前提下实现——`ProjectApprovalService#handleApprovalResult` 的入参与
幂等逻辑（按 `processInstanceId` 校验 + 终态短路）对 push/pull 两种触发方式完全通用，
只需把「谁调它」从 HTTP 入口换成定时任务即可，业务层零改动。

### 其他已考虑但不采用的方案

- **在业务服务里直接连 Flowable / 读 ACT_\* 表**：违背读码笔记 §1 的形态 B（业务不感知引擎），
  且两个服务争抢同一批 ACT_ 表，事务与租户上下文都会出问题；
- **用 MQ（RocketMQ）广播代替 HTTP 转发**：spec §5.3 提到 MQ 广播，但基座现状是 Spring 事件 + HTTP
  转发 + 站内信，**不含 MQ**（读码笔记 §5.4 已记录此差异）。引入 MQ 需要额外中间件链路、
  消息幂等表与 ADR，超出 W3 范围；且即使上了 MQ，**仍需要 bpm-server 这个 JVM 内的代码把 Spring
  事件投递到 MQ**——基座改动一点都省不掉，只是把 HTTP 换成 MQ，收益不抵成本；
- **改基座 `RpcConfiguration` 成配置化转发（读 URL 表）**：这是真正优雅的解法，但属于基座架构改造
  （影响 16 个既有监听器），远超本项目范围，且会让上游同步代价极大。可作为向上游提 PR 的建议。

## 影响

1. **上游 master-jdk17 同步时**：
   - `ProjectApproveStatusListener.java` 是**新增文件**，同步不会冲突（但同步后要确认文件仍在，
     `git checkout` 到旧分支再切回可能丢失）；
   - `RpcConfiguration.java` 会**产生冲突**（上游该文件持续新增 OA/CRM 监听器 bean）。
     冲突解决方式：保留上游全部既有 bean，把我们那一个 `projectApproveStatusListener` bean 追加在末尾即可，
     机制本身就靠 `@ConditionalOnMissingBean` 隔离，与其它 bean 无耦合。
2. **W4/W5 复用**：`payment-approve`（进度款）、`requisition-approve`（材料申领）各需在基座侧再加一个
   同构转发监听器 + 一个 bean。已评估：这是线性增长，不是重复设计，且每次改动都是纯新增。
   三条流程齐备后建议一并向上游提 PR。
3. **配置耦合点**：回调 URL 硬编码在 bpm-server 侧
   （`http://buildcloud-project-server/rpc-api/buildcloud-project/project/update-audit-status`），
   与业务侧 `ApiConstants.PREFIX` + `CALLBACK_PATH_UPDATE_AUDIT_STATUS` 必须一致。
   服务名 `buildcloud-project-server` 必须与 `spring.application.name` 一致，由 Nacos 服务发现解析。
4. **文档**：本 ADR 登记于 `docs/decisions/000-template.md` 索引；`lessons-learned.md` 追加
   L2-12（BPM 跨服务回调必须补转发器）；plan-w3 进度日志记录。
5. **测试**：业务侧 15 个单测覆盖提交/通过/驳回/幂等/非法状态；端到端两条路径（通过/驳回）实测通过，
   证据见 plan-w3 进度日志。
