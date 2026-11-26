# sql/buildcloud — 自研业务表脚本目录

筑云（BuildCloud）**自研业务域**的数据库脚本目录，与基座脚本目录 `sql/mysql/` 并列、互不混放。

- `sql/mysql/` — 基座 yudao-cloud 自带脚本（`ruoyi-vue-pro.sql` 主库、`bpm-2025-03-15.sql`、`bpm-alter-2026-11-25.sql`、`quartz.sql`），**只读使用，不修改**（AGENTS.md R-2）。
- `sql/buildcloud/` — 本目录，`bc_` 前缀业务表 + 菜单/字典初始化，按业务域拆文件。

## 文件清单与执行顺序

| 顺序 | 文件 | 作用 | 依赖 |
| --- | --- | --- | --- |
| 1 | `01-project.sql` | 项目子域建表：`bc_project`（项目主表）、`bc_project_milestone`（里程碑）、`bc_project_member`（成员） | 基座主库 `ruoyi-vue-pro.sql` 已导入 |
| 2 | `02-menu.sql` | 五大菜单组骨架 + 项目台账页面/按钮权限（`system_menu`，id 段 9000-9049） | 同上 |
| 3 | `03-contract.sql` | 合同资金子域建表：`bc_contract`、`bc_contract_payment_plan`、`bc_contract_payment_record`、`bc_contract_change` | 同上（`contract.project_id` 逻辑关联 `bc_project`，不建物理外键） |

> 后续按 spec §7.1 继续追加：`04-material.sql`（材料域 7 张）、
> `05-report.sql`（报表域 1 张）、`06-ai.sql`（AI 域 3 张 + pgvector chunk 表）。
> 域内结构变更用 `0N-xxx-alter-YYYY-MM-DD.sql`（幂等：先查 `INFORMATION_SCHEMA` 再 `ALTER`）。
> 字段与 spec §5.1 的逐项对照、ER 图与设计取舍见 `docs/architecture/er-diagram-v0.md`。
> **每个文件只增不改**；结构变更另起 `0N-xxx-alter-YYYY-MM-DD.sql`，不原地改写历史脚本。

## 执行方式

先起中间件（MySQL 映射在 13306，密码见本地 `docker/.env`，**不要写进任何入库文件**）：

```powershell
# 在仓库根目录执行；<PWD> 换成 docker/.env 里的 MYSQL_ROOT_PASSWORD
docker exec -i bc-mysql mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro < sql/buildcloud/01-project.sql
docker exec -i bc-mysql mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro < sql/buildcloud/02-menu.sql
```

> ⚠️ **必须带 `--default-character-set=utf8mb4`**。脚本文件是 UTF-8 无 BOM；若客户端默认字符集不是
> utf8mb4（容器内 mysql 客户端可能按 latin1 解释），中文注释与菜单名会变成「双重编码」乱码
> （`项目` → `é¡¹ç®`），且**写入后无法靠改字符集修复**，只能重导。
> 校验办法：`SELECT HEX(name) FROM system_menu WHERE id = 9000;` 应为 `E9A1B9E79BAEE7AEA1E79086`（“项目管理”）。

若走宿主机客户端：

```powershell
mysql -h127.0.0.1 -P13306 -uroot -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro -e "source sql/buildcloud/01-project.sql"
```

> PowerShell 的 `<` 重定向不支持，用管道：`Get-Content sql/buildcloud/01-project.sql -Raw -Encoding UTF8 | docker exec -i ...`

## 可重复执行保证（幂等）

所有脚本必须可**无损重跑**，这是本目录的硬约定：

- **建表**：统一使用 `CREATE TABLE IF NOT EXISTS`，不写 `DROP TABLE`；
  索引/唯一键内联在建表语句里 —— 表已存在时整句跳过，不会重复建索引。
- **数据初始化**：统一「先删本脚本固定号段 → 再插入」，例如 `02-menu.sql` 先
  `DELETE FROM system_menu WHERE id BETWEEN 9000 AND 9049` 再 `INSERT`；
  号段由本目录独占，**不触碰基座任何行**，重跑结果与首次执行一致。
- 禁止使用 `REPLACE INTO`（会重置 `create_time` 等审计字段）与无 `WHERE` 的 `DELETE`。

## 号段约定

| 用途 | 号段 | 说明 |
| --- | --- | --- |
| 菜单节点 | 9000-9049 | 见 `02-menu.sql`：9000 项目管理 / 9010 合同资金 / 9020 材料管理 / 9030 报表中心 / 9040 AI 助手 |
| 菜单子节点 | 9001-9049 | 各业务周随功能追加，父节点为上面五个目录 id |
| 错误码 | 1-100~1-104 | 不在 SQL 中体现，见 `ErrorCodeConstants`（spec §7.2） |

基座 `system_menu` 现有 id 已用到 25380，故自研菜单只占用 9000 段并在脚本内固定声明，避免与基座升级冲突。

## 与代码的一致性检查

改表结构时同步三处，缺一视为未完成：

1. 本目录的建表/变更脚本；
2. `buildcloud-module-*-server` 的 `dal/dataobject`（字段名、类型、注释）；
3. 生效中的计划文档「进度日志」一行。

`bc_project` 的字段与 spec §5.1 逐项对应关系写在文件头注释里，改动时一并更新。
