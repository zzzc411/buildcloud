-- =====================================================================================
-- 申领子域（W4-P4：申领单 / 申领明细 / 库存 / 库存流水）单元测试专用 H2 建表脚本（MODE=MYSQL）
--
-- W4-P5 追加：bc_requisition 补 approvers / actual_out_time / actual_return_time 三列、
--            bc_requisition_item 补 returned_qty 一列 —— 与 sql/buildcloud/08-requisition-approval.sql
--            的 ALTER 严格对应（生产靠 08 脚本 ALTER，测试靠本文件全量 CREATE，**两边口径必须一致**）。
--            审批/出库/退料三条链路的集成用例（RequisitionApprovalServiceImplTest）直接复用本文件。
--
-- 为什么独立成文件（而不是写进 create_tables.sql、也不复用 stock-test-tables.sql）：
--   1. create_tables.sql / clean.sql 是 material 域多个并行 worker 的共享文件（P2 基础数据、
--      P3 库存流水、P4 申领），谁改谁冲突 —— 故 P2/P3/P4 各自备脚本（见那两个文件头注释）；
--   2. 申领链路的用例既要**建申领单**（requisition/item）又要**验证库存扣减与流水**
--      （stock/stock_log），两类表必须在同一个 @Sql 里成套 DROP+CREATE，
--      否则「申领删了库存没删」这类半清理会让用例之间串数据；
--   3. 直接复用 stock-test-tables.sql 会耦合：那是 P3 的私有文件，P4 改动它等于改别人的验收口径
--      （且两个脚本都会 DROP 同两张表，一旦有人给 @Sql 加 repeatable 就会互相踩）。
--   → 结论：本文件自带 stock/stock_log（字段与 07-material.sql 严格一致），自成闭环。
--
-- 幂等 / 测试隔离：
--   先 DROP 再 CREATE —— clean.sql 故意为空（见该文件注释），表在每次测试方法前置阶段重建，
--   即天然「清数据」。
--   （不能写成「先 DELETE 再 CREATE」：首次执行时表还不存在，DELETE 直接报 Table not found。）
--
-- 约束：字段与 sql/buildcloud/07-material.sql 的
--       bc_requisition / bc_requisition_item / bc_material_stock / bc_stock_log 严格一致
--       （列名/类型/可空性/默认值/精度），改生产建表脚本时本文件同步；
--       不一致会导致「单测绿、真库红」——尤其 decimal(18,4) 若写成 decimal(18,2)，
--       精度断言会假绿。
--
-- ========== 唯一索引的建/不建（对齐 ADR-004 的 A/B 类口径） ==========
--   - bc_requisition.uk_req_no：**不建**。req_no 属 ADR-004 的 A 类（人可重建的单号），
--     唯一性由 Service 生成策略 + 真库索引兜底；单测若要构造「撞单号」场景，
--     在 H2 建了唯一索引会先抛 DuplicateKeyException，测不到业务错误码（同 P2 的处理口径）。
--   - bc_material_stock.uk_project_material：**建**。属 B 类（结构唯一），且 V1 的
--     「同项目同物料只有一行」是行锁热点成立的前提：没有它，并发首插会造出两行库存，
--     `SELECT ... FOR UPDATE` 就锁不到同一行，并发用例会假绿（同 P3 的判断）。
--
-- ========== 关于 H2 的 SELECT ... FOR UPDATE（V1 并发用例能成立的前提） ==========
--   H2 2.x 的 MVStore 引擎对 `SELECT ... FOR UPDATE` 提供**行级锁 + 锁等待**，
--   与 MySQL InnoDB 语义务一致（等待超时由 LOCK_TIMEOUT 控制，默认 1-2 秒，
--   V1 的扣减事务是毫秒级，10 线程排队不会触发超时；若将来用例变慢，
--   见 RequisitionServiceImplTest 里的 LOCK_TIMEOUT 说明）。
--   若某天换成 H2 1.x（表级锁），并发用例仍然会「过」，但测不出行级语义——
--   届时该用例的断言（成功 5 / 失败 5 / stock=0）依然成立，只是证明力下降。
-- =====================================================================================

DROP TABLE IF EXISTS "bc_requisition_item";
DROP TABLE IF EXISTS "bc_requisition";
DROP TABLE IF EXISTS "bc_stock_log";
DROP TABLE IF EXISTS "bc_material_stock";

-- ------------------------------------------------------------------------------------
-- 申领单（对应 sql/buildcloud/07-material.sql 的 bc_requisition）
--
-- status：0-草稿 1-已提交(已预扣) 2-已扣减 3-已出库 4-已完成 5-部分回退 8-已关闭 9-已取消
-- approval_status：-1-未开始 1-审批中 2-通过 3-不通过 4-已取消（与 BpmProcessInstanceStatusEnum 对齐）
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_requisition"
(
    "id"                  bigint      NOT NULL AUTO_INCREMENT,
    "req_no"              varchar(64) NOT NULL,
    "project_id"          bigint      NOT NULL,
    "applicant_user_id"   bigint      NOT NULL,
    "status"              tinyint     NOT NULL DEFAULT 0,
    "timeout_time"        timestamp   DEFAULT NULL,
    "approval_status"     tinyint     NOT NULL DEFAULT -1,
    "process_instance_id" varchar(64) DEFAULT NULL,
    -- W4-P5（对应 sql/buildcloud/08-requisition-approval.sql）：会签审批人 + 出库/退料时间
    "approvers"           varchar(255) DEFAULT NULL,
    "actual_out_time"     timestamp   DEFAULT NULL,
    "actual_return_time"  timestamp   DEFAULT NULL,
    "remark"              varchar(500) DEFAULT NULL,

    "tenant_id"           bigint      NOT NULL DEFAULT 0,
    "creator"             varchar(64) DEFAULT '',
    "create_time"         timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"             varchar(64) DEFAULT '',
    "update_time"         timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"             bit         NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);

-- 项目内申领单列表（索引形状与真库一致；H2 上不建也不影响语义，建了更贴近真库的执行计划）
CREATE INDEX IF NOT EXISTS "idx_project_id" ON "bc_requisition" ("project_id");
-- 待办与超时扫描：按状态筛，配合 timeout_time 找超时单
CREATE INDEX IF NOT EXISTS "idx_status" ON "bc_requisition" ("status");
CREATE INDEX IF NOT EXISTS "idx_status_timeout" ON "bc_requisition" ("status", "timeout_time");

-- ------------------------------------------------------------------------------------
-- 申领明细（对应 bc_requisition_item）
-- quantity：正数，decimal(18,4) 与 bc_material_stock.available_qty 同精度
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_requisition_item"
(
    "id"             bigint         NOT NULL AUTO_INCREMENT,
    "requisition_id" bigint         NOT NULL,
    "material_id"    bigint         NOT NULL,
    "quantity"       decimal(18, 4) NOT NULL DEFAULT 0.0000,
    -- W4-P5：累计已退料量（可退量 = quantity - returned_qty），精度必须与 quantity 一致
    "returned_qty"   decimal(18, 4) NOT NULL DEFAULT 0.0000,
    "remark"         varchar(500)   DEFAULT NULL,

    "tenant_id"      bigint         NOT NULL DEFAULT 0,
    "creator"        varchar(64)    DEFAULT '',
    "create_time"    timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"        varchar(64)    DEFAULT '',
    "update_time"    timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"        bit            NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "idx_requisition_id" ON "bc_requisition_item" ("requisition_id");
CREATE INDEX IF NOT EXISTS "idx_item_material_id" ON "bc_requisition_item" ("material_id");
-- W4-P5：明细行原子条件更新（WHERE returned_qty + ? <= quantity）按主键定位，无需额外索引；
-- 退料校验先按 requisition_id 取全部明细（走上面的 idx_requisition_id）。
CREATE INDEX IF NOT EXISTS "idx_requisition_process_instance" ON "bc_requisition" ("process_instance_id");

-- ------------------------------------------------------------------------------------
-- 项目库存（对应 bc_material_stock；字段与 stock-test-tables.sql 一致）
-- uk_project_material 必须建，理由见文件头「唯一索引的建/不建」
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_material_stock"
(
    "id"            bigint         NOT NULL AUTO_INCREMENT,
    "project_id"    bigint         NOT NULL,
    "material_id"   bigint         NOT NULL,
    "available_qty" decimal(18, 4) NOT NULL DEFAULT 0.0000,
    "locked_qty"    decimal(18, 4) NOT NULL DEFAULT 0.0000,
    "version"       int            NOT NULL DEFAULT 0,
    "remark"        varchar(500)   DEFAULT NULL,

    "tenant_id"     bigint         NOT NULL DEFAULT 0,
    "creator"       varchar(64)    DEFAULT '',
    "create_time"   timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"       varchar(64)    DEFAULT '',
    "update_time"   timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"       bit            NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id"),
    CONSTRAINT "uk_project_material" UNIQUE ("tenant_id", "project_id", "material_id")
);

CREATE INDEX IF NOT EXISTS "idx_stock_material_id" ON "bc_material_stock" ("material_id");

-- ------------------------------------------------------------------------------------
-- 库存流水（对应 bc_stock_log；字段与 stock-test-tables.sql 一致）
-- quantity 带符号：biz_type=2 申领扣减为负、biz_type=3 退回/biz_type=4 冲正为正
-- W4-P5 起本表新增「同一张单同一物料同一 biz_type 只有一条」的幂等判据，
-- 相关断言依赖 idx_project_biz（按 project_id + biz_no 圈定）。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_stock_log"
(
    "id"            bigint         NOT NULL AUTO_INCREMENT,
    "project_id"    bigint         NOT NULL,
    "material_id"   bigint         NOT NULL,
    "biz_type"      tinyint        NOT NULL,
    "biz_no"        varchar(64)    DEFAULT NULL,
    "quantity"      decimal(18, 4) NOT NULL,
    "balance_after" decimal(18, 4) NOT NULL DEFAULT 0.0000,
    "remark"        varchar(500)   DEFAULT NULL,

    "tenant_id"     bigint         NOT NULL DEFAULT 0,
    "creator"       varchar(64)    DEFAULT '',
    "create_time"   timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"       varchar(64)    DEFAULT '',
    "update_time"   timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"       bit            NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "idx_project_create" ON "bc_stock_log" ("project_id", "create_time");
CREATE INDEX IF NOT EXISTS "idx_project_biz" ON "bc_stock_log" ("project_id", "biz_no");
