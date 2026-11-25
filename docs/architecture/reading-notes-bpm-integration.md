# 读码笔记 ③：BPM 集成模式（OA 请假示例全流程，供 W3/W4 复刻）

| 项 | 内容 |
| --- | --- |
| 基线 | yudao-cloud `master-jdk17`（只读参考 `D:\project\yudao-cloud-ref`），Flowable **8.0.0** |
| 读码范围 | `yudao-module-bpm`（api/server）+ `yudao-module-oa`（跨服务业务方示例，只读） |
| 读码日期 | 2026-11-25 |
| 官方文档交叉印证 | doc.iocoder.cn/bpm/（功能开启：bpm_* 表 SQL 为官方附件、Flowable 自动建 ACT_/FLW_ 表）；doc.iocoder.cn/bpm/use-business-form/（业务表单双向关联模式，与源码一致） |
| 关联 | spec §5.3（审批域）；plan-w3 / plan-w4 |

## 1. 两种接入形态（基座同时给了两套示例）

| 形态 | 示例代码位置 | 场景 |
| --- | --- | --- |
| **A. BPM 模块内置业务**（同 JVM） | `yudao-module-bpm-server/.../service/oa/BpmOALeaveServiceImpl`（流程 key `oa_leave`） | 业务表单和引擎在一个服务里 |
| **B. 独立业务服务**（跨服务，**本项目形态**） | `yudao-module-oa`（`OaLeaveApplyServiceImpl`，流程 key `oa_leave_apply`）+ bpm-server 里的 HTTP 转发监听器 | 业务服务（buildcloud-*）与 bpm-server 分进程——W3/W4 用这套 |

> 形态 A 中业务代码也走 `BpmProcessInstanceApi` 接口而非直接调引擎 Service——**统一走 API 层是基座的刻意设计**（官方文档：业务无需关心底层是 Flowable 还是 Activiti）。

## 2. 核心接口与数据模型

### 2.1 发起/撤回 API（`yudao-module-bpm-api/.../api/task/BpmProcessInstanceApi.java`，@FeignClient(name="bpm-server")）

| 方法 | URL（PREFIX=/rpc-api/bpm/process-instance） | 说明 |
| --- | --- | --- |
| `createProcessInstance(userId, BpmProcessInstanceCreateReqDTO)` | POST `/create` | 发起流程，返回 processInstanceId |
| `cancelProcessInstanceByStartUser(userId, processInstanceId, reason)` | POST `/cancel-by-start-user` | **发起人撤回**（业务服务可调） |

`BpmProcessInstanceCreateReqDTO` 四个字段：`processDefinitionKey`（必填）、`variables`（Map 流程变量）、`businessKey`（必填，**业务单据主键**）、`startUserSelectAssignees`（发起人自选审批人 Map<taskKey, List<userId>>）。

### 2.2 状态枚举（业务表 status 直接复用）

- `BpmProcessInstanceStatusEnum`（bpm-api）：NOT_START(-1) 未开始 / RUNNING(1) 审批中 / APPROVE(2) 通过 / REJECT(3) 不通过 / CANCEL(4) 已取消；
- `BpmTaskStatusEnum`（bpm-api）：SKIP(-2)/NOT_START(-1)/WAIT(0)/RUNNING(1)/APPROVE(2)/REJECT(3)/CANCEL(4)/RETURN(5)/APPROVING(7)（WAIT/APPROVING 服务加签场景）。

### 2.3 双向关联（官方文档《审批接入（业务表单）》与源码一致）

- 业务表 → 流程：业务表的 `process_instance_id` 字段（对应 `ACT_HI_PROCINST.PROC_INST_ID_`）；
- 流程 → 业务表：流程实例的 `businessKey`（`ACT_HI_PROCINST.BUSINESS_KEY_`，发起时传入业务单据 id）。

## 3. 全流程追踪（形态 B：独立业务服务，oa-server 示例）

### 3.1 发起：草稿 → 提交 → 流程实例

`OaLeaveApplyController`（`/oa/leave-apply/*`）→ `OaLeaveApplyServiceImpl`：

1. `createLeaveApply`：插业务表，`status = NOT_START`（草稿，还没进流程）；
2. `submitLeaveApply`（@Transactional）：
   - 校验可提交（本人 + 状态=NOT_START）→ 更新 `status = RUNNING`；
   - **Feign 调 `processInstanceApi.createProcessInstance(userId, new BpmProcessInstanceCreateReqDTO().setProcessDefinitionKey(BpmModelConstants.LEAVE_APPLY).setBusinessKey(id).setVariables({days}).setStartUserSelectAssignees(...))`**；
   - 回写 `process_instance_id`。

> 形态 A（BpmOALeaveServiceImpl#createLeave）是单步版：@Transactional 内插单(status=RUNNING) → 发起 → 回写，无草稿态。

### 3.2 bpm-server 侧发起内部实现（回调链的源头）

`BpmProcessInstanceApiImpl`（@RestController 实现 Feign 接口）→ `BpmProcessInstanceServiceImpl#createProcessInstance(userId, DTO)`（:887 行）：

- `FlowableUtils.executeAuthenticatedUserId(userId, ...)` 以发起人身份执行；
- `getActiveProcessDefinition(key)` 取流程定义 → `createProcessInstance0`：校验定义存在/未挂起、发起权限（canUserStartProcessDefinition）、自选审批人合法性 → 注入流程变量（`START_USER_ID`、`STATUS=RUNNING`、`SKIP_EXPRESSION_ENABLED`）→ `runtimeService.createProcessInstanceBuilder().businessKey(businessKey).variables(...).start()`；支持按 `processIdRule` 用 `BpmProcessIdRedisDAO`（Redis）预生成自定义实例号。

### 3.3 审批与驳回（bpm-server 内部）

- **通过**：`BpmTaskServiceImpl#approveTask`（:604）→ 校验任务/实例/签名/审批意见 → `updateTaskStatusAndReason(taskId, APPROVE)` → 评论/附件 → 合并流程变量 → `taskService.complete(taskId)`；
- **驳回**：`rejectTask`（:870）→ `updateTaskStatusAndReason(taskId, REJECT)` → 按 BPMN 节点的 `RejectHandler` 分流：
  - `RETURN_USER_TASK`（驳回至指定节点）→ `returnTask(...)` 退回，流程继续跑；
  - 否则（终止型驳回）→ `processInstanceService.updateProcessInstanceReject(instance, reason)`（实例变量 STATUS=REJECT）+ `moveTaskToEnd`（强制结束流程）。

### 3.4 撤回（取消）

`BpmProcessInstanceController` 提供 `DELETE /cancel-by-start-user`（发起人）与 `/cancel-by-admin`（管理员）→ `cancelProcessInstanceByStartUser`（:1004）：校验实例存在/仅本人/`allowCancelRunningProcess` 配置/非子流程 → `updateProcessInstanceCancel`：实例变量 STATUS=CANCEL + reason → 级联取消子流程 → `taskService.moveTaskToEnd`。

### 3.5 审批结果回调（**业务方拿结果的关键链路**）

```
Flowable 引擎事件（PROCESS_COMPLETED / PROCESS_CANCELLED）
  ▼
BpmProcessInstanceEventListener（framework/flowable/core/listener，AbstractFlowableEngineEventListener）
  ▼
BpmProcessInstanceServiceImpl#processProcessInstanceCompleted（:1086）
  ├─ 仍是 RUNNING 则改判 APPROVE；REJECT 保持；子流程拒绝联动父流程
  ├─ messageService.sendMessageWhenProcessInstanceApprove/Reject（站内信通知）
  └─ processInstanceEventPublisher.sendProcessInstanceResultEvent(
         BpmProcessInstanceConvert.buildProcessInstanceStatusEvent(...))
       ↓  Spring ApplicationEvent（BpmProcessInstanceEventPublisher → ApplicationEventPublisher）
  ▼
【同 JVM 监听】BpmOALeaveStatusListener（bpm-server 内 oa_leave）
【跨服务转发】OaLeaveApplyStatusListener（bpm-server api/event 包，RpcConfiguration @Bean 注册）
  → BpmHttpRequestUtils.executeBpmHttpRequest(event,
       "http://oa-server/rpc-api/oa/leave-apply/update-audit-status", loadBalancedRestTemplate)
     （POST JSON：id/processDefinitionKey/status/reason/businessKey + tenant-id Header）
  ▼
oa-server 侧 OaLeaveApplyStatusListener（service/leave/listener 包）
  —— 同一个类同时是 @RestController + extends BpmProcessInstanceStatusEventListener（双模式）：
     进程内收 Spring 事件 / 跨进程收 HTTP，都会进 onEvent
  ▼
leaveApplyService.updateLeaveApplyStatus(businessKey, status)
  → 回写业务表 status；APPROVE 时执行通过后置动作（示例：生成考勤明细）
```

> 转发监听器在 bpm-server 用 `@ConditionalOnMissingBean` 注册（RpcConfiguration :50-54）：单体部署时业务模块自己的同名监听器 Bean 优先，微服务部署时才启用 HTTP 转发版——一套代码两种部署形态。

## 4. 涉及的表（W3/W4 需要的完整清单）

### 4.1 BPM 业务配置表（8 张，DO 在 `yudao-module-bpm-server/.../dal/dataobject/`）

| 表 | DO | 用途 |
| --- | --- | --- |
| `bpm_category` | BpmCategoryDO | 流程分类 |
| `bpm_form` | BpmFormDO | 动态表单 |
| `bpm_process_definition_info` | BpmProcessDefinitionInfoDO | 流程定义扩展（表单类型/标题规则/Http 前后置触发等） |
| `bpm_process_expression` | BpmProcessExpressionDO | 流程表达式 |
| `bpm_process_listener` | BpmProcessListenerDO | 流程监听器 |
| `bpm_user_group` | BpmUserGroupDO | 用户组（审批人候选） |
| `bpm_process_instance_copy` | BpmProcessInstanceCopyDO | 流程抄送 |
| `bpm_oa_leave` | BpmOALeaveDO | OA 请假示例业务表（含 `status`、`process_instance_id`） |

### 4.2 Flowable 引擎表（自建）

`application.yaml` 配 `flowable.database-schema-update: true` → 启动自动创建 `ACT_*` / `FLW_*` 全套表（`check-process-definitions: false`，`history-level: audit`）。bpm-server 端口 **48083**。

### 4.3 ⚠️ DDL 获取方式（读码实证，影响 W3 部署）

- 基座仓库 `sql/mysql/ruoyi-vue-pro.sql` **不含任何 `bpm_*` 表**（仅 system/infra/demo 表；`bpm_` 字样只出现在字典数据 INSERT 中）；
- 仓库内仅测试用 H2 DDL（`yudao-module-bpm-server/src/test/resources/sql/create_tables.sql`），不可直接用于 MySQL 生产初始化；
- 官方文档 doc.iocoder.cn/bpm/ 明确：bpm_* 表 SQL 以官方附件 `bpm-2025-10-04.sql.zip` 提供（星球成员专有授权），Flowable 的 ACT_/FLW_ 表由引擎自动创建；
- **W3 行动项**：启动 bpm-server 让 Flowable 自建引擎表 + 从官方渠道/自写 DDL 补 bpm_* 8 张业务配置表（字段可对照 DO 与 H2 DDL 精确还原）。

## 5. W3/W4 复刻清单（buildcloud 三条审批流的模板）

### 5.1 需要新建的类（以 `buildcloud-module-project` 立项审批为例，包名 com.buildcloud.*）

| 类 | 参照基座 | 说明 |
| --- | --- | --- |
| `controller/admin/project/BcProjectController` | OaLeaveApplyController | 草稿 create/update + submit + page/get；`@PreAuthorize("@ss.hasPermission('project:xxx:...')")` |
| `service/project/BcProjectService(Impl)` | OaLeaveApplyServiceImpl | 提交时 Feign 调 `BpmProcessInstanceApi.createProcessInstance`，回写 process_instance_id；`updateProjectStatus(businessKey, status)` 供回调 |
| `dal/.../BcProjectDO` | OaLeaveApplyDO | BaseDO + `status`（复用 BpmProcessInstanceStatusEnum 值域）+ `process_instance_id` |
| `framework/rpc/config/RpcConfiguration` | bpm-server 的 RpcConfiguration | `@EnableFeignClients(clients = {..., BpmProcessInstanceApi.class})` |
| `enums/BpmModelConstants`（或等价常量类） | oa 模块 BpmModelConstants | 流程定义 key 常量：`project-approve` / `payment-approve` / `requisition-approve`（spec §5.3） |
| `service/xxx/listener/XxxStatusListener` | oa-server 的 OaLeaveApplyStatusListener | `@RestController + extends BpmProcessInstanceStatusEventListener`：`@PostMapping("/rpc-api/project/xxx/update-audit-status")` + 按 key 过滤 + 更新单据状态/触发后置动作 |

### 5.2 需要在 bpm-server 侧登记的（跨服务回调转发）

- 新建 `com.iocoder.yudao.module.bpm.api.event` 包下转发监听器（照抄 `OaLeaveApplyStatusListener` 模式：`getProcessDefinitionKey()` 返回业务流程 key，`onEvent` 用 `BpmHttpRequestUtils.executeBpmHttpRequest(event, "http://buildcloud-project/rpc-api/project/.../update-audit-status", loadBalancedRestTemplate)`）；
- 在 bpm-server 的 `RpcConfiguration` 注册 `@Bean @ConditionalOnMissingBean`。
- ⚠️ 这是**唯一需要动基座代码的点**（新增文件、不改已有文件），按 AGENTS.md R-2 记 ADR 说明。

### 5.3 需要的表/配置/流程定义

| 类别 | 清单 |
| --- | --- |
| 业务表 | `bc_project`（立项）、`bc_contract_payment_record` 关联审批（进度款）、`bc_requisition`（申领）——均含 `status` + `process_instance_id` |
| BPM 配置表 | 4.1 的 8 张（W3 前导入） |
| 流程定义 | 管理端「流程模型」设计器创建 3 个模型（key = project-approve / payment-approve / requisition-approve），配置审批人（角色/用户组/发起人自选）、会签、驳回策略（RejectHandler）、超时规则；spec §5.3 的会签/驳回至发起人/超时自动处理均由设计器配置承载 |
| 菜单/权限 | system_menu 加业务单据菜单 + `@PreAuthorize` 权限标识 SQL |
| Nacos | 自研服务无需 BPM 特殊配置；bpm-server 沿用官方配置 |

### 5.4 与 spec §5.3 的对照

- 「审批结果通过 MQ 广播给业务方与站内信」：**基座现状是 Spring 事件 + HTTP 转发 + 站内信，不含 MQ**。W3/W4 复刻按基座模式实现；若仍要 MQ 广播（如通知报表域），属新增设计，届时按 AGENTS.md 记 ADR，不影响审批链路本身；
- 会签/驳回/超时：引擎能力齐备（BpmUserTaskApproveMethodEnum 多实例会签、BpmUserTaskRejectHandlerTypeEnum 驳回策略、BpmBoundaryEventTypeEnum 边界事件），由流程模型配置，无需写代码。

## 6. 一条请求的完整链路（验收追踪线：申领审批）

```
① 材料员在管理端提交申领 → POST /admin-api/material/requisition/submit
   buildcloud-material: OaLeaveApply 模式（status RUNNING → Feign createProcessInstance(key="requisition-approve",
   businessKey=申领单id, variables={projectId, materialId, qty}) → 回写 process_instance_id）
② bpm-server: BpmProcessInstanceApiImpl → BpmProcessInstanceServiceImpl#createProcessInstance
   → Flowable runtimeService 启动实例（BUSINESS_KEY_=申领单id）
③ 审批人待办 → BpmTaskController approve/reject → BpmTaskServiceImpl#approveTask/#rejectTask
④ 流程结束 → BpmProcessInstanceEventListener#processCompleted
   → processProcessInstanceCompleted（补 APPROVE/站内信/发布事件）
   → OaLeaveApplyStatusListener(转发版) POST http://buildcloud-material/rpc-api/material/requisition/update-audit-status
⑤ buildcloud-material: RequisitionStatusListener#onEvent
   → updateRequisitionStatus(businessKey, status)
   → APPROVE：扣库存/出库（W4 V1 链路）；REJECT/CANCEL：回滚预扣 + 冲正流水（W5 V2 链路）
```
