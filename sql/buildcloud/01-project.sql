-- =====================================================================================
-- 筑云 BuildCloud · 项目子域（buildcloud-module-project）建表脚本
-- 表：bc_project（项目主表）
-- 依据：spec v1.2 §5.1（项目域字段）+ §7.1（核心表清单）+ AGENTS.md §4（BaseDO 六字段）
-- 可重复执行：全部语句使用 CREATE TABLE IF NOT EXISTS，不含 DROP；
--             索引随建表语句内联声明（建表时若表已存在则整句跳过，无需单独判断索引）。
-- 执行：mysql -u<user> -p"<PWD>" ruoyi-vue-pro < 01-project.sql
-- =====================================================================================

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
