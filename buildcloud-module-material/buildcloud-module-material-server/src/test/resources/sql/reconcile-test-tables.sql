-- =====================================================================================
-- 库存对账（W5-P4）单元测试专用 H2 建表脚本（MODE=MySQL）
--
-- 为什么独立成文件（而不是写进 create_tables.sql / 复用 requisition-test-tables.sql）：
--   对账用例既要**建申领单与明细**（判断单据状态）、又要**验证库存与流水**，
--   还要 bc_reconcile_log（快照表）。三类表必须在同一个 @Sql 里成套 DROP+CREATE，
--   否则「对账日志删了库存没删」这类半清理会让用例之间串数据（同 P4 的判断）。
--   直接改 requisition-test-tables.sql 会污染 W5-P2/P3 的验收口径（那两个测试类共用它），
--   故本 Phase 自备脚本（约定见 P2/P3 的文件头注释）。
--
-- 幂等 / 测试隔离：
--   先 DROP 再 CREATE —— clean.sql 故意为空，表在每次测试方法前置阶段重建，即天然「清数据」。
--
-- 约束：字段与 sql/buildcloud/07-material.sql（bc_requisition / bc_requisition_item /
--       bc_material_stock / bc_stock_log）与 10-reconcile-log.sql（bc_reconcile_log）
--       **严格一致**（列名/类型/可空性/默认值/精度）。不一致会导致「单测绿、真库红」。
--
-- 唯一索引口径（与 requisition-test-tables.sql 完全一致，ADR-004）：
--   bc_requisition.uk_req_no 不建（A 类）；bc_material_stock.uk_project_material 建（B 类）。
-- =====================================================================================

DROP TABLE IF EXISTS "bc_reconcile_log";
DROP TABLE IF EXISTS "bc_requisition_item";
DROP TABLE IF EXISTS "bc_requisition";
DROP TABLE IF EXISTS "bc_stock_log";
DROP TABLE IF EXISTS "bc_material_stock";

-- ------------------------------------------------------------------------------------
-- 申领单（对应 bc_requisition；status 0/1/2/3/4/5/8/9）
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

CREATE INDEX IF NOT EXISTS "idx_project_id" ON "bc_requisition" ("project_id");
CREATE INDEX IF NOT EXISTS "idx_status" ON "bc_requisition" ("status");
CREATE INDEX IF NOT EXISTS "idx_status_timeout" ON "bc_requisition" ("status", "timeout_time");
CREATE INDEX IF NOT EXISTS "idx_requisition_process_instance" ON "bc_requisition" ("process_instance_id");

-- ------------------------------------------------------------------------------------
-- 申领明细（对应 bc_requisition_item）
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_requisition_item"
(
    "id"             bigint         NOT NULL AUTO_INCREMENT,
    "requisition_id" bigint         NOT NULL,
    "material_id"    bigint         NOT NULL,
    "quantity"       decimal(18, 4) NOT NULL DEFAULT 0.0000,
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

-- ------------------------------------------------------------------------------------
-- 项目库存（对应 bc_material_stock；uk_project_material 必须建，理由见 requisition-test-tables.sql）
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
-- 库存流水（对应 bc_stock_log；quantity 带符号）
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

-- ------------------------------------------------------------------------------------
-- 对账快照（对应 sql/buildcloud/10-reconcile-log.sql 的 bc_reconcile_log）
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_reconcile_log"
(
    "id"          bigint        NOT NULL AUTO_INCREMENT,
    "batch_no"    varchar(64)   NOT NULL,
    "check_time"  timestamp     NOT NULL,
    "check_type"  tinyint       NOT NULL,
    "diff_count"  int           NOT NULL DEFAULT 0,
    "fixed_count" int           NOT NULL DEFAULT 0,
    "status"      tinyint       NOT NULL DEFAULT 0,
    "detail_json" varchar(4000) DEFAULT NULL,

    "tenant_id"   bigint        NOT NULL DEFAULT 0,
    "creator"     varchar(64)   DEFAULT '',
    "create_time" timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"     varchar(64)   DEFAULT '',
    "update_time" timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"     bit           NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "idx_batch_no" ON "bc_reconcile_log" ("batch_no");
CREATE INDEX IF NOT EXISTS "idx_type_time" ON "bc_reconcile_log" ("check_type", "check_time");
CREATE INDEX IF NOT EXISTS "idx_check_time" ON "bc_reconcile_log" ("check_time");
