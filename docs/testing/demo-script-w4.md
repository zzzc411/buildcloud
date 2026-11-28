# W4 演示走查手册：材料域端到端闭环（申领 → 会签 → 出库 → 退料 → 对账）

| 项 | 内容 |
| --- | --- |
| 计划 | [plan-w4-material-v1](../plans/plan-w4-material-v1.md) Phase 6 |
| 覆盖链路 | 建目录/物料 → 入库 → 建申领单 → 提交会签审批 → 2 人批准 → 查状态与库存 → 出库 → 退料（部分 + 全退）→ 流水与库存对账 |
| 适用版本 | `main` @ W4（`requisition-approve` 流程定义 v1；唯一索引口径已按 ADR-004 归一） |
| 执行环境 | Windows PowerShell 5.1 + curl.exe（Win10 1803+ 自带）；MySQL 容器 `bc-mysql`（宿主 13306，容器内 socket） |
| 本次实测时间 | 2026-11-28，管理员 `admin/admin123`、普通账号 `yudao/admin123` |

> ⚠️ **这是可执行的走查脚本，不是性能报告。** 本文件出现的所有数字都是「本次走查的实际返回 / 断言值」，
> 不构成任何吞吐、延迟、容量结论。**性能数字只能出自 `docs/testing/` 下的压测报告**（AGENTS R-6）。

---

## 0. 前置条件（缺一项后面的步骤就会失败）

### 0.1 中间件

```powershell
cd D:\project\buildcloud
docker ps --format "{{.Names}}`t{{.Status}}"
# 期望：bc-mysql / bc-redis / bc-nacos / bc-rmq-namesrv / bc-rmq-broker 全部 Up (healthy)
```

`xxl-job` / `seata` / `postgres` / `minio` 在 local profile 下**不需要**（见 `docs/ops/middleware-limits.md`）。

### 0.2 五个服务

本走查**必须**有：`gateway(48080)`、`system(48081)`、`infra(48082)`、`bpm(48083)`、`material(48085)`。

```powershell
# 端口自检：5 个端口都应列出监听
Get-NetTCPConnection -State Listen -LocalPort 48080,48081,48082,48083,48085 |
  Select-Object LocalPort, OwningProcess | Sort-Object LocalPort

# 没起来就一键拉起（先打包：jar 必须在 target/ 下）
# mvn -B -ntp -DskipTests -Dmaven.test.skip=true install -pl buildcloud-module-material/buildcloud-module-material-api,buildcloud-module-material/buildcloud-module-material-server
powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1 -Services system,infra,bpm,gateway,material
```

服务级就绪（自研模块有 health 接口）：

```powershell
curl.exe -s -H "tenant-id: 1" http://127.0.0.1:48085/admin-api/buildcloud-material/health
# 期望：{"code":0,"msg":"","data":"ok"}
```

### 0.3 流程已部署（`requisition-approve`）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\bpm-deploy-requisition-approve.ps1
```

验证（容器内执行）：

```powershell
docker exec bc-mysql mysql -uroot -p"<PWD>" -D ruoyi-vue-pro -e "SELECT KEY_,VERSION_,DEPLOYMENT_ID_ FROM ACT_RE_PROCDEF WHERE KEY_='requisition-approve';"
# 期望：1 行，KEY_=requisition-approve
```

> 脚本会先 `model/create`，key 已存在则走「复用模型 + `update-bpmn` 刷新 XML 再部署」分支
> （lessons-learned L2-14：复用模型时**必须**刷新 bpmnXml，否则部署的是旧内容）。

### 0.4 账号与外键前置数据

| 用途 | 值 | 说明 |
| --- | --- | --- |
| 发起人 / 会签人 1 | `admin` / `admin123`（`system_users.id=1`） | 默认超级管理员 |
| 会签人 2 | `yudao` / `admin123`（`system_users.id=100`） | 会签需要**两个不同的人各批一次** |
| 演示项目 | `bc_project.id=2`（`BC-DEMO-001` 滨江商务中心） | 走查用固定 id，SQL 对账也用它 |
| 租户 | `tenant-id: 1` | 所有请求都必须带这个头 |

> **为什么必须两个账号**：会签节点按 `approvers` 变量并行建任务，**不能代替别人审批**——
> 用 admin 去批 assignee=100 的任务会返回 `1009005001 该任务的审批人不是你`（L2-16）。

### 0.5 登录拿 token（后续所有请求复用）

```powershell
$login = curl.exe -s -X POST "http://127.0.0.1:48080/admin-api/system/auth/login" `
  -H "tenant-id: 1" -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@login.json"
$login | ConvertFrom-Json | Select-Object code
```

`login.json`（UTF-8 无 BOM）：

```json
{"username":"admin","password":"admin123"}
```

`ADMIN_TOKEN` / `YUD_TOKEN` 用同样的方式分别登录 `admin` 与 `yudao`：

```powershell
$ADMIN_TOKEN = (curl.exe -s -X POST "http://127.0.0.1:48080/admin-api/system/auth/login" `
  -H "tenant-id: 1" -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@login.json" | ConvertFrom-Json).data.accessToken
$YUD_TOKEN   = (curl.exe -s -X POST "http://127.0.0.1:48080/admin-api/system/auth/login" `
  -H "tenant-id: 1" -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@login-yudao.json" | ConvertFrom-Json).data.accessToken
```

`login-yudao.json`：

```json
{"username":"yudao","password":"admin123"}
```

**期望**：两次都是 `code=0`，`data.accessToken` 非空（32 位）。**判据口径**：基座是「HTTP 200 + JSON body
`code`」，所以失败时 curl 也可能返回 200，**必须看 `code` 字段**（L3-4）。

### 0.6 为什么全部用 `--data-binary @file`

PowerShell 5.1 的 `Invoke-RestMethod -Body '<含中文的字符串>'` 会按 ISO-8859-1 编码 body，
中文被替换成 `?`（HEX `3F`）后落库**不可逆**（lessons-learned L1-1d）。
`curl.exe --data-binary "@file"` + 文件存为 **UTF-8 无 BOM** 是唯一稳妥姿势（L2-10）。

> 文件命名约定：本手册引用的所有请求体文件都放在 `D:\project\tmp\w4-demo\`，与仓库解耦（不入库）。

### 0.7 变量速查（每步都会用到）

```powershell
$G      = "http://127.0.0.1:48080/admin-api"
$HDR_A  = @("-H","Authorization: Bearer $ADMIN_TOKEN","-H","tenant-id: 1")
$HDR_Y  = @("-H","Authorization: Bearer $YUD_TOKEN","-H","tenant-id: 1")
$J      = "-H","Content-Type: application/json; charset=utf-8"
$PROJ   = 2          # bc_project.id（BC-DEMO-001）
```

---

## 步骤 1：建材料目录（A 类编码，可删后重建）

`create-category.json`：

```json
{"parentId":0,"name":"演示-主材","code":"DEMO-MAIN","sort":1,"status":0}
```

```powershell
curl.exe -s -X POST "$G/buildcloud-material/category/create" $HDR_A $J --data-binary "@create-category.json"
```

**期望**：`code=0`，`data` 为新目录 id（本次实测 `22`）。把它记下来：

```powershell
$CAT_ID = 22      # ← 用上面返回的 data 替换
```

> 编码 `DEMO-MAIN` 属 **ADR-004 的 A 类**（人可重建的业务编码）：删掉该目录后**可以用同一个 code 再建**，
> 唯一约束只在「同编码且未删除」时生效。生产库的索引口径由 `sql/buildcloud/09-unique-index-realign.sql` 归一。

---

## 步骤 2：建物料

`create-material.json`（注意 `categoryId` 填上一步的 id）：

```json
{"materialNo":"DEMO-REBAR-01","name":"演示螺纹钢","spec":"HRB400 Φ20","unit":"吨","categoryId":22,"status":0}
```

```powershell
curl.exe -s -X POST "$G/buildcloud-material/material/create" $HDR_A $J --data-binary "@create-material.json"
$MAT_ID = 11      # ← 用返回的 data 替换（本次实测 11）
```

**期望**：`code=0`，`data` 为物料 id。

**顺带验一下 A 类口径（可选但推荐）**：同编码重复创建必须被 Service 拒绝且是**可读的 400**，不是 500：

```powershell
curl.exe -s -X POST "$G/buildcloud-material/material/create" $HDR_A $J --data-binary "@create-material.json"
# 期望：code=1_102_020_002（MATERIAL_NO_DUPLICATE），msg 含「物料编码[DEMO-REBAR-01]已存在」
```

---

## 步骤 3：入库（两笔，验证余额累加与乐观锁）

`inbound-1.json` / `inbound-2.json`：

```json
{"projectId":2,"materialId":11,"quantity":100,"remark":"首批进场"}
```

```json
{"projectId":2,"materialId":11,"quantity":50.5,"remark":"第二批进场"}
```

```powershell
curl.exe -s -X POST "$G/buildcloud-material/stock/inbound" $HDR_A $J --data-binary "@inbound-1.json"
curl.exe -s -X POST "$G/buildcloud-material/stock/inbound" $HDR_A $J --data-binary "@inbound-2.json"
```

**期望**（本次实测）：

| 请求 | `code` | `data.bizType` | `data.quantity` | `data.balanceAfter` |
| --- | --- | --- | --- | --- |
| inbound-1 | 0 | 1（入库） | 100 | 100.0000 |
| inbound-2 | 0 | 1 | 50.5 | **150.5000** |

入库单号形如 `IN-20261128-E78FA86E`（`data.bizNo`），写进流水的 `biz_no`，后面按单号可追溯。

查询库存（`total=1`、`availableQty=150.5000`、`lockedQty=0.0000`）：

```powershell
curl.exe -s "$G/buildcloud-material/stock/page?projectId=$PROJ&materialId=$MAT_ID" $HDR_A
```

---

## 步骤 4：建申领单（草稿，不动库存）

`requisition-create.json`：

```json
{"projectId":2,"applicantUserId":1,"remark":"演示：3号楼主体用钢","items":[{"materialId":11,"quantity":50}]}
```

```powershell
curl.exe -s -X POST "$G/buildcloud-material/requisition/create" $HDR_A $J --data-binary "@requisition-create.json"
$REQ_ID = 164     # ← 用返回的 data 替换
```

**期望**：`code=0`，`data` 为新申领单 id。此时单据 `status=0`（草稿），库存仍是 `150.5`（**没动**）。

查详情（含明细与 `returnedQty=0`）：

```powershell
curl.exe -s "$G/buildcloud-material/requisition/get?id=$REQ_ID" $HDR_A
```

---

## 步骤 5：提交会签审批（会签人 = admin(1) + yudao(100)）

`requisition-approve-submit.json`：

```json
{"requisitionId":164,"approverUserIds":[1,100]}
```

```powershell
curl.exe -s -X POST "$G/buildcloud-material/requisition/approval/submit" $HDR_A $J --data-binary "@requisition-approve-submit.json"
$INST = "..."     # ← 用返回的 data（流程实例 id）替换
```

**期望**：

- `code=0`，`data` 为流程实例 id（形如 `669045af-eae4-11f1-8871-b047e9969ddb`）；
- 单据状态 `status=1`（已提交）、`approval_status=1`（审批中），`approvers="1,100"`；
- **库存已扣减**：`available_qty` 从 `150.5` → `100.5`（V1 口径：**提交即扣减**，不是审批通过才扣）。

```powershell
curl.exe -s "$G/buildcloud-material/requisition/approval/status?requisitionId=$REQ_ID" $HDR_A
curl.exe -s "$G/buildcloud-material/stock/page?projectId=$PROJ&materialId=$MAT_ID" $HDR_A
```

> 为什么提交就扣：V1 是「同一事务内 `SELECT ... FOR UPDATE` → 校验 → 扣减 → 写流水」的对照组实现
> （spec §6.1）。审批通过**只推进状态、绝不再扣一次**——重复扣 = 同一单扣两遍而驳回只补一遍，账永远不平。

---

## 步骤 6：查会签任务（2 个任务、2 个不同的人）

```powershell
docker exec bc-mysql mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 -D ruoyi-vue-pro `
  -e "SELECT ID_, ASSIGNEE_, PROC_INST_ID_ FROM ACT_RU_TASK WHERE PROC_INST_ID_='$INST';"
```

**期望**：**2 行**，`ASSIGNEE_` 分别是 `1` 与 `100`（证明 `approvers` 变量取人生效、会签实例数=名单人数）。

> 也可以用管理端接口查，但 `ACT_RU_TASK` 是**唯一能证明「中途状态」的地方**：
> 会签的实例结束事件只在「全部实例完成」时发布，业务侧中途**收不到任何回调**，
> 所以「批了一个人」在业务表上是看不出来的（L2-16）。

---

## 步骤 7：会签 2 人批准（必须用各自的账号）

### 7.1 admin 批自己那条（会签 1/2）

把 `ACT_RU_TASK` 里 `ASSIGNEE_=1` 的 `ID_` 填进 `approve-admin.json`：

```json
{"id":"669771bb-eae4-11f1-8871-b047e9969ddb","reason":"同意"}
```

```powershell
curl.exe -s -X PUT "$G/bpm/task/approve" $HDR_A $J --data-binary "@approve-admin.json"
```

**期望**：`code=0`。**然后**：

```powershell
curl.exe -s "$G/buildcloud-material/requisition/approval/status?requisitionId=$REQ_ID" $HDR_A
# 期望：status=1（仍是已提交）、approvalStatus=1（仍在审批中）—— 1/2 不算通过
curl.exe -s "$G/buildcloud-material/stock/page?projectId=$PROJ&materialId=$MAT_ID" $HDR_A
# 期望：availableQty=100.5（库存一分不动）
```

> ⚠️ 基座审批接口是 **`PUT`**（用 POST 会得到 `code=405`，看着像接口不存在），
> 且**不能代替别人审批**（用 admin 批 assignee=100 的任务会返回 `1009005001`）。这两条都是 L2-16 的实证。

### 7.2 yudao 批自己的那条（会签 2/2 → 流程结束）

`approve-yudao.json`（填 `ASSIGNEE_=100` 的 `ID_`）：

```json
{"id":"66988331-eae4-11f1-8871-b047e9969ddb","reason":"同意"}
```

```powershell
curl.exe -s -X PUT "$G/bpm/task/approve" $HDR_Y $J --data-binary "@approve-yudao.json"
Start-Sleep -Seconds 3      # 等 bpm 的转发器把结果回调到 material
curl.exe -s "$G/buildcloud-material/requisition/approval/status?requisitionId=$REQ_ID" $HDR_A
```

**期望**：

| 字段 | 期望值 | 说明 |
| --- | --- | --- |
| `status` | `2` | 已扣减（会签全部通过） |
| `approvalStatus` | `2` | 审批通过 |
| `approvalStatusName` | `审批中` → `通过` | 可读文案 |
| `reason` | `会签已通过，库存已扣减，可出库` | 下一步指引 |
| 库存 | **仍是 `100.5`** | 通过**不扣库存**（提交时已扣） |

```powershell
curl.exe -s "$G/buildcloud-material/stock/page?projectId=$PROJ&materialId=$MAT_ID" $HDR_A
```

---

## 步骤 8：出库（2 → 3，不动库存）

```powershell
curl.exe -s -X POST "$G/buildcloud-material/requisition/outbound?id=$REQ_ID" $HDR_A
```

**期望**：`code=0`，`data.status=3`（已出库），`data.actualOutTime` 非空；库库存仍是 `100.5`。

重复出库必须被拒（防止「在途料时长」报表被刷新）：

```powershell
curl.exe -s -X POST "$G/buildcloud-material/requisition/outbound?id=$REQ_ID" $HDR_A
# 期望：code=1_102_050_010（REQUISITION_CANNOT_OUTBOUND），msg 含「已出库…仅已扣减可出库」
```

---

## 步骤 9：退料（先部分 20，再退剩余 30 → 全部退完）

### 9.1 退 20（3 → 5 部分回退）

`return-20.json`：

```json
{"requisitionId":164,"items":[{"materialId":11,"quantity":20}]}
```

```powershell
curl.exe -s -X POST "$G/buildcloud-material/requisition/return" $HDR_A $J --data-binary "@return-20.json"
```

**期望**：`code=0`、`data.status=5`（部分回退）、`data.items[0].returnedQty=20.0000`、
`data.actualReturnTime` 非空；库存 `100.5 → 120.5`，新增一条 `biz_type=3 quantity=+20 balance_after=120.5` 流水。

### 9.2 退剩余 30（5 → 4 已完成）

`return-30.json`：

```json
{"requisitionId":164,"items":[{"materialId":11,"quantity":30}]}
```

```powershell
curl.exe -s -X POST "$G/buildcloud-material/requisition/return" $HDR_A $J --data-binary "@return-30.json"
```

**期望**：`code=0`、`data.status=4`（已完成）；`returnedQty` 累加到 `50.0000`（=申领量，可退量 0）；
库存 `120.5 → 150.5`（回到入库总量）；新增 `biz_type=3 quantity=+30 balance_after=150.5` 流水。

### 9.3 反向用例：再退 1 必须被拒（终态不可退）

```powershell
curl.exe -s -X POST "$G/buildcloud-material/requisition/return" $HDR_A $J --data-binary "@return-1.json"
# return-1.json = {"requisitionId":164,"items":[{"materialId":11,"quantity":1}]}
# 期望：code=1_102_050_011（REQUISITION_CANNOT_RETURN），msg 含「已完成」
```

---

## 步骤 10：查流水与库存对账

### 10.1 流水（5 条，按时间倒序）

```powershell
curl.exe -s "$G/buildcloud-material/stock/stock-log/page?projectId=$PROJ&materialId=$MAT_ID&pageSize=10" $HDR_A
```

**期望**（本次实测，倒序）：

| `id` | `bizType` | `quantity` | `balanceAfter` | `bizNo` |
| --- | --- | --- | --- | --- |
| 122 | 3 退回 | +30.0000 | 150.5000 | `REQ-20261128-89C76C` |
| 121 | 3 退回 | +20.0000 | 120.5000 | 同上 |
| 120 | 2 申领扣减 | −50.0000 | 100.5000 | 同上 |
| 119 | 1 入库 | +50.5000 | 150.5000 | `IN-20261128-0A80088C` |
| 118 | 1 入库 | +100.0000 | 100.0000 | `IN-20261128-E78FA86E` |

### 10.2 单据终态

```powershell
curl.exe -s "$G/buildcloud-material/requisition/approval/status?requisitionId=$REQ_ID" $HDR_A
```

**期望**：`status=4`、`approvalStatus=2`、`approvers=[1,100]`、`items[0].returnedQty=50`、`items[0].returnableQty=0`。

### 10.3 对账校验 SQL（**核心口径：`SUM(quantity) == available_qty`**）

> 容器内执行（PowerShell 不支持 `<` 重定向，必须 `docker cp` + 容器内 shell，L1-1b；
> 必须带 `--default-character-set=utf8mb4`，否则中文注释/数据双重编码且不可逆，L2-10）。
> `<PWD>` 从 `docker/.env` 的 `MYSQL_ROOT_PASSWORD` 取（真实值不入库，R-1）。

`recon.sql`（放 `D:\project\tmp\w4-demo\`）：

```sql
-- ① 库存 vs 流水净额：本演示项目/物料
SELECT s.project_id, s.material_id,
       s.available_qty                          AS stock_available,
       COALESCE(l.sum_qty, 0)                   AS log_sum_quantity,
       s.available_qty - COALESCE(l.sum_qty, 0) AS diff,
       IF(s.available_qty = COALESCE(l.sum_qty, 0), 'CONSISTENT', 'BROKEN') AS verdict
  FROM bc_material_stock s
  LEFT JOIN (SELECT project_id, material_id, SUM(quantity) AS sum_qty
               FROM bc_stock_log WHERE deleted = 0
              GROUP BY project_id, material_id) l
         ON l.project_id = s.project_id AND l.material_id = s.material_id
 WHERE s.deleted = 0 AND s.project_id = 2 AND s.material_id = 11;

-- ② 该物料逐笔流水（balance_after 必须等于「期初 + 累计流水」）
SELECT id, biz_type, biz_no, quantity, balance_after, create_time
  FROM bc_stock_log
 WHERE deleted = 0 AND project_id = 2 AND material_id = 11
 ORDER BY id;

-- ③ 单据与明细终态
SELECT r.id, r.req_no, r.status, r.approval_status, r.approvers,
       r.actual_out_time, r.actual_return_time,
       i.material_id, i.quantity, i.returned_qty,
       (i.quantity - i.returned_qty) AS returnable
  FROM bc_requisition r
  JOIN bc_requisition_item i ON i.requisition_id = r.id AND i.deleted = 0
 WHERE r.id = 164;

-- ④ 全库口径（所有项目物料）：应为 0 行「BROKEN」
SELECT s.project_id, s.material_id, s.available_qty, COALESCE(l.sum_qty, 0) AS log_sum,
       s.available_qty - COALESCE(l.sum_qty, 0) AS diff
  FROM bc_material_stock s
  LEFT JOIN (SELECT project_id, material_id, SUM(quantity) AS sum_qty
               FROM bc_stock_log WHERE deleted = 0
              GROUP BY project_id, material_id) l
         ON l.project_id = s.project_id AND l.material_id = s.material_id
 WHERE s.deleted = 0 AND s.available_qty <> COALESCE(l.sum_qty, 0);
```

```powershell
docker cp D:\project\tmp\w4-demo\recon.sql bc-mysql:/tmp/recon.sql
docker exec bc-mysql sh -c "mysql -uroot -p'<PWD>' --default-character-set=utf8mb4 -D ruoyi-vue-pro -t < /tmp/recon.sql"
```

**期望**（本次实测）：

```
| project_id | material_id | stock_available | log_sum_quantity | diff   | verdict    |
|          2 |          11 |        150.5000 |         150.5000 | 0.0000 | CONSISTENT |
```

第 ④ 段应返回 **空结果集**（0 行）。这是最值得在演示里念出来的那句：
**「期初 + Σ流水 = 期末」在真库上对得平，不是单测里的假绿。**

---

## 附：失败时的 3 秒定位表

| 现象 | 先看哪里 | 常见原因 |
| --- | --- | --- |
| 网关 `code=503` | `logs/gateway.out.log` + Nacos 服务列表 | 目标服务未注册 / 刚重启还在注册中 |
| 登录 `code=0` 但其它接口 `code=401` | 请求头 | 少带 `Authorization` 或 `tenant-id` |
| 审批动作 `code=405` | 请求方法 | 用了 POST，基座 `/bpm/task/approve` 是 **PUT** |
| `code=1009005001` | `ACT_RU_TASK.ASSIGNEE_` | 用错了账号批别人的任务 |
| 会签批完 1 人后状态不动 | 业务表 `status/approval_status` + `ACT_RU_TASK` | **正常**：会签未收齐，业务侧本来就没有回调 |
| 审批通过后库存变少 | `bc_stock_log` 该单流水 | 不该发生：通过不碰库存；若变少说明被重复扣减，立即回滚现场并查日志 |
| 中文变 `?` | 请求体提交姿势 | 用了 `Invoke-RestMethod -Body '字符串'`，改用 `curl.exe --data-binary @file`（L1-1d） |
| 建目录/物料同编码报 500 | 库里的唯一索引 | 生产库还没跑 `09-unique-index-realign.sql`，跑一次即可（ADR-004） |

---

## 附：本次走查覆盖的验收点（对应 plan-w4 Phase 6）

| 验收点 | 本脚本步骤 | 判据 |
| --- | --- | --- |
| 申领 → 审批 → 出库手动演示通过 | 步骤 4-8 | 每步 `code=0` 且状态按 1→2→3 推进 |
| 会签 2 人全过才通过 | 步骤 6-7 | 1/2 时 `status` 不变、库存不变；2/2 后 `status=2` |
| 退料部分回退与全退 | 步骤 9 | 5 → 4；`returnedQty` 20 → 50 |
| 库存与流水对账 | 步骤 10 | `SUM(quantity) == available_qty`，`diff=0` |
| V1 可被压测（对照组就绪） | 步骤 4-5 的接口面（`create` / `submit`） | 接口与参数就绪；**吞吐/延迟数据见 W5 压测报告，本文件不提供** |
