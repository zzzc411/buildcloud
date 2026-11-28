-- =====================================================================================
-- 筑云 BuildCloud · 材料域（buildcloud-module-material）建表脚本
-- 表：bc_material_category（树形目录）、bc_material（物料）、bc_supplier（供应商）、
--     bc_material_stock（库存）、bc_requisition（申领单）、bc_requisition_item（申领明细）、
--     bc_stock_log（库存流水，W6 分库分表主表）
-- 依据：spec v1.2 §5.2（材料域实体，逐字段对照）+ §6.1（V1/V2 申领链路）
--       + §6.3（分库分表：bc_stock_log 按 project_id 哈希 4 库 8 表）
--       + §7.1（核心表清单：material 域 7 张）+ AGENTS.md §4（BaseDO 六字段）
-- 可重复执行：全部 CREATE TABLE IF NOT EXISTS，不含 DROP；
--             索引/唯一键内联在建表语句里（表已存在则整句跳过，不会重复建索引）。
-- 执行：
--   docker cp sql/buildcloud/07-material.sql bc-mysql:/tmp/07-material.sql
--   docker exec bc-mysql sh -c 'mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 \
--       -D ruoyi-vue-pro < /tmp/07-material.sql'
--   ⚠️ PowerShell 不支持 `<` 重定向，重定向必须放进容器内 shell（lessons-learned L1-1b）；
--   ⚠️ 必须带 --default-character-set=utf8mb4，否则中文表/列注释与数据双重编码且不可逆（L2-10）。
-- 精度约定：材料数量可能有小数（吨/立方/米），一律 decimal(18,4)；
--           本域无金额列（材料金额属报表域聚合口径）。
-- 命名说明：文件序号 07 —— README 早先预留的 `04-material.sql` 号段已被
--           `04-project-approval.sql` / `05-bpm-seed.sql` / `06-contract-approval.sql` 占用，
--           按「每个文件只增不改」约定顺延取下一个空号。
--
-- 【唯一索引口径 —— ADR-004】本文件保留 `CREATE TABLE` 内的唯一索引作为新建库的初始防线；
--   生产口径由 `09-unique-index-realign.sql` 归一。本域涉及：
--     A 类 → bc_material_category.uk_material_category_code、bc_material.uk_material_no、
--            bc_supplier.uk_supplier_no、bc_requisition.uk_req_no
--            （归一后为普通索引 idx_* / 同名普通索引，允许「删后同编码重建」）
--     B 类 → bc_material_stock.uk_project_material（保留唯一索引：W5 并发热点行的并发防线）
--   详见 docs/decisions/004-unique-index-with-logical-delete.md。
-- =====================================================================================

-- ------------------------------------------------------------------------------------
-- 1) bc_material_category —— 材料目录（树形）
--    spec §5.2：树形目录。parent_id = 0 表示根节点；code 为目录编码，租户内唯一，
--    供导入/对账时稳定引用（名称可能改，编码不改）。
--    uk(tenant_id, code)：同租户内编码不重复；删除父节点前由业务层校验无子节点
--    （错误码 MATERIAL_CATEGORY_HAS_CHILDREN）。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_material_category`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '目录编号（主键）',
    `parent_id`   bigint       NOT NULL DEFAULT 0 COMMENT '父目录编号（0 表示根节点）',
    `name`        varchar(100) NOT NULL COMMENT '目录名称',
    `code`        varchar(64)  NOT NULL COMMENT '目录编码（租户内唯一，导入/对账用稳定键）',
    `sort`        int          NOT NULL DEFAULT 0 COMMENT '排序号（同级内升序展示）',
    `status`      tinyint      NOT NULL DEFAULT 0 COMMENT '状态：0-开启 1-停用（CommonStatusEnum）',
    `remark`      varchar(500) DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段（基座 MyBatis Plus + 多租户 + 逻辑删除强制约定；DO extends TenantBaseDO，AGENTS R-8）----
    `tenant_id`   bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 目录编码租户内唯一（逻辑删除行不参与唯一性判断由业务层查重时过滤 deleted）
    -- **（A 类：逻辑删除后允许同编码重建，见 ADR-004；生产口径由 09-unique-index-realign.sql 归一）**
    -- 归一后为普通索引 idx_material_category_code，唯一性由 Service 层「查全量含已删行」承担；
    -- 这是 ADR-004 的直接触发点（W4-P2 实测：删 STEEL 再建 STEEL 曾返 500）。
    UNIQUE KEY `uk_material_category_code` (`tenant_id`, `code`) USING BTREE,
    -- 树形加载主路径：按 parent_id 查直接子节点（一次查全树 = 查全表后内存组树）
    KEY `idx_parent_id` (`parent_id`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '材料目录表（树形）';

-- ------------------------------------------------------------------------------------
-- 2) bc_material —— 物料主数据
--    spec §5.2：编码/名称/规格/单位/目录。
--    material_no 为业务编码（租户内唯一），入库/申领/流水全链路以它做人工可读标识；
--    unit 存单位名称快照（个/吨/米/立方），不另设单位字典表（spec 未要求）；
--    category_id 逻辑关联 bc_material_category.id，不建物理外键（与项目域口径一致）。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_material`
(
    `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '物料编号（主键）',
    `material_no`  varchar(64)  NOT NULL COMMENT '物料编码（业务编码，租户内唯一）',
    `name`         varchar(200) NOT NULL COMMENT '物料名称',
    `spec`         varchar(200) DEFAULT NULL COMMENT '规格型号（如 HRB400 Φ20）',
    `unit`         varchar(32)  NOT NULL COMMENT '计量单位（个/吨/米/立方…）',
    `category_id`  bigint       DEFAULT NULL COMMENT '所属目录（bc_material_category.id）',
    `status`       tinyint      NOT NULL DEFAULT 0 COMMENT '状态：0-开启 1-停用（停用后不可申领）',
    `remark`       varchar(500) DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`    bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`      varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`      varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 物料编码租户内唯一（申领/入库按编码反查的唯一性前提）
    -- **（A 类：逻辑删除后允许同编码重建，见 ADR-004；生产口径由 09-unique-index-realign.sql 归一）**
    -- 归一后为普通索引 idx_material_no，唯一性由 Service 层「查全量含已删行」承担。
    UNIQUE KEY `uk_material_no` (`tenant_id`, `material_no`) USING BTREE,
    -- 按目录筛物料（目录树点击后的列表主路径）
    KEY `idx_category_id` (`category_id`) USING BTREE,
    -- 物料名称模糊查询与分页排序的常见路径
    KEY `idx_name` (`name`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '物料表';

-- ------------------------------------------------------------------------------------
-- 3) bc_supplier —— 供应商
--    spec §5.2：供应商。supplier_no 为业务编码（租户内唯一）。
--    contact_person / contact_phone / address 为联系人快照（不做联系人子表，spec 未要求）。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_supplier`
(
    `id`             bigint       NOT NULL AUTO_INCREMENT COMMENT '供应商编号（主键）',
    `supplier_no`    varchar(64)  NOT NULL COMMENT '供应商编码（业务编码，租户内唯一）',
    `name`           varchar(200) NOT NULL COMMENT '供应商名称',
    `contact_person` varchar(64)  DEFAULT NULL COMMENT '联系人',
    `contact_phone`  varchar(32)  DEFAULT NULL COMMENT '联系电话',
    `address`        varchar(500) DEFAULT NULL COMMENT '联系地址',
    `status`         tinyint      NOT NULL DEFAULT 0 COMMENT '状态：0-开启 1-停用',
    `remark`         varchar(500) DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`      bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`        varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 供应商编码租户内唯一
    -- **（A 类：逻辑删除后允许同编码重建，见 ADR-004；生产口径由 09-unique-index-realign.sql 归一）**
    -- 归一后为普通索引 idx_supplier_no。
    UNIQUE KEY `uk_supplier_no` (`tenant_id`, `supplier_no`) USING BTREE,
    KEY `idx_name` (`name`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '供应商表';

-- ------------------------------------------------------------------------------------
-- 4) bc_material_stock —— 项目库存（核心表）
--    spec §5.2：库存：项目+物料 唯一，available_qty / locked_qty / version 乐观锁。
--
--    uk(tenant_id, project_id, material_id) 是 W5 并发热点的前提：
--      - 同一项目同一物料只有一行，扣减才可能落到同一行上形成热点 → V1 行锁串行化对照、
--        V2 Redis Lua 预扣 + DB 乐观锁最后防线，都以「这一行」为操作对象；
--      - 若允许多行，并发扣减会分散到不同行，既测不出热点，也无法用一条 UPDATE 兜住超卖。
--
--    available_qty / locked_qty 拆分（预扣语义）：
--      available_qty = 可申领量（V1 只读写它）；locked_qty = 已提交待审批的预留量（W5 预扣链路用）。
--      这样「已提交未审批」的量不会被别的申领单看到，同时不必先扣 available 再回补（回补窗口会产生超卖）。
--
--    version：MyBatis Plus @Version 乐观锁列，入库/出库扣减走
--      `UPDATE ... SET available_qty = available_qty - ?, version = version + 1
--       WHERE id = ? AND version = ? AND available_qty >= ?`
--      —— 影响行数 0 即冲突/不足，由业务层重试或报 STOCK_NOT_ENOUGH（spec §6.1 防超卖第二道防线）。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_material_stock`
(
    `id`             bigint         NOT NULL AUTO_INCREMENT COMMENT '库存编号（主键）',
    `project_id`     bigint         NOT NULL COMMENT '所属项目（bc_project.id）',
    `material_id`    bigint         NOT NULL COMMENT '物料（bc_material.id）',
    `available_qty`  decimal(18, 4) NOT NULL DEFAULT 0.0000 COMMENT '可用库存数量（V1 扣减对象）',
    `locked_qty`     decimal(18, 4) NOT NULL DEFAULT 0.0000 COMMENT '锁定数量（已提交待审批的预扣量，W5 使用）',
    `version`        int            NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（MyBatis Plus @Version）',
    `remark`         varchar(500)   DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`      bigint         NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`        varchar(64)    DEFAULT '' COMMENT '创建者',
    `create_time`    datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)    DEFAULT '' COMMENT '更新者',
    `update_time`    datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        bit(1)         NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 同项目同物料唯一：并发热点行 + 扣减原子性的前提（W5 压测对照组）
    -- **（B 类：结构唯一 —— 重复即数据错误，按 ADR-004 保留数据库唯一索引作并发防线；09 脚本不动它）**
    UNIQUE KEY `uk_project_material` (`tenant_id`, `project_id`, `material_id`) USING BTREE,
    -- 反向查询：某物料在各项目的库存分布（跨项目调拨/汇总）
    KEY `idx_material_id` (`material_id`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '项目库存表（项目+物料唯一，乐观锁）';

-- ------------------------------------------------------------------------------------
-- 5) bc_requisition —— 材料申领单
--    spec §5.2：req_no 全局唯一 / 项目 / 申请人 / 状态 / 超时时间。
--    状态机（spec §5.2 原文）：
--      草稿0 → 已提交1(已预扣) → 已扣减2 → 已出库3 → 已完成4
--                 ├ 审批驳回 → 已取消9（回滚预扣 + 冲正流水）
--                 └ 超时未审完 → 已关闭8（回滚预扣 + 冲正流水）
--      退料 → 部分回退5
--    8/9 为两个不同的终止态（超时 vs 驳回），分开存才能在报表里区分「运营自动关单」与「审批拒绝」。
--
--    timeout_time：提交时写入「当前时间 + 30 分钟」；W5 以 RocketMQ 延迟消息在到期时扫描
--      status 仍为 1（已提交）的单据并关单（spec §6.1 第 ⑥ 步）。列存在即让延迟消息
--      可以「到期后再核对一次 timeout_time」，避免消息早到/重投导致误关单。
--
--    approval_status / process_instance_id：复用基座 BpmProcessInstanceStatusEnum 值域与
--      Flowable 实例 id（与 bc_project 口径完全一致，见 04-project-approval.sql），
--      保证审批域跨业务域只有一套口径。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_requisition`
(
    `id`                 bigint       NOT NULL AUTO_INCREMENT COMMENT '申领单编号（主键）',
    `req_no`             varchar(64)  NOT NULL COMMENT '申领单号（业务编码，租户内唯一；MQ 消费幂等键）',
    `project_id`         bigint       NOT NULL COMMENT '所属项目（bc_project.id，数据权限维度）',
    `applicant_user_id`  bigint       NOT NULL COMMENT '申请人（system_users.id）',
    `status`             tinyint      NOT NULL DEFAULT 0 COMMENT '申领状态：0-草稿 1-已提交(已预扣) 2-已扣减 3-已出库 4-已完成 5-部分回退 8-已关闭(超时) 9-已取消(驳回)',
    `timeout_time`       datetime     DEFAULT NULL COMMENT '超时关闭时间（提交时 = 当前时间 + 30 分钟，W5 延迟消息据此关单）',
    `approval_status`    tinyint      NOT NULL DEFAULT -1 COMMENT '审批状态：-1-未开始 1-审批中 2-通过 3-不通过 4-已取消（BpmProcessInstanceStatusEnum）',
    `process_instance_id` varchar(64)  DEFAULT NULL COMMENT '审批流程实例编号（ACT_HI_PROCINST.PROC_INST_ID_）',
    `remark`             varchar(500) DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`          bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`            varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`            varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`            bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 申领单号租户内唯一：MQ 消费者幂等（spec §6.1「以 req_no 唯一索引防重复投递」）
    -- **（A 类：逻辑删除后允许同编码重建，见 ADR-004；生产口径由 09-unique-index-realign.sql 归一）**
    -- ⚠️ 归一后为普通索引 idx_req_no：**幂等不能再依赖本索引**，须由
    --   ① 单据状态 CAS（`UPDATE ... WHERE status = 期望值`，影响行数 0 即幂等返回）；
    --   ② 流水的 (biz_no, biz_type) 业务键；③ 单号随机段三重兜底。W5 的 MQ 消费者必须走 ①。
    UNIQUE KEY `uk_req_no` (`tenant_id`, `req_no`) USING BTREE,
    -- 项目内申领单列表（项目详情/数据权限过滤后的主路径）
    KEY `idx_project_id` (`project_id`) USING BTREE,
    -- 待办与超时扫描：按状态筛「已提交(1)」，配合 timeout_time 找超时单
    KEY `idx_status` (`status`) USING BTREE,
    KEY `idx_create_time` (`create_time`) USING BTREE,
    -- 超时关闭 Job / 延迟消息的主路径：status + timeout_time（避免全表扫待办）
    KEY `idx_status_timeout` (`status`, `timeout_time`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '材料申领单表';

-- ------------------------------------------------------------------------------------
-- 6) bc_requisition_item —— 申领明细
--    spec §5.2：明细：申领单/物料/数量。
--    一张申领单含多行物料；quantity 用 decimal(18,4) 与库存同精度，
--    避免「明细 4 位、库存 2 位」在累加时产生精度漂移导致对账不平。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_requisition_item`
(
    `id`             bigint         NOT NULL AUTO_INCREMENT COMMENT '明细编号（主键）',
    `requisition_id` bigint         NOT NULL COMMENT '所属申领单（bc_requisition.id）',
    `material_id`    bigint         NOT NULL COMMENT '物料（bc_material.id）',
    `quantity`       decimal(18, 4) NOT NULL DEFAULT 0.0000 COMMENT '申领数量（正数，退回量另记流水）',
    `remark`         varchar(500)   DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`      bigint         NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`        varchar(64)    DEFAULT '' COMMENT '创建者',
    `create_time`    datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)    DEFAULT '' COMMENT '更新者',
    `update_time`    datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        bit(1)         NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 明细按申领单查（详情页一次性取全量明细）
    KEY `idx_requisition_id` (`requisition_id`) USING BTREE,
    -- 按物料统计申领量（消耗汇总/对账）
    KEY `idx_material_id` (`material_id`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '材料申领明细表';

-- ------------------------------------------------------------------------------------
-- 7) bc_stock_log —— 库存流水（W6 分库分表主表）
--    spec §5.2 + §6.3：biz_type 入库/申领/退回/冲正、biz_no、数量±、变动后余额。
--
--    分片键 project_id（spec §6.3：按 project_id 哈希 → 逻辑 4 库 × 8 表 = 32 分片）：
--      - 业务查询天然带 project_id（数据权限 + 流水查询都按项目圈定），路由不跨片；
--      - 故本表在建表阶段就必须把 project_id 作为「每张分片表都有」的列并进主查询索引，
--        ShardingSphere 分片键缺失列会在 W6 接入时直接报错。
--    idx_project_create(project_id, create_time)：分片内查询主路径
--      ——「某项目某时间段的流水」= 定位分片 + 分片内按二级索引范围扫，是最热路径。
--
--    quantity 用带符号数量（入库/退回/冲正为正，申领扣减为负）：
--      对账一行聚合 `SUM(quantity)` 即得净变动，不需要 CASE WHEN biz_type IN (...)。
--    balance_after：变动后余额快照，用于逐笔核对与断点续算
--      （期初 + SUM(quantity) = 期末；与 stock.available_qty + locked_qty 对账，spec §6.1 第 ⑦ 步）。
--    biz_no：业务单号（入库单号/req_no 等），与 biz_type 一起构成幂等判别键；
--      不是唯一索引——一次申领可产生多条流水（扣减 + 后续冲正）。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_stock_log`
(
    `id`            bigint         NOT NULL AUTO_INCREMENT COMMENT '流水编号（主键；应用层用 MyBatis Plus ASSIGN_ID 雪花ID填充，不依赖自增，故 W6 分片可直接复用）',
    `project_id`    bigint         NOT NULL COMMENT '所属项目（bc_project.id，**分片键**：4 库 8 表按此哈希）',
    `material_id`   bigint         NOT NULL COMMENT '物料（bc_material.id）',
    `biz_type`      tinyint        NOT NULL COMMENT '业务类型：1-入库 2-申领扣减 3-退回 4-冲正',
    `biz_no`        varchar(64)    DEFAULT NULL COMMENT '业务单号（入库单号/申领单号 req_no，幂等与追溯用）',
    `quantity`      decimal(18, 4) NOT NULL COMMENT '变动数量（**正负号表示增减**：入库/退回/冲正为正，申领扣减为负）',
    `balance_after` decimal(18, 4) NOT NULL DEFAULT 0.0000 COMMENT '变动后余额（逐笔快照，用于对账与断点续算）',
    `remark`        varchar(500)   DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`     bigint         NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`       varchar(64)    DEFAULT '' COMMENT '创建者',
    `create_time`   datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（同时是流水时间序）',
    `updater`       varchar(64)    DEFAULT '' COMMENT '更新者',
    `update_time`   datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       bit(1)         NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 分片内查询主路径（**必须建**）：某项目某时间段的流水，定位分片后按此二级索引范围扫
    KEY `idx_project_create` (`project_id`, `create_time`) USING BTREE,
    -- 幂等/追溯：按业务单号反查本单产生的全部流水（含冲正），同样以分片键打头避免跨片
    KEY `idx_project_biz` (`project_id`, `biz_no`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '库存流水表（W6 分库分表主表，按 project_id 哈希）';
