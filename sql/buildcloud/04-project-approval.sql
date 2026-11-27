-- =====================================================================================
-- 筑云 BuildCloud · 项目子域「立项审批」关联列变更脚本
-- 表：bc_project（新增 process_instance_id / approval_status 两列）
-- 依据：spec v1.2 §5.3（审批域：业务表与流程实例双向关联）+ 读码笔记
--       《reading-notes-bpm-integration.md》§2.3（业务表 process_instance_id ↔ 流程 businessKey）
--
-- 与 01-project.sql 的关系：01 只增不改（README「每个文件只增不改」硬约定），
-- 故立项审批所需的列在这里以 ALTER 形式追加，而不是回到 01 里原地改。
--
-- 可重复执行（幂等）：
--   1) 用 INFORMATION_SCHEMA.COLUMNS 判断列是否已存在；
--   2) 存在则把语句改写成不改变任何数据的 `DO 0`，不存在才执行真正的 ALTER。
--   MySQL 8 不支持 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，因此用 PREPARE 动态拼语句，
--   这比「先跑一次失败再忽略 ERROR 1060」更安全：脚本在 sql_mode=STRICT 或
--   `mysql --force` 关闭时报错行为不一致，而条件判断的结果是确定的。
--
-- 执行：
--   docker cp sql/buildcloud/04-project-approval.sql bc-mysql:/tmp/04.sql
--   docker exec bc-mysql sh -c 'mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 \
--       -D ruoyi-vue-pro < /tmp/04.sql'
--   ⚠️ PowerShell 不支持 `<` 重定向，重定向必须放进容器内 shell（lessons-learned L1-1b）；
--   ⚠️ 必须带 --default-character-set=utf8mb4（lessons-learned L2-10）。
-- =====================================================================================

-- ------------------------------------------------------------------------------------
-- 1) bc_project.process_instance_id —— 关联的 Flowable 流程实例编号
--    对应 ACT_HI_PROCINST.PROC_INST_ID_（读码笔记 §2.3）；发起审批成功时回写，
--    驳回/取消后仍保留（审批历史可追溯），重新发起时覆盖为最新实例。
--    varchar(64)：Flowable 实例 id 形如 `<definitionKey>:<version>:<uuid>`，约 45 字符。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_project'
            AND COLUMN_NAME = 'process_instance_id') > 0,
        'DO 0',
        'ALTER TABLE `bc_project`
            ADD COLUMN `process_instance_id` varchar(64) DEFAULT NULL COMMENT ''立项审批流程实例编号（ACT_HI_PROCINST.PROC_INST_ID_）'' AFTER `remark`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------------------------------
-- 2) bc_project.approval_status —— 立项审批状态
--    取值沿用基座 BpmProcessInstanceStatusEnum（bpm-api），业务表直接复用其值域，
--    避免再定义一套状态码造成前后端口径分裂（读码笔记 §2.2）：
--      -1 未开始 / 1 审批中 / 2 通过 / 3 不通过 / 4 已取消
--    DEFAULT -1：新建项目尚未发起审批，即「未开始」。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_project'
            AND COLUMN_NAME = 'approval_status') > 0,
        'DO 0',
        'ALTER TABLE `bc_project`
            ADD COLUMN `approval_status` tinyint NOT NULL DEFAULT -1 COMMENT ''立项审批状态：-1-未开始 1-审批中 2-通过 3-不通过 4-已取消（BpmProcessInstanceStatusEnum）'' AFTER `process_instance_id`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
