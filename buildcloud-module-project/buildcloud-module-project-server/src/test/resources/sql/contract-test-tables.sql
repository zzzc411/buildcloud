-- =====================================================================================
-- 合同子域（com.buildcloud.contract）单元测试专用 H2 建表脚本（MODE=MYSQL）
--
-- 为什么独立成文件（而不是加进 create_tables.sql）：
--   create_tables.sql / clean.sql 由 W3-P2（project 子域）统一维护，并行 worker 同时改会产生
--   写冲突；合同子域的表由本文件自备，测试类通过
--   @Sql(scripts = "/sql/contract-test-tables.sql") 自行准备。
--
-- 幂等 / 测试隔离：
--   先 DROP 再 CREATE——BaseDbUnitTest 的 AFTER_TEST_METHOD 只清 bc_project，不会清合同 4 表，
--   故本文件在每次测试方法前置阶段重建合同表，天然起到「清数据」作用，用例之间不串数据。
--   （不能写成「先 DELETE 再 CREATE」：首次执行时表还不存在，DELETE 直接报 Table not found。）
--
-- 约束：字段与 sql/buildcloud/03-contract.sql 保持一致（列名/类型/可空性/默认值），
--       改动生产建表脚本时本文件同步；不一致会导致「单测绿、真库红」。
-- =====================================================================================

DROP TABLE IF EXISTS "bc_contract_payment_plan";
DROP TABLE IF EXISTS "bc_contract_payment_record";
DROP TABLE IF EXISTS "bc_contract_change";
DROP TABLE IF EXISTS "bc_contract";

CREATE TABLE IF NOT EXISTS "bc_contract"
(
    "id"            bigint         NOT NULL AUTO_INCREMENT,
    "contract_no"   varchar(64)    NOT NULL,
    "contract_name" varchar(200)   NOT NULL,
    "project_id"    bigint         DEFAULT NULL,
    "direction"     tinyint        NOT NULL DEFAULT 1,
    "amount"        decimal(18, 2) NOT NULL DEFAULT 0.00,
    "sign_date"     date           DEFAULT NULL,
    "status"        tinyint        NOT NULL DEFAULT 0,
    "party_a"       varchar(200)   DEFAULT NULL,
    "party_b"       varchar(200)   DEFAULT NULL,
    "paid_amount"   decimal(18, 2) NOT NULL DEFAULT 0.00,
    "remark"        varchar(500)   DEFAULT NULL,
    -- 进度款审批关联列（W3-P5，与 sql/buildcloud/06-contract-approval.sql 一致）
    "process_instance_id"     varchar(64)    DEFAULT NULL,
    "approval_status"         tinyint        NOT NULL DEFAULT -1,
    "pending_payment_amount"  decimal(18, 2) DEFAULT NULL,
    "pending_period"          int            DEFAULT NULL,

    "tenant_id"     bigint         NOT NULL DEFAULT 0,
    "creator"       varchar(64)    DEFAULT '',
    "create_time"   timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"       varchar(64)    DEFAULT '',
    "update_time"   timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"       bit            NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id"),
    CONSTRAINT "uk_contract_no" UNIQUE ("tenant_id", "contract_no")
);

CREATE TABLE IF NOT EXISTS "bc_contract_payment_plan"
(
    "id"          bigint         NOT NULL AUTO_INCREMENT,
    "contract_id" bigint         NOT NULL,
    "period"      int            NOT NULL,
    "plan_amount" decimal(18, 2) NOT NULL DEFAULT 0.00,
    "plan_date"   date           DEFAULT NULL,
    "status"      tinyint        NOT NULL DEFAULT 0,
    "remark"      varchar(500)   DEFAULT NULL,

    "tenant_id"   bigint         NOT NULL DEFAULT 0,
    "creator"     varchar(64)    DEFAULT '',
    "create_time" timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"     varchar(64)    DEFAULT '',
    "update_time" timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"     bit            NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id"),
    CONSTRAINT "uk_contract_period" UNIQUE ("tenant_id", "contract_id", "period")
);

CREATE TABLE IF NOT EXISTS "bc_contract_payment_record"
(
    "id"          bigint         NOT NULL AUTO_INCREMENT,
    "contract_id" bigint         NOT NULL,
    "approval_no" varchar(64)    DEFAULT NULL,
    "amount"      decimal(18, 2) NOT NULL DEFAULT 0.00,
    "pay_date"    date           DEFAULT NULL,
    "type"        tinyint        NOT NULL DEFAULT 1,
    "remark"      varchar(500)   DEFAULT NULL,

    "tenant_id"   bigint         NOT NULL DEFAULT 0,
    "creator"     varchar(64)    DEFAULT '',
    "create_time" timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"     varchar(64)    DEFAULT '',
    "update_time" timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"     bit            NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);

CREATE TABLE IF NOT EXISTS "bc_contract_change"
(
    "id"            bigint         NOT NULL AUTO_INCREMENT,
    "contract_id"   bigint         NOT NULL,
    "type"          tinyint        NOT NULL DEFAULT 1,
    "change_amount" decimal(18, 2) NOT NULL DEFAULT 0.00,
    "change_reason" varchar(500)   DEFAULT NULL,
    "approval_no"   varchar(64)    DEFAULT NULL,
    "remark"        varchar(500)   DEFAULT NULL,

    "tenant_id"     bigint         NOT NULL DEFAULT 0,
    "creator"       varchar(64)    DEFAULT '',
    "create_time"   timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"       varchar(64)    DEFAULT '',
    "update_time"   timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"       bit            NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);
