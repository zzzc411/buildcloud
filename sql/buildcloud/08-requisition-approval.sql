-- =====================================================================================
-- 筑云 BuildCloud · 材料申领「审批 + 出库 + 退料」关联列变更脚本
-- 表：bc_requisition（新增 approvers / actual_out_time / actual_return_time 三列）
--     bc_requisition_item（新增 returned_qty 一列）
-- 依据：spec v1.2 §5.2（申领状态机：审批通过→已扣减；驳回→已取消+回补预扣+冲正流水；退料→部分回退）
--       + §5.3（requisition-approve 含会签）+ W4-P5 任务书
--       + 读码笔记《reading-notes-bpm-integration.md》§2.3（业务表 process_instance_id ↔ businessKey）
--       + 经验 lessons-learned L2-12（BPM 跨服务回调必须为每个流程 key 补转发器）
--
-- 与 07-material.sql 的关系：07 只增不改（sql/buildcloud/README.md「每个文件只增不改」硬约定），
-- 故 P5 需要的新列在这里以 ALTER 形式追加，而不是回到 07 里原地改
-- （与 04-project-approval.sql / 06-contract-approval.sql 的处理方式完全一致）。
--
-- 【07 里已有的、本 Phase 直接复用不改的列】
--   bc_requisition.process_instance_id  varchar(64)  —— 审批流程实例编号（P1 建表时已就位）
--   bc_requisition.approval_status      tinyint -1   —— -1未开始/1审批中/2通过/3不通过/4已取消
--   → 说明：P1 建表口径已够用，本 Phase **不需要**再补这两列；这里新增的是三样新东西。
--
-- 【为什么需要这三个新列（而不是"能跑就行"）】
--
-- 1) bc_requisition.approvers —— 在途会签审批人列表（逗号分隔的用户编号，如 "1,100"）
--    基座的审批结果回调事件 BpmProcessInstanceStatusEvent 只带
--    id / processDefinitionKey / status / reason / businessKey **五个字段，不含流程变量**
--    （见 bpm-api 该类源码；BpmHttpRequestUtils#executeBpmHttpRequest 直接把 event 当 body），
--    基座 bpm-api 也没有「按实例编号查流程变量」的 RPC（只有 create / cancel）。
--    而审批详情页要显示「这张单由谁在会签、还差谁」，驳回排障时也要能一眼看出审批人配置。
--    故提交审批时把审批人列表暂存在业务表（与 06-contract-approval.sql 的
--    pending_payment_amount「暂存在途数据」完全同构的手法），出结果后保留（审计用，不清空）。
--    备选方案（已评估放弃）：单独建在途表 —— 为 1 个字段建表，反而多一处状态两处存的不一致风险。
--
-- 2) bc_requisition.actual_out_time —— 实际出库时间
--    spec §5.2 要求「申领→出库」闭环；出库（2→3）不再动库存（V1 在提交审批前就已扣减），
--    但必须留下"什么时候真正把料发出去"的时间戳，否则：
--      - 报表域（W4 起）算「材料领用及时率/在途料时长」没有时间基准；
--      - 退料校验无从判断「已出库多久还能退」；
--      - 与原生的 create_time 混淆（建单时间 ≠ 出库时间）。
--    同一列也保证了「出库是一次性动作」可被审计（重复出库不会刷新成新时间，见 CAS 口径）。
--
-- 3) bc_requisition.actual_return_time —— 最近一次退料时间
--    退料可能多次发生（部分回退可以退好几次），单据上留最后一次的时间即可定位"最近一次动库存"。
--    每次退料的**逐笔明细**在 bc_stock_log（biz_type=3 退回）里，本列不做明细存储。
--
-- 4) bc_requisition_item.returned_qty —— 明细行累计已退料量（decimal(18,4)，与 quantity 同精度）
--    退料校验的唯一依据：可退量 = quantity - returned_qty，且退回量必须 ≤ 已出库量。
--    为什么不靠「按 biz_no 聚合 bc_stock_log.quantity」算可退量：
--      - 流水表是 W6 的分库分表主表（按 project_id 哈希），跨片聚合会随分片上线变成跨片查询；
--      - 明细行的可退量是**单据内的一致性约束**，放在单据明细上才能用「WHERE returned_qty + ? <= quantity」
--        做原子条件更新（并发重复退料时第二个请求影响行数 0 → 报错，而不是把库存退两次）。
--    精度必须与 quantity / bc_material_stock.available_qty 一致（都是 decimal(18,4)），
--    否则会出现「退完还有 0.0001 没退、单据永远停在部分回退」这类幽灵差异。
--
-- 可重复执行（幂等）：
--   1) 用 INFORMATION_SCHEMA.COLUMNS 判断列是否已存在；
--   2) 存在则把语句改写成不改变任何数据的 `DO 0`，不存在才执行真正的 ALTER。
--   MySQL 8 不支持 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，因此用 PREPARE 动态拼语句，
--   这比「先跑一次失败再忽略 ERROR 1060」更安全：脚本在 sql_mode=STRICT 或
--   `mysql --force` 关闭时报错行为不一致，而条件判断的结果是确定的。
--
-- 执行：
--   docker cp sql/buildcloud/08-requisition-approval.sql bc-mysql:/tmp/08.sql
--   docker exec bc-mysql sh -c 'mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 \
--       -D ruoyi-vue-pro < /tmp/08.sql'
--   ⚠️ PowerShell 不支持 `<` 重定向，重定向必须放进容器内 shell（lessons-learned L1-1b）；
--   ⚠️ 必须带 --default-character-set=utf8mb4（lessons-learned L2-10）。
-- =====================================================================================

-- ------------------------------------------------------------------------------------
-- 1) bc_requisition.approvers —— 在途会签审批人列表
--    格式：逗号分隔的用户编号，如 "1,100"。存字符串而不是 JSON 的理由：
--    只用于展示与排障，不参与任何 SQL 过滤；varchar 在 MySQL/HS 两侧行为一致，
--    JSON 列在 H2 单测（MODE=MYSQL）下没有对应类型，会让「单测绿、真库红」口径分裂。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_requisition'
            AND COLUMN_NAME = 'approvers') > 0,
        'DO 0',
        'ALTER TABLE `bc_requisition`
            ADD COLUMN `approvers` varchar(255) DEFAULT NULL COMMENT ''会签审批人用户编号列表（逗号分隔，如 1,100；提交审批时暂存）'' AFTER `process_instance_id`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------------------------------
-- 2) bc_requisition.actual_out_time —— 实际出库时间
--    出库动作（已扣减2 → 已出库3）写入；重复出库不会刷新（状态 CAS 拦在 2→3 这条边上）。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_requisition'
            AND COLUMN_NAME = 'actual_out_time') > 0,
        'DO 0',
        'ALTER TABLE `bc_requisition`
            ADD COLUMN `actual_out_time` datetime DEFAULT NULL COMMENT ''实际出库时间（已扣减2→已出库3 时写入）'' AFTER `timeout_time`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------------------------------
-- 3) bc_requisition.actual_return_time —— 最近一次退料时间
--    退料可多次发生（部分回退），此处只留最后一次；逐笔明细在 bc_stock_log（biz_type=3）。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_requisition'
            AND COLUMN_NAME = 'actual_return_time') > 0,
        'DO 0',
        'ALTER TABLE `bc_requisition`
            ADD COLUMN `actual_return_time` datetime DEFAULT NULL COMMENT ''最近一次退料时间（退料可多次，逐笔明细见 bc_stock_log.biz_type=3）'' AFTER `actual_out_time`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------------------------------
-- 4) bc_requisition_item.returned_qty —— 明细行累计已退料量
--    可退量 = quantity - returned_qty（必须 ≥ 0 且 ≤ 已出库量）。
--    原子条件更新放在 RequisitionItemMapper#increaseReturnedQtyReturnCheck 里，
--    并发重复退料时第二个请求影响行数 0 → 抛业务错误码，不会把库存退两次。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_requisition_item'
            AND COLUMN_NAME = 'returned_qty') > 0,
        'DO 0',
        'ALTER TABLE `bc_requisition_item`
            ADD COLUMN `returned_qty` decimal(18, 4) NOT NULL DEFAULT 0.0000 COMMENT ''累计已退料量（可退量 = quantity - returned_qty，精度与 quantity 一致）'' AFTER `quantity`');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ------------------------------------------------------------------------------------
-- 5) 索引：审批中心的「按流程实例反查业务单」入口（运维排障/回调补投时按实例号定位单据）
--    07 里没有 process_instance_id 的索引（P1 只要求字段就位），P5 起回调侧会真正用到。
--    MySQL 8 不支持 CREATE INDEX IF NOT EXISTS，同样用 INFORMATION_SCHEMA.STATISTICS 判断。
-- ------------------------------------------------------------------------------------
SET @sql := IF(
        (SELECT COUNT(*)
           FROM INFORMATION_SCHEMA.STATISTICS
          WHERE TABLE_SCHEMA = DATABASE()
            AND TABLE_NAME = 'bc_requisition'
            AND INDEX_NAME = 'idx_process_instance_id') > 0,
        'DO 0',
        'ALTER TABLE `bc_requisition`
            ADD INDEX `idx_process_instance_id` (`process_instance_id`) USING BTREE');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
