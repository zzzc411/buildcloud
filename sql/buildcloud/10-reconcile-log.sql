-- =====================================================================================
-- 筑云 BuildCloud · 库存对账结果快照表建表脚本（plan-w5 Phase 4）
-- 表：bc_reconcile_log（每轮对账 × 每种差异类型 一行）
-- 依据：spec v1.2 §6.1 步骤⑦「对账兜底：XXL-Job 定时比对 Redis 预扣 vs DB 流水 →
--       差异告警（站内信）+ 修复」
--       + AGENTS §4（BaseDO 六字段）+ R-8（DO extends TenantBaseDO）
-- 可重复执行：CREATE TABLE IF NOT EXISTS，不含 DROP；
--             索引内联在建表语句里（表已存在则整句跳过，不会重复建索引）。
-- 执行：
--   docker cp sql/buildcloud/10-reconcile-log.sql bc-mysql:/tmp/10-reconcile-log.sql
--   docker exec bc-mysql sh -c 'mysql -uroot -p"<PWD>" --default-character-set=utf8mb4 \
--       -D ruoyi-vue-pro < /tmp/10-reconcile-log.sql'
--   ⚠️ PowerShell 不支持 `<` 重定向，重定向必须放进容器内 shell（lessons-learned L1-1b）；
--   ⚠️ 必须带 --default-character-set=utf8mb4（L2-10）。
--
-- ========== 这张表为什么必须有（而不是"只打日志"） ==========
--   对账的价值不在"这一轮发现了什么"，而在"差异是否在收敛"：
--     - 同一个 (project_id, material_id, diff_type) 连续多轮都有差异 → 修复逻辑失效；
--     - 差异数从 N 降到 0 的那一轮 → 可以确认收敛。
--   只打日志时，这两件事只能靠人翻文件，且日志有轮转（默认保留 30 天），
--   事故复盘时"当时到底有没有告警"无从证明。落表让每轮对账成为**可查询的事实**。
--
-- ========== 一个设计点：check_type 与 batch_no 的关系 ==========
--   同一轮对账（一个 batch_no）会写**多行**（每种差异类型一行，最多 3 行），而不是一行汇总。
--   理由：三种差异的 diff_count / fixed_count / status 各自独立（D1 已修完而 D3 仍失败是常态），
--   合成一行就必须在 detail_json 里嵌套两个维度，查询与告警阈值判断都要解析 JSON——
--   而阈值告警正是最常见的查询（"今天有没有哪一轮差异超阈值"）。
--   `10-reconcile-log.sql` 因此把 check_type 也放进索引，支持"按类型看趋势"。
-- =====================================================================================

-- ------------------------------------------------------------------------------------
-- bc_reconcile_log —— 库存对账结果快照（每轮 × 每类差异 一行）
--
-- check_type：1-D1（Redis 有预扣 / DB 无扣减流水）2-D2（DB 有扣减 / Redis 无预扣）3-D3（可用量不一致）
-- status    ：0-无差异 1-有差异未修复 2-已全部修复   （ReconcileStatusEnum）
-- fixed_count 是"本轮实际修复成功的条数"；diff_count - fixed_count = 仍未修复的条数。
--   两者分开存（而不是只存 diff_count）的理由：修复失败（DB 漂移拒绝扣减、Redis 写失败）
--   是最需要告警的状态——只看 diff_count 无法区分"发现了但没修好"与"发现了正在修"。
-- detail_json：本轮差异明细 + 修复前后值（审计要求"每条修复记日志，含修复前后值"）。
--   存 JSON 而不是另开明细表：明细只被人工排查读，不参与任何业务查询；
--   且明细形状随差异类型变化（D1/D2 是 reqNo 级、D3 是两个数值），拆表会变成稀疏宽表。
-- ------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `bc_reconcile_log`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '对账快照编号（主键）',
    `batch_no`    varchar(64)  NOT NULL COMMENT '对账批次号（一轮对账一个批次；格式 RCL-yyyyMMddHHmmss-SSS-分片号）',
    `check_time`  datetime     NOT NULL COMMENT '对账执行时间（批次开始时刻，本轮所有行相同）',
    `check_type`  tinyint      NOT NULL COMMENT '差异类型：1-D1（Redis 有预扣/DB 无流水） 2-D2（DB 有扣减/Redis 无预扣） 3-D3（可用量不一致）',
    `diff_count`  int          NOT NULL DEFAULT 0 COMMENT '本轮该类型检出的差异条数',
    `fixed_count` int          NOT NULL DEFAULT 0 COMMENT '本轮该类型修复成功的条数（diff_count - fixed_count = 未修复）',
    `status`      tinyint      NOT NULL DEFAULT 0 COMMENT '对账结论：0-无差异 1-有差异未修复 2-已全部修复',
    `detail_json` varchar(4000) DEFAULT NULL COMMENT '差异明细 JSON（含修复前后值；超长时截断并在末尾标注）',
    -- ---- BaseDO 六字段（DO extends TenantBaseDO，AGENTS R-8）----
    `tenant_id`   bigint       NOT NULL DEFAULT 0 COMMENT '租户编号',
    `creator`     varchar(64)  DEFAULT '' COMMENT '创建者',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updater`     varchar(64)  DEFAULT '' COMMENT '更新者',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     bit(1)       NOT NULL DEFAULT b'0' COMMENT '是否删除',
    PRIMARY KEY (`id`) USING BTREE,
    -- 按批次查看一轮对账的全部结论（运维排查入口：拿到 batch_no 一次取全）
    KEY `idx_batch_no` (`batch_no`) USING BTREE,
    -- 按类型看差异趋势 + 阈值告警扫描（"某类型最近 N 轮是否一直有差异"）
    KEY `idx_type_time` (`check_type`, `check_time`) USING BTREE,
    -- 按时间倒序的告警/看板列表（控制台最近对账记录）
    KEY `idx_check_time` (`check_time`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT = '库存对账结果快照表（每轮对账 × 每类差异 一行）';
