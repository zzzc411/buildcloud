-- =====================================================================================
-- 库存子域（W4-P3：库存 / 库存流水）单元测试专用 H2 建表脚本（MODE=MYSQL）
--
-- 为什么独立成文件（而不是写进 create_tables.sql）：
--   create_tables.sql / clean.sql 是 material 域多个并行 worker 的共享文件（P2 基础数据、
--   P3 库存流水、P4 申领），谁改谁冲突。故本子域自备脚本，测试类用
--   @Sql(scripts = "/sql/stock-test-tables.sql", executionPhase = BEFORE_TEST_METHOD) 引入。
--   （约定与 P2 的 material-test-tables.sql 一致，见那两个文件的注释。）
--
-- 幂等 / 测试隔离：
--   先 DROP 再 CREATE —— clean.sql 故意为空（见该文件注释），表在每次测试方法前置阶段重建，
--   即天然「清数据」，用例之间不串数据。
--   （不能写成「先 DELETE 再 CREATE」：首次执行时表还不存在，DELETE 直接报 Table not found。）
--
-- 约束：字段与 sql/buildcloud/07-material.sql 的 bc_material_stock / bc_stock_log 严格一致
--       （列名/类型/可空性/默认值/精度），改生产建表脚本时本文件同步；
--       不一致会导致「单测绿、真库红」——尤其 decimal(18,4) 若写成 decimal(18,2)，
--       精度截断类断言会假绿。
--
-- 关于唯一索引 uk_project_material（**本文件故意建了**，与 P2 的做法不同，理由如下）：
--   P2 的 category/material/supplier 唯一性是**业务口径**（编码重复要返回业务错误码
--   MATERIAL_NO_DUPLICATE），所以 H2 不建唯一索引，否则先抛 DuplicateKeyException、测不到错误码。
--   而库存的「同项目同物料唯一」是**并发正确性的物理兜底**：StockServiceImpl 的首次入库并发路径
--   正是"插入撞唯一键 → 捕 DuplicateKeyException → 转入更新路径"，不建索引这条分支永远测不到，
--   真正跑起来还会因并发首插产生两行库存（超卖温床）。
--   故这里按生产口径建 uk，并专门用 testInbound_concurrentFirstInsert_singleStockRow 验证它。
--
-- 为什么不带 tenant_id 索引前缀：H2 单测不启用多租户插件（无 TenantContextHolder），
--   tenant_id 恒为列默认值 0，加了前缀不影响语义；但真库 uk 是 (tenant_id, project_id, material_id)，
--   这里保留三列形式以贴近真实约束形状。
-- =====================================================================================

DROP TABLE IF EXISTS "bc_stock_log";
DROP TABLE IF EXISTS "bc_material_stock";

-- ------------------------------------------------------------------------------------
-- 项目库存（对应 sql/buildcloud/07-material.sql 的 bc_material_stock）
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
    -- 同项目同物料唯一：并发首插的物理兜底（见文件头说明）
    CONSTRAINT "uk_project_material" UNIQUE ("tenant_id", "project_id", "material_id")
);

-- 反向查询：某物料在各项目的库存分布
CREATE INDEX IF NOT EXISTS "idx_material_id" ON "bc_material_stock" ("material_id");

-- ------------------------------------------------------------------------------------
-- 库存流水（对应 sql/buildcloud/07-material.sql 的 bc_stock_log）
--
-- id 在真库注释里约定「应用层用 ASSIGN_ID 雪花 ID 填充」，但 DO 未写死 @TableId(type=...),
-- 而是沿用模块级 id-type 配置（单测 AUTO）——故这里保持 AUTO_INCREMENT，
-- 单测里断言时不要写死 id 值。
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

-- 分片内查询主路径：某项目某时间段的流水
CREATE INDEX IF NOT EXISTS "idx_project_create" ON "bc_stock_log" ("project_id", "create_time");
-- 幂等/追溯：按业务单号反查本单产生的全部流水（含冲正）
CREATE INDEX IF NOT EXISTS "idx_project_biz" ON "bc_stock_log" ("project_id", "biz_no");
