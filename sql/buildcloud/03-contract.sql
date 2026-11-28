-- =====================================================================================
-- 筑云 BuildCloud · 合同资金子域（buildcloud-module-project 下的 com.buildcloud.contract）建表脚本
-- 表：bc_contract（合同）、bc_contract_payment_plan（收付款计划）、
--     bc_contract_payment_record（实际收付款）、bc_contract_change（变更签证）
-- 依据：spec v1.2 §5.1（合同子域实体与状态机）+ §7.1（核心表清单：contract 域 4 张）
--       + AGENTS.md §4（BaseDO 六字段、金额精度）
-- 可重复执行：全部 CREATE TABLE IF NOT EXISTS，不含 DROP；索引内联在 CREATE 内，表已存在则整句跳过。
-- 执行：mysql -u<user> -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro < 03-contract.sql
--       ⚠️ 必须带 --default-character-set=utf8mb4（lessons-learned L2-10：中文双重编码不可逆）
-- 约定：金额 decimal(18,2)（元，含 2 位小数，满足工程结算精度）；日期用 date（不需要时分秒语义）。
--
-- 【唯一索引口径 —— ADR-004】本文件保留 `CREATE TABLE` 内的唯一索引作为新建库的初始防线；
--   生产口径由 `09-unique-index-realign.sql` 归一。本域涉及：
--     A 类 → bc_contract.uk_contract_no（逻辑删除后允许同编号重签）
--     B 类 → bc_contract_payment_plan.uk_contract_period（含 period，同合同期次重复是真错误，保留）
-- =====================================================================================

-- ------------------------------------------------------------------------------------
-- 1) bc_contract —— 合同主表
--    spec §5.1：编号/名称/关联项目/收入或支出/金额/签订日期/状态（+ 甲乙方、累计收付、备注）
--    状态机：草稿0 → 审批中1 → 履行中2 → 已结算3，旁路 已作废9
--    direction：1-收入（我方收款，如业主合同）2-支出（我方付款，如分包/采购合同）；
--               台账对账与报表汇总都必须按 direction 分口径，故建索引。
--    paid_amount：累计已收/已付金额，由进度款审批通过后回写；与 payment_record 之和
--               在正常流程下应一致，对账 Job 以此校验（spec §5.1「台账对账」）。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_contract`
(
    `id`             bigint         NOT NULL AUTO_INCREMENT COMMENT '合同编号（主键）',
    `contract_no`    varchar(64)    NOT NULL COMMENT '合同编号（业务编码，租户内唯一）',
    `contract_name`  varchar(200)   NOT NULL COMMENT '合同名称',
    `project_id`     bigint         DEFAULT NULL COMMENT '关联项目（bc_project.id；补充协议等可能暂不挂项目，故可空）',
    `direction`      tinyint        NOT NULL DEFAULT 1 COMMENT '合同方向：1-收入（我方收款） 2-支出（我方付款）',
    `amount`         decimal(18, 2) NOT NULL DEFAULT 0.00 COMMENT '合同金额（元）',
    `sign_date`      date           DEFAULT NULL COMMENT '签订日期',
    `status`         tinyint        NOT NULL DEFAULT 0 COMMENT '合同状态：0-草稿 1-审批中 2-履行中 3-已结算 9-已作废',
    `party_a`        varchar(200)   DEFAULT NULL COMMENT '甲方（发包方/付款方，按方向语义不同）',
    `party_b`        varchar(200)   DEFAULT NULL COMMENT '乙方（承包方/收款方，按方向语义不同）',
    `paid_amount`    decimal(18, 2) NOT NULL DEFAULT 0.00 COMMENT '累计已收/已付金额（元，进度款审批通过后回写）',
    `remark`         varchar(500)   DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`      bigint         NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`        varchar(64)    DEFAULT '' COMMENT '创建者',
    `create_time`    datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)    DEFAULT '' COMMENT '更新者',
    `update_time`    datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        bit(1)         NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 合同编号租户内唯一（逻辑删除记录不参与唯一性判断由业务层保证，与 bc_project 口径一致）
    -- **（A 类：逻辑删除后允许同编码重建，见 ADR-004；生产口径由 09-unique-index-realign.sql 归一）**
    -- 归一后本键变为普通索引（同名 uk_contract_no），唯一性由 Service 层「查全量含已删行」承担。
    UNIQUE KEY `uk_contract_no` (`tenant_id`, `contract_no`) USING BTREE,
    -- 台账主路径：按项目查本合同清单（项目详情页/成本归集）
    KEY `idx_project_id` (`project_id`) USING BTREE,
    -- 状态筛选（待办：审批中/履行中）
    KEY `idx_status` (`status`) USING BTREE,
    -- 收付款台账按方向 + 签订日期汇总/排序
    KEY `idx_direction_sign_date` (`direction`, `sign_date`) USING BTREE,
    KEY `idx_create_time` (`create_time`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '合同表';

-- ------------------------------------------------------------------------------------
-- 2) bc_contract_payment_plan —— 收付款计划（合同期次）
--    spec §5.1：期次/计划金额/计划日期/状态。
--    uk(tenant_id, contract_id, period)：同一合同内期次唯一，避免重复编制同一期计划。
--    对账口径：plan.plan_amount 汇总 vs record.amount 汇总 → 差异列（spec §5.1「台账对账」）。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_contract_payment_plan`
(
    `id`            bigint         NOT NULL AUTO_INCREMENT COMMENT '计划编号（主键）',
    `contract_id`   bigint         NOT NULL COMMENT '所属合同（bc_contract.id）',
    `period`        int            NOT NULL COMMENT '期次（第几期，从 1 起；同合同内唯一）',
    `plan_amount`   decimal(18, 2) NOT NULL DEFAULT 0.00 COMMENT '计划收付款金额（元）',
    `plan_date`     date           DEFAULT NULL COMMENT '计划收付款日期',
    `status`        tinyint        NOT NULL DEFAULT 0 COMMENT '计划状态：0-未到期 1-待到账 2-已收付 3-已逾期',
    `remark`        varchar(500)   DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`     bigint         NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`       varchar(64)    DEFAULT '' COMMENT '创建者',
    `create_time`   datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`       varchar(64)    DEFAULT '' COMMENT '更新者',
    `update_time`   datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       bit(1)         NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 同一合同内期次唯一
    -- **（B 类：结构唯一 = 同合同重复编制同一期次是数据错误，按 ADR-004 保留；09 脚本不动它）**
    UNIQUE KEY `uk_contract_period` (`tenant_id`, `contract_id`, `period`) USING BTREE,
    -- 计划列表按合同查、按计划日期排序；逾期扫描 Job 走 (status, plan_date)
    KEY `idx_contract_plan_date` (`contract_id`, `plan_date`) USING BTREE,
    KEY `idx_status_plan_date` (`status`, `plan_date`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '合同收付款计划表';

-- ------------------------------------------------------------------------------------
-- 3) bc_contract_payment_record —— 实际收付款流水
--    spec §5.1：金额/日期/关联审批单。
--    approval_no 存 BPM 流程的业务单号字符串而非流程实例外键（设计取舍见 ER 图文档）：
--    ① payment_record 可能来自手工补录（历史数据无审批流），强制外键会造空值占位；
--    ② BPM 实例 id 随流程定义升级/迁移会变，业务单号才是稳定幂等键；
--    ③ 回调链路（spec §5.3）以业务单号反查，不依赖 bpm 库内部主键。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_contract_payment_record`
(
    `id`          bigint         NOT NULL AUTO_INCREMENT COMMENT '收付款流水编号（主键）',
    `contract_id` bigint         NOT NULL COMMENT '所属合同（bc_contract.id）',
    `approval_no` varchar(64)    DEFAULT NULL COMMENT '关联审批单号（BPM 流程业务单号，手工补录可为空）',
    `amount`      decimal(18, 2) NOT NULL DEFAULT 0.00 COMMENT '实际收付款金额（元）',
    `pay_date`    date           DEFAULT NULL COMMENT '实际收付款日期',
    `type`        tinyint        NOT NULL DEFAULT 1 COMMENT '类型：1-收款 2-付款',
    `remark`      varchar(500)   DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`   bigint         NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`     varchar(64)    DEFAULT '' COMMENT '创建者',
    `create_time` datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)    DEFAULT '' COMMENT '更新者',
    `update_time` datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     bit(1)         NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 合同详情页/对账主路径：按合同查全部流水
    KEY `idx_contract_id` (`contract_id`) USING BTREE,
    -- 流水按日期汇总
    KEY `idx_contract_pay_date` (`contract_id`, `pay_date`) USING BTREE,
    -- 审批回调幂等：同一审批单只应产生一条流水（唯一性由业务层 + 该索引兜底排查重复）
    KEY `idx_approval_no` (`approval_no`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '合同实际收付款流水表';

-- ------------------------------------------------------------------------------------
-- 4) bc_contract_change —— 合同变更签证
--    spec §5.1：类型/增减金额/原因/关联审批。
--    change_amount 允许负数：正数=增项（追加金额），负数=减项（核减金额），
--    这样「合同变更后金额 = amount + SUM(change_amount)」一处口径，无需看 type 再决定加减符号。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_contract_change`
(
    `id`            bigint         NOT NULL AUTO_INCREMENT COMMENT '变更编号（主键）',
    `contract_id`   bigint         NOT NULL COMMENT '所属合同（bc_contract.id）',
    `type`          tinyint        NOT NULL DEFAULT 1 COMMENT '变更类型：1-设计变更 2-现场签证 3-索赔 9-其他',
    `change_amount` decimal(18, 2) NOT NULL DEFAULT 0.00 COMMENT '增减金额（元）：正数=增项，负数=减项',
    `change_reason` varchar(500)   DEFAULT NULL COMMENT '变更原因',
    `approval_no`   varchar(64)    DEFAULT NULL COMMENT '关联审批单号（BPM 流程业务单号）',
    `remark`        varchar(500)   DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`     bigint         NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`       varchar(64)    DEFAULT '' COMMENT '创建者',
    `create_time`   datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`       varchar(64)    DEFAULT '' COMMENT '更新者',
    `update_time`   datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       bit(1)         NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 合同详情页变更列表主路径
    KEY `idx_contract_id` (`contract_id`) USING BTREE,
    -- 变更类型筛选
    KEY `idx_type` (`type`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '合同变更签证表';
