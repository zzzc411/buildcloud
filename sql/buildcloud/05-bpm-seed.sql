-- =====================================================================================
-- 筑云 BuildCloud · BPM 业务配置种子数据（流程分类 + 用户组）
-- 表：bpm_category（流程分类）、bpm_user_group（用户组）
-- 依据：spec v1.2 §5.3（审批域：立项 / 进度款 / 材料申领三条流程）
--       读码笔记《reading-notes-bpm-integration.md》§4.1 / §5.3
--
-- 为什么需要本脚本：
--   基座仓库的 bpm SQL（sql/mysql/bpm-2025-03-15.sql 等 4 个版本）只带
--   bpm_process_definition_info 的 250 条演示数据，bpm_category / bpm_user_group / bpm_form
--   三张表**没有任何种子数据**（已实测确认）。缺分类时管理端「流程模型-分类」下拉为空、
--   流程定义列表按分类筛选无结果；缺用户组时审批节点无法用「用户组」策略分配审批人。
--   本脚本按施工业务口径补齐这两张表。
--
-- 分类设计原则（与自研三个业务域一一对应，便于按域筛选流程定义）：
--   PROJECT  工程项目类 —— 立项审批、设计变更审批（project 域，bc_project）
--   FINANCE  财务资金类 —— 进度款审批（contract 域，bc_contract_payment_record）
--   MATERIAL 物资采购类 —— 材料申领 / 采购审批（material 域，bc_requisition）
--   ADMIN    综合行政类 —— 预留：请假、用章等行政类流程，非 spec 范围，仅占位保持分类体系完整
--   分类 code 用大写英文（与基座 ACT_RE_PROCDEF.CATEGORY_ 的取值形态一致），
--   流程定义的 category 字段引用本表的 code（不是 id），见 05-bpm-process-definition.sql。
--
-- 用户组设计：3 个组对应三条流程的审批人候选，成员先以 1 号 admin 占位
--   （本地环境 system_users 只有 admin 有完整权限，真实组织架构接入后改 user_ids 即可，
--     无需改流程定义——用户组是「审批人配置」与「人员名单」之间的解耦层）。
--
-- 可重复执行（幂等）：先按固定 id 段 DELETE，再 INSERT。
--   号段约定（本目录独占，不触碰基座演示数据）：
--     bpm_category    id 9101-9110
--     bpm_user_group  id 9201-9210
--   基座 bpm_category 现有 max(id)=117、bpm_user_group 现有 max(id)=113（空表但自增位已推进），
--   9100/9200 段与基座数据无交集；即使 id 被其它环境占用，按 id 段 DELETE 也只会清理本脚本自己的行。
--   禁止 REPLACE INTO（会重置 create_time 等审计字段），见 sql/buildcloud/README.md。
--
-- 执行：
--   docker cp sql/buildcloud/05-bpm-seed.sql bc-mysql:/tmp/05.sql
--   docker exec bc-mysql sh -c 'mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 \
--       -D ruoyi-vue-pro < /tmp/05.sql'
--   校验中文未乱码：SELECT HEX(name) FROM bpm_category WHERE id = 9101; 应为 E5B7A5E7A88BE9A1B9E79BAE（「工程项目」）
-- =====================================================================================

-- ------------------------------------------------------------------------------------
-- 1) bpm_category —— 流程分类
--    列：name / code / description / status(0正常 1停用) / sort / BaseDO 四字段 + deleted + tenant_id
--    sort 用 10/20/30/40 留出插队空间，避免以后新增分类要整体重排。
-- ------------------------------------------------------------------------------------
DELETE FROM `bpm_category` WHERE `id` BETWEEN 9101 AND 9110;

INSERT INTO `bpm_category` (`id`, `name`, `code`, `description`, `status`, `sort`,
                            `creator`, `create_time`, `updater`, `update_time`, `deleted`, `tenant_id`)
VALUES (9101, '工程项目类', 'PROJECT', '立项、设计变更、竣工验收等工程流程', 0, 10,
        '1', NOW(), '1', NOW(), b'0', 1),
       (9102, '财务资金类', 'FINANCE', '进度款、材料付款、结算款等资金审批流程', 0, 20,
        '1', NOW(), '1', NOW(), b'0', 1),
       (9103, '物资采购类', 'MATERIAL', '材料申领、采购、入库等物资流程', 0, 30,
        '1', NOW(), '1', NOW(), b'0', 1),
       (9104, '综合行政类', 'ADMIN', '请假、用章、用车等行政类流程（预留）', 0, 40,
        '1', NOW(), '1', NOW(), b'0', 1);

-- ------------------------------------------------------------------------------------
-- 2) bpm_user_group —— 用户组（审批节点「用户组」候选人策略的候选人来源）
--    列：name / description / user_ids（**JSON 数组**字符串）/ status / BaseDO 四字段 + deleted + tenant_id
--
--    ⚠️ user_ids 必须写成 JSON 数组形态 '["1"]' / '[1]'，**不能**写成逗号串 '1'。
--       基座 BpmUserGroupDO#userIds 是 `Set<Long>` + `@TableField(typeHandler = JacksonTypeHandler.class)`，
--       读库时走 Jackson 反序列化；写 '1' 会抛
--       `MismatchedInputException: Cannot deserialize value of type java.util.HashSet<java.lang.Long> from Integer value`，
--       而该异常在流程模型部署链路（validateBpmnConfig → USER_GROUP 策略 validateParam）
--       会被包成 `ResultMapException` 冒泡成 500「系统异常」，排查时看不出是用户组数据的问题
--       （W3-P4 实测踩坑：部署 project-approve 报 500，日志根因在 user_ids 列的取值形态）。
--       此处用 '["1"]' 保持与基座前端写入的形态一致（元素为字符串也由 Jackson 转 Long）。
-- ------------------------------------------------------------------------------------
DELETE FROM `bpm_user_group` WHERE `id` BETWEEN 9201 AND 9210;

INSERT INTO `bpm_user_group` (`id`, `name`, `description`, `user_ids`, `status`,
                              `creator`, `create_time`, `updater`, `update_time`, `deleted`, `tenant_id`)
VALUES (9201, '项目经理组', '立项审批的部门经理/项目经理审批人候选', '["1"]', 0,
        '1', NOW(), '1', NOW(), b'0', 1),
       (9202, '财务审批组', '进度款与付款类流程的财务审批人候选', '["1"]', 0,
        '1', NOW(), '1', NOW(), b'0', 1),
       (9203, '材料管理组', '材料申领与采购流程的审批人候选', '["1"]', 0,
        '1', NOW(), '1', NOW(), b'0', 1);
