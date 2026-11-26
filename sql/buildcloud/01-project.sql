-- =====================================================================================
-- 筑云 BuildCloud · 项目子域（buildcloud-module-project）建表脚本
-- 表：bc_project（项目主表）、bc_project_milestone（里程碑）、bc_project_member（项目成员）
-- 依据：spec v1.2 §5.1（项目域字段）+ §7.1（核心表清单：project 域 3 张）+ AGENTS.md §4（BaseDO 六字段）
-- 可重复执行：全部语句使用 CREATE TABLE IF NOT EXISTS，不含 DROP；
--             索引随建表语句内联声明（建表时若表已存在则整句跳过，无需单独判断索引）。
-- 执行：mysql -u<user> -p"<PWD>" --default-character-set=utf8mb4 ruoyi-vue-pro < 01-project.sql
--       ⚠️ 必须带 --default-character-set=utf8mb4，否则中文注释与数据会双重编码（lessons-learned L2-10）
-- =====================================================================================

-- ------------------------------------------------------------------------------------
-- 1) bc_project —— 项目主表
--    spec §5.1 项目实体逐项映射：编号(project_no)/名称(project_name)/类型(project_type)/
--    状态(status)/省市(province_*/city_*)/建设单位(build_unit)/合同额(contract_amount)/
--    计划与实际开竣工(plan_*_date/actual_*_date)/项目经理(manager_user_id)/所属部门(dept_id)
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_project`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '项目编号（主键）',
    `project_no`       varchar(64)  NOT NULL COMMENT '项目编号（业务编码，租户内唯一）',
    `project_name`     varchar(200) NOT NULL COMMENT '项目名称',
    `project_type`     tinyint      NOT NULL COMMENT '项目类型：参见 ProjectTypeEnum（房建/市政/路桥/装饰/其他）',
    `status`           tinyint      NOT NULL DEFAULT 0 COMMENT '项目状态：0-筹备 1-在建 2-停工 3-竣工（见 ProjectStatusEnum）',
    -- 行政区域：省 + 市，用基座 infra 的「地区」字典（infra 无地区表，故存编码 + 名称快照）
    `province_code`    varchar(32)  DEFAULT NULL COMMENT '省份编码',
    `province_name`    varchar(64)  DEFAULT NULL COMMENT '省份名称',
    `city_code`        varchar(32)  DEFAULT NULL COMMENT '城市编码',
    `city_name`        varchar(64)  DEFAULT NULL COMMENT '城市名称',
    `address`          varchar(500) DEFAULT NULL COMMENT '详细地址',
    `build_unit`       varchar(200) DEFAULT NULL COMMENT '建设单位（业主）',
    `contract_amount`  decimal(18, 2) DEFAULT 0.00 COMMENT '合同额（元）',
    `plan_start_date`  date         DEFAULT NULL COMMENT '计划开工日期',
    `plan_end_date`    date         DEFAULT NULL COMMENT '计划竣工日期',
    `actual_start_date` date        DEFAULT NULL COMMENT '实际开工日期',
    `actual_end_date`  date         DEFAULT NULL COMMENT '实际竣工日期',
    `manager_user_id`  bigint       DEFAULT NULL COMMENT '项目经理（system_users.id）',
    `dept_id`          bigint       DEFAULT NULL COMMENT '所属部门（system_dept.id，数据权限按部门过滤）',
    `remark`           varchar(500) DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段（基座 MyBatis Plus + 多租户 + 逻辑删除强制约定）----
    `tenant_id`        bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`          varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`          varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 业务唯一键：项目编号在租户内唯一（逻辑删除的记录不参与唯一性判断由业务层保证）
    UNIQUE KEY `uk_project_no` (`tenant_id`, `project_no`) USING BTREE,
    -- 台账查询主路径：按部门（数据权限）+ 按状态筛选
    KEY `idx_dept_id` (`dept_id`) USING BTREE,
    KEY `idx_status` (`status`) USING BTREE,
    KEY `idx_manager_user_id` (`manager_user_id`) USING BTREE,
    KEY `idx_create_time` (`create_time`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '项目主表';

-- ------------------------------------------------------------------------------------
-- 2) bc_project_milestone —— 项目里程碑
--    spec §5.1：名称/计划与实际日期/权重/状态。
--    weight 用 decimal(5,2) 存百分比（0.00~100.00），同一项目内建议合计 100；
--    进度百分比 = SUM(已完成里程碑 weight) / SUM(全部 weight)，故用小数而非整数。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_project_milestone`
(
    `id`             bigint         NOT NULL AUTO_INCREMENT COMMENT '里程碑编号（主键）',
    `project_id`     bigint         NOT NULL COMMENT '所属项目（bc_project.id）',
    `milestone_name` varchar(200)   NOT NULL COMMENT '里程碑名称',
    `plan_date`      date           DEFAULT NULL COMMENT '计划完成日期',
    `actual_date`    date           DEFAULT NULL COMMENT '实际完成日期',
    `weight`         decimal(5, 2)  NOT NULL DEFAULT 0.00 COMMENT '权重（百分比 0.00~100.00，同项目内建议合计 100）',
    `status`         tinyint        NOT NULL DEFAULT 0 COMMENT '里程碑状态：0-未开始 1-进行中 2-已完成 3-已延期',
    `sort`           int            NOT NULL DEFAULT 0 COMMENT '排序号（同项目内升序展示，工程补充字段）',
    `remark`         varchar(500)   DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`      bigint         NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`        varchar(64)    DEFAULT '' COMMENT '创建者',
    `create_time`    datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`        varchar(64)    DEFAULT '' COMMENT '更新者',
    `update_time`    datetime       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        bit(1)         NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 里程碑列表按项目查、按计划日期排序（甘特/进度看板主路径）
    KEY `idx_project_plan_date` (`project_id`, `plan_date`) USING BTREE,
    KEY `idx_project_status` (`project_id`, `status`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '项目里程碑表';

-- ------------------------------------------------------------------------------------
-- 3) bc_project_member —— 项目成员（数据权限第二维度：成员白名单）
--    spec §5.1：项目/用户/项目内角色；uk(tenant_id, project_id, user_id) 保证同一用户
--    在同一项目内只有一条记录（角色变更走 UPDATE，不新增行），这是成员白名单判定的前提。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_project_member`
(
    `id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '成员编号（主键）',
    `project_id`   bigint       NOT NULL COMMENT '所属项目（bc_project.id）',
    `user_id`      bigint       NOT NULL COMMENT '用户编号（system_users.id）',
    `project_role` tinyint      NOT NULL DEFAULT 9 COMMENT '项目内角色：1-项目经理 2-技术负责人 3-施工员 4-安全员 5-材料员 9-其他',
    `remark`       varchar(500) DEFAULT NULL COMMENT '备注',
    -- ---- BaseDO 六字段 ----
    `tenant_id`    bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`      varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`      varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time`  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`      bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 一人在一个项目内只有一个成员身份（角色变更走 UPDATE）
    UNIQUE KEY `uk_project_user` (`tenant_id`, `project_id`, `user_id`) USING BTREE,
    -- 反向查询：某用户参与哪些项目（成员白名单数据权限用）
    KEY `idx_user_id` (`user_id`) USING BTREE,
    KEY `idx_project_role` (`project_id`, `project_role`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '项目成员表';
