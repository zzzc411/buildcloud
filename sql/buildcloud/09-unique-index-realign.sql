-- =====================================================================================
-- 筑云 BuildCloud · 逻辑删除与业务唯一约束的**生产口径归一**脚本
--
-- 依据：docs/decisions/004-unique-index-with-logical-delete.md（ADR-004，状态：接受）
--       + docs/ops/lessons-learned.md L3-7（「逻辑删除 + 唯一索引 = 500 灾难」实证）
--
-- 一、要解决的问题（ADR-004 §背景）
--   基座所有业务表用**逻辑删除**（`deleted` bit，MyBatis-Plus 自动追加 `deleted = 0`），
--   而 MySQL 唯一索引**不感知** `deleted` 列——`deleted = 1` 的行仍然占用索引位。
--   于是「删除业务编码后用同一编码重建」的完整故障链是：
--     ① Service 的 selectByCode 被逻辑删除插件过滤 → 查不到已删行 → 校验通过
--     ② INSERT 撞唯一索引 → DuplicateKeyException → 前端只拿到 `code=500 系统异常`
--   用户完全无法自助解决（W4-P2 联调实证，删 STEEL 建 STEEL 返 500）。
--   对照事实：基座 `system_users`/`system_role`/`system_dict_type` **只有 PRIMARY，没有任何
--   业务唯一索引**——基座唯一性 100% 由 Service 层承担，所以基座从未暴露这类问题。
--
-- 二、ADR-004 的混合口径（本脚本正是它的落地）
--   A 类「业务标识类」（编码/单号，人可重新申请，删了就该能用同编码重建）
--     → **DROP 数据库唯一索引**，唯一性由 Service 层「查全量（含已删行）」承担；
--     → 但**保留一个同名（或 idx_ 前缀）的普通索引**，查询能力与查询计划不受影响
--       （按编码反查仍走索引，不会退化成全表扫）。
--   B 类「结构唯一类」（天然不该重复，重复本身是数据错误）
--     → **保留数据库唯一索引**，作为并发下的最后防线 + Service 层友好校验。
--     理由见 ADR-004 §理由与替代方案：`bc_material_stock` 是 W5 高并发热点表，
--     「先查再插」在并发下有竞态窗口，会插出重复库存行 → 预扣错乱、超卖。
--
-- 三、A / B 类划分（共 9 个唯一索引，本脚本处理 6 个）
--   A 类 6 个 —— 本脚本 DROP 唯一索引 + ADD 普通索引：
--     bc_project.uk_project_no                    (tenant_id, project_no)   → 普通索引，名不变
--     bc_contract.uk_contract_no                  (tenant_id, contract_no)  → 普通索引，名不变
--     bc_material.uk_material_no                  (tenant_id, material_no)  → 普通索引 idx_material_no
--     bc_supplier.uk_supplier_no                  (tenant_id, supplier_no)  → 普通索引 idx_supplier_no
--     bc_requisition.uk_req_no                    (tenant_id, req_no)       → 普通索引 idx_req_no
--     bc_material_category.uk_material_category_code (tenant_id, code)      → 普通索引 idx_material_category_code
--     （命名说明：材料域 3 条加 idx_ 前缀，因为「uk_ 前缀 = 唯一」是运维肉眼核对的口径，
--       留着旧名会让 DBA 误判；project/contract 两条沿用原名，见各自条目下的注释。）
--     （每条都写清了表、列、为什么归此类，见下方第 1~6 节的逐条注释。）
--   B 类 3 个 —— **不动**，仅在本文件内注释声明（按 ADR-004 保留）：
--     bc_material_stock.uk_project_material        (tenant_id, project_id, material_id)
--     bc_project_member.uk_project_user            (tenant_id, project_id, user_id)
--     bc_contract_payment_plan.uk_contract_period  (tenant_id, contract_id, period)
--
-- 四、执行方式（幂等，可重复执行任意次）
--   1) 按 `INFORMATION_SCHEMA.STATISTICS` 判断「唯一索引是否存在」→ 存在才 DROP；
--   2) 按同一张表判断「普通索引是否存在」→ 不存在才 ADD；
--   3) 两条判断都用 `SET @sql := IF(...)` + `PREPARE`/`EXECUTE`/`DEALLOCATE` 动态拼语句，
--      写法照 `04-project-approval.sql`（MySQL 8 没有 `DROP INDEX IF EXISTS`）。
--   **为什么不用「先跑一次失败再忽略 ERROR 1091」**：脚本在 `sql_mode=STRICT` 或
--   `mysql --force` 打开时报错行为不一致，而条件判断的结果是确定的。
--   判断条件两半写在**同一条判断里**（`唯一索引存在 AND 普通索引不存在`），
--   避免「唯一索引在、普通索引也在」时重复 ADD 撞名。
--
--   执行：
--     docker cp sql/buildcloud/09-unique-index-realign.sql bc-mysql:/tmp/09.sql
--     docker exec bc-mysql sh -c 'mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 \
--         -D ruoyi-vue-pro < /tmp/09.sql'
--     ⚠️ PowerShell 不支持 `<` 重定向，重定向必须放进容器内 shell（lessons-learned L1-1b）；
--     ⚠️ 必须带 --default-character-set=utf8mb4（L2-10，否则中文注释二次编码且不可逆）。
--
-- 五、与建表脚本的关系（为什么要 DROP 再 ADD 一个普通索引，而不是直接删）
--   `01-project.sql` / `03-contract.sql` / `07-material.sql` 的 `CREATE TABLE` 语句**不改**：
--   新建库时这些唯一索引仍作为初始防线（先严后松，比一开始就没有约束安全）。
--   本脚本是「生产库口径归一」的收口动作，可对任意已有库重复执行。
--   若某表根本没有该索引（例如运维手工清了），脚本照样会把普通索引补上（第 2 段独立判断）。
--
-- 六、影响面
--   - 事务性：MySQL 8 的 `DROP INDEX`/`ADD INDEX` 走 ONLINE DDL（INPLACE），
--     bc_* 表当前数据量下为秒级，不锁写；大表上仍建议在低峰执行。
--   - 行为：A 类字段从此**允许**「同编码、一条活跃 + 若干已删」并存，
--     由 Service 层拦截「同编码且未删除」的重复（返回可读 400，不是 500）。
-- =====================================================================================


-- ------------------------------------------------------------------------------------
-- 1) bc_project.uk_project_no → 归 A 类：项目编号由业务方按编码规则自编，
--    项目录入错误/立项撤销后重建同编号是正常业务动作。
--    索引名沿用原名 `uk_project_no`（基座约定「索引名 = 语义名」，语义已从"唯一"降级为"普通"，
--    改名会牵动 01-project.sql 与实际库之间的口径差，反而不利于核对；本文件头已声明其降级）。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_project'
            AND INDEX_NAME = 'uk_project_no') > 0
        AND
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_project'
            AND INDEX_NAME = 'uk_project_no'
            AND NON_UNIQUE = 1) = 0,
        'ALTER TABLE `bc_project` DROP INDEX `uk_project_no`',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 补普通索引（查询能力保留）：按项目编号反查仍是热路径（导入/对账/重复录入提示）
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_project'
            AND INDEX_NAME = 'uk_project_no') = 0,
        'ALTER TABLE `bc_project` ADD INDEX `uk_project_no` (`tenant_id`, `project_no`) USING BTREE',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ------------------------------------------------------------------------------------
-- 2) bc_contract.uk_contract_no → 归 A 类：合同编号人可重新编制；
--    合同录入作废后以同编号重签是常态（补充协议与主合同分别编号）。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_contract'
            AND INDEX_NAME = 'uk_contract_no') > 0
        AND
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_contract'
            AND INDEX_NAME = 'uk_contract_no'
            AND NON_UNIQUE = 1) = 0,
        'ALTER TABLE `bc_contract` DROP INDEX `uk_contract_no`',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_contract'
            AND INDEX_NAME = 'uk_contract_no') = 0,
        'ALTER TABLE `bc_contract` ADD INDEX `uk_contract_no` (`tenant_id`, `contract_no`) USING BTREE',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ------------------------------------------------------------------------------------
-- 3) bc_material_category.uk_material_category_code → 归 A 类
--    这里是 ADR-004 的**直接触发点**（W4-P2 实测：删 STEEL 再建 STEEL 返 500）。
--    材料目录编码是人编的归类简化码，目录撤销重建同编码很常见。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_material_category'
            AND INDEX_NAME = 'uk_material_category_code') > 0
        AND
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_material_category'
            AND INDEX_NAME = 'uk_material_category_code'
            AND NON_UNIQUE = 1) = 0,
        'ALTER TABLE `bc_material_category` DROP INDEX `uk_material_category_code`',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 普通索引名加 idx_ 前缀：保留原名会让「uk_ 前缀 = 唯一」的直觉判断出错，
-- 这张表的索引名在生产库上是 DBA/运维肉眼核对的口径，改名成本低于误判成本。
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_material_category'
            AND INDEX_NAME = 'idx_material_category_code') = 0,
        'ALTER TABLE `bc_material_category` ADD INDEX `idx_material_category_code` (`tenant_id`, `code`) USING BTREE',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ------------------------------------------------------------------------------------
-- 4) bc_material.uk_material_no → 归 A 类（物料编码，人可重新编制）
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_material'
            AND INDEX_NAME = 'uk_material_no') > 0
        AND
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_material'
            AND INDEX_NAME = 'uk_material_no'
            AND NON_UNIQUE = 1) = 0,
        'ALTER TABLE `bc_material` DROP INDEX `uk_material_no`',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_material'
            AND INDEX_NAME = 'idx_material_no') = 0,
        'ALTER TABLE `bc_material` ADD INDEX `idx_material_no` (`tenant_id`, `material_no`) USING BTREE',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ------------------------------------------------------------------------------------
-- 5) bc_supplier.uk_supplier_no → 归 A 类（供应商编码，供方退出后同编码重新入库很常见）
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_supplier'
            AND INDEX_NAME = 'uk_supplier_no') > 0
        AND
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_supplier'
            AND INDEX_NAME = 'uk_supplier_no'
            AND NON_UNIQUE = 1) = 0,
        'ALTER TABLE `bc_supplier` DROP INDEX `uk_supplier_no`',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_supplier'
            AND INDEX_NAME = 'idx_supplier_no') = 0,
        'ALTER TABLE `bc_supplier` ADD INDEX `idx_supplier_no` (`tenant_id`, `supplier_no`) USING BTREE',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ------------------------------------------------------------------------------------
-- 6) bc_requisition.uk_req_no → 归 A 类（申领单号，人可重新开单）
--
--    ⚠️ 这条与其余 5 条有一处**语义差别**，必须记住：
--    07-material.sql 里 uk_req_no 被当成「MQ 消费幂等键」（spec §6.1「以 req_no 唯一索引
--    防重复投递」）。DROP 之后，**幂等不能再依赖数据库唯一索引**，而必须落在：
--      a) 单据状态 CAS（`UPDATE ... WHERE status = 期望值`，影响行数 0 即幂等返回）；
--      b) 库存流水的 biz_no + biz_type 业务键（对账维度）；
--      c) 单号生成器的随机段（REQ-yyyyMMdd-XXXXXX，碰撞概率可忽略）。
--    当前 W4 的实现已经全部满足 a)（所有状态推进都是 CAS），b/c 也已具备；
--    W5 的 MQ 消费者必须**继续**走 a) 而不是"插流水靠唯一索引兜底"，否则幂等会悄悄失效。
--    另外：由于 uk_req_no 覆盖 (tenant_id, req_no) 整列，它同时承担了「按单号反查」的查询索引，
--    故这里必须补一个同列普通索引，不能只 DROP。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_requisition'
            AND INDEX_NAME = 'uk_req_no') > 0
        AND
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_requisition'
            AND INDEX_NAME = 'uk_req_no'
            AND NON_UNIQUE = 1) = 0,
        'ALTER TABLE `bc_requisition` DROP INDEX `uk_req_no`',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_requisition'
            AND INDEX_NAME = 'idx_req_no') = 0,
        'ALTER TABLE `bc_requisition` ADD INDEX `idx_req_no` (`tenant_id`, `req_no`) USING BTREE',
        'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- =====================================================================================
-- 7) B 类：**按 ADR-004 保留，本脚本不落任何 DDL**
--
--    bc_material_stock.uk_project_material (tenant_id, project_id, material_id)
--      同项目同物料只能有一行库存——W5 高并发热点行的前提。并发下「先查再插」不足以
--      防重复行（两个请求同时查不到 → 同时插），重复库存行会让预扣/扣减分散到两行，
--      超卖与对账断点同时出现。**不能靠逻辑删除重建**：库存行删了就是关系消失，
--      同 (项目, 物料) 再出现本该复用同一行（删后重建是数据错误，不是业务动作）。
--      已实测：同 (project, material) 插两条 → Duplicate entry（见 W4-P6 验收记录）。
--
--    bc_project_member.uk_project_user (tenant_id, project_id, user_id)
--      一人在一个项目里只有一个成员身份（角色变更走 UPDATE，不新增行），
--      是「成员白名单数据权限」的判定前提；重复行会让权限判定出现两个不同角色。
--
--    bc_contract_payment_plan.uk_contract_period (tenant_id, contract_id, period)
--      **已核对**：该索引含 `period`（第几期），同一合同内期次唯一正是业务要防的真错误
--      （重复编制同一期收付款计划 → 台账重复统计金额）。period 是序号而非可重建的编码，
--      删除某期计划后再编同一期本身就是错误操作，应由 Service 给出可读拒绝而非放行。
--
--    以上三个索引若被误删，用对应建表脚本的 `UNIQUE KEY` 语句补回（01-project.sql / 03-contract.sql /
--    07-material.sql），或在下面手动执行（已注释，勿解除）：
--    -- ALTER TABLE `bc_material_stock` ADD UNIQUE KEY `uk_project_material` (`tenant_id`, `project_id`, `material_id`);
--    -- ALTER TABLE `bc_project_member` ADD UNIQUE KEY `uk_project_user` (`tenant_id`, `project_id`, `user_id`);
--    -- ALTER TABLE `bc_contract_payment_plan` ADD UNIQUE KEY `uk_contract_period` (`tenant_id`, `contract_id`, `period`);
-- =====================================================================================


-- ------------------------------------------------------------------------------------
-- 8) 结果自检（脚本末尾输出，便于执行时一眼核对：
--    A 类 6 行 NON_UNIQUE=1；B 类 3 行 NON_UNIQUE=0）
-- ------------------------------------------------------------------------------------
SELECT TABLE_NAME,
       INDEX_NAME,
       IF(NON_UNIQUE = 1, '普通索引(A类口径)', '唯一索引(B类口径)') AS kind,
       GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)           AS columns_of_index
  FROM INFORMATION_SCHEMA.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN ('bc_project', 'bc_contract', 'bc_material_category', 'bc_material',
                      'bc_supplier', 'bc_requisition',
                      'bc_material_stock', 'bc_project_member', 'bc_contract_payment_plan')
   AND INDEX_NAME IN ('uk_project_no', 'uk_contract_no', 'uk_material_category_code',
                      'uk_material_no', 'uk_supplier_no', 'uk_req_no',
                      'idx_material_category_code', 'idx_material_no', 'idx_supplier_no', 'idx_req_no',
                      'uk_project_material', 'uk_project_user', 'uk_contract_period')
 GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
 ORDER BY TABLE_NAME, INDEX_NAME;
