-- =====================================================================================
-- 单元测试 H2 建表脚本（MODE=MYSQL）
-- 说明：BaseDbUnitTest 走 H2 内存库，schema 由本文件初始化（见 application-unit-test.yaml
--       的 spring.sql.init.schema-locations）。
-- 约束：本文件只建「单测真正用到的表」，字段与 sql/buildcloud/01-project.sql 保持一致；
--       新增/修改业务列时两处同步，否则单测与真实库会产生漂移。
-- 归属：本文件由 W3-P2（project 子域）维护；contract 子域如需建表，另建 create_tables_contract.sql
--       并在 application-unit-test.yaml 里追加 schema-locations，避免两个并行 worker 改同一文件。
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
    -- 立项审批两列（对应 sql/buildcloud/04-project-approval.sql）：W3-P4 新增，
    -- approval_status 的 DEFAULT -1 必须与真实库一致，否则「未开始」在单测里会变 NULL 而掩盖判空缺陷
    "process_instance_id" varchar(64)  DEFAULT NULL,
    "approval_status"     tinyint      NOT NULL DEFAULT -1,

    "tenant_id"         bigint         NOT NULL DEFAULT 0,
    "creator"           varchar(64)    DEFAULT '',
    "create_time"       timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"           varchar(64)    DEFAULT '',
    "update_time"       timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"           bit            NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);

-- ------------------------------------------------------------------------------------
-- 项目里程碑（对应 sql/buildcloud/01-project.sql 的 bc_project_milestone）
-- weight 用 decimal(5,2) 才能在单测里暴露「精度/区间」问题（服务层校验 0~100 且合计 ≤100）
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_project_milestone"
(
    "id"             bigint        NOT NULL AUTO_INCREMENT,
    "project_id"     bigint        NOT NULL,
    "milestone_name" varchar(200)  NOT NULL,
    "plan_date"      date          DEFAULT NULL,
    "actual_date"    date          DEFAULT NULL,
    "weight"         decimal(5, 2) NOT NULL DEFAULT 0.00,
    "status"         tinyint       NOT NULL DEFAULT 0,
    "sort"           int           NOT NULL DEFAULT 0,
    "remark"         varchar(500)  DEFAULT NULL,

    "tenant_id"      bigint        NOT NULL DEFAULT 0,
    "creator"        varchar(64)   DEFAULT '',
    "create_time"    timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"        varchar(64)   DEFAULT '',
    "update_time"    timestamp     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"        bit           NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);

-- ------------------------------------------------------------------------------------
-- 项目成员（对应 sql/buildcloud/01-project.sql 的 bc_project_member）
-- 注意：H2 这里不建 uk_project_user 唯一索引——唯一性由 Service 层前置校验（返回业务错误码），
--       单测要能构造「重复添加被拒」的场景；真实库的索引是兜底，不承担业务口径。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_project_member"
(
    "id"           bigint       NOT NULL AUTO_INCREMENT,
    "project_id"   bigint       NOT NULL,
    "user_id"      bigint       NOT NULL,
    "project_role" tinyint      NOT NULL DEFAULT 9,
    "remark"       varchar(500) DEFAULT NULL,

    "tenant_id"    bigint       NOT NULL DEFAULT 0,
    "creator"      varchar(64)  DEFAULT '',
    "create_time"  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"      varchar(64)  DEFAULT '',
    "update_time"  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"      bit          NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);
