-- =====================================================================================
-- 筑云 BuildCloud · 合同子域「进度款审批」关联列变更脚本
-- 表：bc_contract（新增 process_instance_id / approval_status / pending_payment_amount / pending_period 四列）
-- 依据：spec v1.2 §5.1（「进度款申请走审批，通过后生成 payment_record 并回写合同累计收付」）
--       + §5.3（审批域：业务表与流程实例双向关联）
--       + 读码笔记《reading-notes-bpm-integration.md》§2.3（业务表 process_instance_id ↔ 流程 businessKey）
--       + 经验 lessons-learned L2-12（BPM 跨服务回调必须为每个流程 key 补转发器）
--
-- 与 03-contract.sql 的关系：03 只增不改（sql/buildcloud/README.md「每个文件只增不改」硬约定），
-- 故进度款审批所需的列在这里以 ALTER 形式追加，而不是回到 03 里原地改。
-- 与 04-project-approval.sql 的关系：完全同构（同一套 INFORMATION_SCHEMA 幂等写法），
-- 只是目标表与列不同——两条流程（project-approve / payment-approve）在业务表上的
-- 关联字段形态保持一致，排障时两张表可以直接对照读。
--
-- 【为什么需要后两列（pending_payment_amount / pending_period）】
-- 基座的审批回调事件 BpmProcessInstanceStatusEvent 只携带 id / processDefinitionKey /
-- status / reason / businessKey **五个字段，不含流程变量**（见 bpm-api 的该类源码；
-- BpmHttpRequestUtils#executeBpmHttpRequest(event, url, restTemplate) 直接把 event 当 body）。
-- 而「通过后要按申请金额生成一条 payment_record」必须知道**提交时申请的金额与期次**，
-- 回调时无处可取。基座 bpm-api 也没有「按实例编号查流程变量」的 RPC（只有 create / cancel）。
-- 因此提交审批时把申请金额与期次落在合同表上（在途申请），回调成功或驳回后清空：
--   - 通过 → 用 pending_payment_amount 生成流水、累加 paid_amount，再清空；
--   - 驳回 → 直接清空（不生成流水）。
-- 备选方案（已评估放弃）：① 把金额编码进 businessKey —— 会破坏 businessKey = 业务主键的语义，
-- 且让回调侧的「串号校验」无法按主键比对；② 提交时先落一条「待生效」流水 —— 会污染
-- paid_amount 的累加口径与对账口径；③ 单独建一张在途申请表 —— 为 2 个字段建表，
-- 反而增加「一处状态两处存」的不一致风险。
--
-- 可重复执行（幂等）：
--   1) 用 INFORMATION_SCHEMA.COLUMNS 判断列是否已存在；
--   2) 存在则把语句改写成不改变任何数据的 `DO 0`，不存在才执行真正的 ALTER。
--   MySQL 8 不支持 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，因此用 PREPARE 动态拼语句，
--   这比「先跑一次失败再忽略 ERROR 1060」更安全：脚本在 sql_mode=STRICT 或
--   `mysql --force` 关闭时报错行为不一致，而条件判断的结果是确定的。
--
-- 执行：
--   docker cp sql/buildcloud/06-contract-approval.sql bc-mysql:/tmp/06.sql
--   docker exec bc-mysql sh -c 'mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 \
--       -D ruoyi-vue-pro < /tmp/06.sql'
--   ⚠️ PowerShell 不支持 `<` 重定向，重定向必须放进容器内 shell（lessons-learned L1-1b）；
--   ⚠️ 必须带 --default-character-set=utf8mb4（lessons-learned L2-10）。
-- =====================================================================================

-- ------------------------------------------------------------------------------------
-- 1) bc_contract.process_instance_id —— 关联的 Flowable 流程实例编号
--    对应 ACT_HI_PROCINST.PROC_INST_ID_；发起审批成功时回写，
--    驳回/取消后仍保留（审批历史可追溯），重新发起时覆盖为最新实例。
--    varchar(64)：Flowable 实例 id 形如 `<definitionKey>:<version>:<uuid>`，约 45 字符。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_contract'
            AND COLUMN_NAME = 'process_instance_id') > 0,
        'DO 0',
        'ALTER TABLE `bc_contract`
            ADD COLUMN `process_instance_id` varchar(64) DEFAULT NULL COMMENT ''进度款审批流程实例编号（ACT_HI_PROCINST.PROC_INST_ID_）'' AFTER `remark`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------------------------------
-- 2) bc_contract.approval_status —— 进度款审批状态
--    取值沿用基座 BpmProcessInstanceStatusEnum（bpm-api），业务表直接复用其值域，
--    避免再定义一套状态码造成前后端口径分裂（读码笔记 §2.2；与 bc_project 同口径）：
--      -1 未开始 / 1 审批中 / 2 通过 / 3 不通过 / 4 已取消
--    DEFAULT -1：新建合同尚未发起审批，即「未开始」。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_contract'
            AND COLUMN_NAME = 'approval_status') > 0,
        'DO 0',
        'ALTER TABLE `bc_contract`
            ADD COLUMN `approval_status` tinyint NOT NULL DEFAULT -1 COMMENT ''进度款审批状态：-1-未开始 1-审批中 2-通过 3-不通过 4-已取消（BpmProcessInstanceStatusEnum）'' AFTER `process_instance_id`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------------------------------
-- 3) bc_contract.pending_payment_amount —— 在途的进度款申请金额（元）
--    提交审批时写入，审批通过后用于生成 payment_record，随后与 pending_period 一并清空。
--    为 NULL 表示当前没有在途申请。理由见文件头。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_contract'
            AND COLUMN_NAME = 'pending_payment_amount') > 0,
        'DO 0',
        'ALTER TABLE `bc_contract`
            ADD COLUMN `pending_payment_amount` decimal(18, 2) DEFAULT NULL COMMENT ''在途进度款申请金额（元，提交审批时写入，出结果后清空）'' AFTER `approval_status`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------------------------------
-- 4) bc_contract.pending_period —— 在途的进度款期次（第几期）
--    与 bc_contract_payment_plan.period 同口径；用途同第 3 列（回调时写入流水备注与期次追溯）。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_contract'
            AND COLUMN_NAME = 'pending_period') > 0,
        'DO 0',
        'ALTER TABLE `bc_contract`
            ADD COLUMN `pending_period` int DEFAULT NULL COMMENT ''在途进度款申请期次（与 payment_plan.period 同口径，出结果后清空）'' AFTER `pending_payment_amount`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
