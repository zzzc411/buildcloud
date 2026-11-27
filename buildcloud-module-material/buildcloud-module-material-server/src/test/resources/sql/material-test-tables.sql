-- =====================================================================================
-- 材料基础数据子域（W4-P2：目录 / 物料 / 供应商）单元测试专用 H2 建表脚本（MODE=MYSQL）
--
-- 为什么独立成文件（而不是写进 create_tables.sql）：
--   create_tables.sql / clean.sql 是 material 域多个并行 worker 的共享文件（P2 基础数据、
--   P3 库存流水、P4 申领），谁改谁冲突。故本子域自备脚本，测试类用
--   @Sql(scripts = "/sql/material-test-tables.sql", executionPhase = BEFORE_TEST_METHOD) 引入。
--
-- 幂等 / 测试隔离：
--   先 DROP 再 CREATE——clean.sql 故意为空（见该文件注释），表在每次测试方法前置阶段重建，
--   即天然「清数据」，用例之间不串数据。
--   （不能写成「先 DELETE 再 CREATE」：首次执行时表还不存在，DELETE 直接报 Table not found。）
--
-- 约束：字段与 sql/buildcloud/07-material.sql 的三张表保持一致（列名/类型/可空性/默认值），
--       改生产建表脚本时本文件同步；不一致会导致「单测绿、真库红」。
--
-- 为什么这里**不建** uk_material_category_code / uk_material_no / uk_supplier_no：
--   编码唯一性是**业务口径**，由 Service 层查重后抛业务错误码
--   （MATERIAL_CATEGORY_NAME_DUPLICATE / MATERIAL_NO_DUPLICATE / SUPPLIER_NO_DUPLICATE），
--   单测要能构造「重复时返回上述错误码」的场景；真库上那几个唯一索引只是并发兜底。
--   若在 H2 建了唯一索引，重复插入会先抛 DuplicateKeyException，测不到业务错误码。
--   （同 project 模块 create_tables.sql 对 uk_project_user 的处理口径。）
-- =====================================================================================

DROP TABLE IF EXISTS "bc_material";
DROP TABLE IF EXISTS "bc_material_category";
DROP TABLE IF EXISTS "bc_supplier";

-- ------------------------------------------------------------------------------------
-- 材料目录（树形，对应 sql/buildcloud/07-material.sql 的 bc_material_category）
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_material_category"
(
    "id"         bigint       NOT NULL AUTO_INCREMENT,
    "parent_id"  bigint       NOT NULL DEFAULT 0,
    "name"       varchar(100) NOT NULL,
    "code"       varchar(64)  NOT NULL,
    "sort"       int          NOT NULL DEFAULT 0,
    "status"     tinyint      NOT NULL DEFAULT 0,
    "remark"     varchar(500) DEFAULT NULL,

    "tenant_id"  bigint       NOT NULL DEFAULT 0,
    "creator"    varchar(64)  DEFAULT '',
    "create_time" timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"    varchar(64)  DEFAULT '',
    "update_time" timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"    bit          NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);

-- ------------------------------------------------------------------------------------
-- 物料（对应 bc_material）
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_material"
(
    "id"          bigint       NOT NULL AUTO_INCREMENT,
    "material_no" varchar(64)  NOT NULL,
    "name"        varchar(200) NOT NULL,
    "spec"        varchar(200) DEFAULT NULL,
    "unit"        varchar(32)  NOT NULL,
    "category_id" bigint       DEFAULT NULL,
    "status"      tinyint      NOT NULL DEFAULT 0,
    "remark"      varchar(500) DEFAULT NULL,

    "tenant_id"   bigint       NOT NULL DEFAULT 0,
    "creator"     varchar(64)  DEFAULT '',
    "create_time" timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"     varchar(64)  DEFAULT '',
    "update_time" timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"     bit          NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);

-- ------------------------------------------------------------------------------------
-- 供应商（对应 bc_supplier）
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS "bc_supplier"
(
    "id"             bigint       NOT NULL AUTO_INCREMENT,
    "supplier_no"    varchar(64)  NOT NULL,
    "name"           varchar(200) NOT NULL,
    "contact_person" varchar(64)  DEFAULT NULL,
    "contact_phone"  varchar(32)  DEFAULT NULL,
    "address"        varchar(500) DEFAULT NULL,
    "status"         tinyint      NOT NULL DEFAULT 0,
    "remark"         varchar(500) DEFAULT NULL,

    "tenant_id"      bigint       NOT NULL DEFAULT 0,
    "creator"        varchar(64)  DEFAULT '',
    "create_time"    timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updater"        varchar(64)  DEFAULT '',
    "update_time"    timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted"        bit          NOT NULL DEFAULT FALSE,

    PRIMARY KEY ("id")
);
