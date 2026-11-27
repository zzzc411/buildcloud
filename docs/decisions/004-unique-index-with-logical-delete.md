# ADR-004: 逻辑删除与业务唯一约束的口径（全仓）

- 日期：2026-11-27
- 状态：接受
- 关联：AGENTS R-4 / R-8；spec §7.1；`sql/buildcloud/01-project.sql`~`07-material.sql`；W4-P2 联调发现（`uk_material_category_code` 与 `deleted` 冲突）

## 背景

W4-P2 联调暴露一个真实缺陷：删除材料目录 `STEEL` 后**重新创建同编码**，接口返回 `500 系统异常`（`DuplicateKeyException`），用户无法自助解决。

根因链：
1. 基座所有业务表用**逻辑删除**（`deleted` bit，MyBatis-Plus 自动追加 `deleted = 0`）；
2. MySQL 唯一索引**不感知** `deleted` 列——`deleted = 1` 的行仍然占用索引位；
3. Service 层 `selectByCode` 被逻辑删除插件过滤 → 查不到已删行 → 校验通过 → INSERT 撞唯一索引 → 500。

**关键实证**：查基座自身 `system_*` 表（`system_users`/`system_role`/`system_dict_type` 等）——**全表只有 PRIMARY 主键，没有任何业务唯一索引**。基座的唯一性 100% 靠 Service 层：

```java
// RoleServiceImpl 原文
role = roleMapper.selectByCode(code);
throw exception(ROLE_CODE_DUPLICATE, code);
```

即：**基座从未把唯一性交给数据库**，因此从未遇到这个问题。这个坑是我们在 `bc_*` 表上「比基座更严」引入的。

## 决策

**采纳「混合口径」，按业务语义分两类**：

| 类别 | 判定标准 | 做法 | 已落地表 |
| --- | --- | --- | --- |
| **A 类：业务标识类**（编码/单号，人可重新申请） | 删除后应允许用同编码重建 | **不用数据库唯一索引**；Service 层校验（`selectOne` 含 `deleted` 全量查，绕过逻辑删除插件） | `bc_material_category.code`、`bc_material.material_no`、`bc_supplier.supplier_no` |
| **B 类：结构唯一类**（天然不该重复，删除即代表关系消失） | 重复本身是数据错误 | **保留数据库唯一索引**（作为最后防线）+ Service 层友好校验 | `bc_material_stock(tenant_id,project_id,material_id)`、`bc_project_member(tenant_id,project_id,user_id)`、`bc_contract_payment_plan(tenant_id,contract_id,period)` |

**A 类的 Service 层校验必须查已删行**（这是关键细节）：
```java
// 原生 SQL 绕过逻辑删除插件（多租户插件仍自动追加 tenant_id）
@Select("SELECT COUNT(*) FROM bc_material_category WHERE code = #{code}")
Long countByCodeIncludeDeleted(@Param("code") String code);
```
- 若已删行存在 → 返回**可读**错误码（告知"该编码曾被已删除记录使用"），或后端做「回收站恢复」引导；
- 相比 500 崩溃，用户至少有明确提示。**这是本次已完成的降级**（W4-P2 已落地）。

## 理由与替代方案

**为什么不全用 A 类（去掉所有唯一索引）？**

| 方案 | 判断 |
| --- | --- |
| **全 A 类**（完全照基座，Service 层校验） | ❌ 否决：`bc_material_stock` 是 W5 高并发的热点表，并发下 Service 层「先查再插」有竞态窗口，会插出重复库存行 → 预扣错乱、超卖。**并发场景必须有数据库约束兜底**。 |
| **全 B 类**（保留索引 + 把 `deleted` 纳入唯一键） | ❌ 否决：`UNIQUE(tenant_id, code, deleted)` **不解决**问题——只能允许「1 条活跃 + 1 条已删」，删第二次再撞。要真正解决需要 `deleted` 存时间戳/id 而非 bit，**改动全仓 + 改基座逻辑删除机制**（R-2）。 |
| **混合口径（本次采纳）** | ✅ 按业务语义分开：人可重建的编码走 Service 校验，结构性唯一走数据库约束。语义清晰，且**高并发表有硬约束**。 |

**为什么不现在就把 A 类的索引 DROP 掉？**
- 本次只**降级为可读 400**（Service 先探测已删行再抛错），索引仍在；
- 真正 DROP 索引属于 R-4（改数据库结构）+ 影响 P1 已交付的 SQL，需单独一个 `08-unique-index-realign.sql` 迁移脚本统一处理，并同步更新 01/03/07 三个建表脚本（新建库时口径一致）；
- **列为 W4 收尾待办**，与「演示数据/压测数据」一并处理，避免当前打断 P4 申领链路。

## 影响

1. **新增表的口径**（W5-W7 起）：先判断 A/B 类再设计索引，不确定的按 A 类（更宽容）；
2. **必须记录的坑**：已写入 `docs/ops/lessons-learned.md` L2-14；
3. **W4 收尾待办**：① 新建 `08-unique-index-realign.sql`（A 类 DROP 唯一索引，保留普通索引供查询）；② 同步 01/03/07 建表脚本注释口径；③ `bc_project`/`bc_contract`/`bc_requisition` 的 `uk_*_no` 归 A 类（单号人可重建）；
4. **不改基座**（R-2）：本次决策只约束 `bc_*` 表。
