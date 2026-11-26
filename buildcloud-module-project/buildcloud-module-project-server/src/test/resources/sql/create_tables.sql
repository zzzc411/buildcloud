-- =====================================================================================
-- 单元测试 H2 建表脚本（MODE=MYSQL）
-- 说明：BaseDbUnitTest 走 H2 内存库，schema 由本文件初始化（见 application-unit-test.yaml
--       的 spring.sql.init.schema-locations）。
-- 约束：本文件只建「单测真正用到的表」，字段与 sql/buildcloud/01-project.sql 保持一致；
--       新增/修改业务列时两处同步，否则单测与真实库会产生漂移。
-- =====================================================================================

CREATE TABLE IF NOT EXISTS "bc_project"
(
    "id"                bigint         NOT NULL AUTO_INCREMENT,
    "project_no"        varchar(64)    NOT NULL,
    "project_name"      varchar(200)   NOT NULL,
    "project_type"      tinyint        NOT NULL,
    "status"            tinyint        NOT NULL DEFAULT 0,
    "province_code"     varchar(32)    DEFAULT NULL,
    "province_name"     varchar(64)    DEFAULT NULL,
    "city_code"         varchar(32)    DEFAULT NULL,
    "city_name"         varchar(64)    DEFAULT NULL,
    "address"           varchar(500)   DEFAULT NULL,
    "build_unit"        varchar(200)   DEFAULT NULL,
    "contract_amount"   decimal(18, 2) DEFAULT 0.00,
    "plan_start_date"   date           DEFAULT NULL,
    "plan_end_date"     date           DEFAULT NULL,
    "actual_start_date" date           DEFAULT NULL,
    "actual_end_date"   date           DEFAULT NULL,
    "manager_user_id"   bigint         DEFAULT NULL,
    "dept_id"           bigint         DEFAULT NULL,
    "remark"            varchar(500)   DEFAULT NULL,

    "tenant_id"         bigint         NOT NULL DEFAULT 0,
    "creator"           varchar(64)    DEFAULT '',
    "create_time"       timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"           varchar(64)    DEFAULT '',
    "update_time"       timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"           bit            NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);
