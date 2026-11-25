-- =============================================================================
-- 筑云 BuildCloud — BPM 流程定义信息表增量变更（7 列，2026-11-25）
-- 来源：芋道星球附件 bpm 增量脚本（成员授权使用，勿对外分发）
-- 作用：把 bpm-2025-03-15.sql 的表结构补齐到 yudao-cloud master-jdk17 版本，
--       与 bpm_process_definition_info 的 7 个新配置字段对齐：
--       可发起部门 / 审批人撤回 / 流程前·后置触发 / 任务前·后置触发 / 打印模板。
-- 执行顺序：先执行 bpm-2025-03-15.sql，再执行本脚本（一次性变更，重复执行会报列已存在）。
-- =============================================================================

ALTER TABLE `bpm_process_definition_info`
  ADD COLUMN `start_dept_ids` varchar(500) NULL DEFAULT NULL COMMENT '可发起部门编号数组' AFTER `start_user_ids`,
  ADD COLUMN `allow_withdraw_task` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否允许审批人撤回任务' AFTER `allow_cancel_running_process`,
  ADD COLUMN `process_before_trigger_setting` text NULL DEFAULT NULL COMMENT '流程前置触发配置' AFTER `summary_setting`,
  ADD COLUMN `process_after_trigger_setting` text NULL DEFAULT NULL COMMENT '流程后置触发配置' AFTER `process_before_trigger_setting`,
  ADD COLUMN `task_before_trigger_setting` text NULL DEFAULT NULL COMMENT '任务前置触发配置' AFTER `process_after_trigger_setting`,
  ADD COLUMN `task_after_trigger_setting` text NULL DEFAULT NULL COMMENT '任务后置触发配置' AFTER `task_before_trigger_setting`,
  ADD COLUMN `print_template_setting` text NULL DEFAULT NULL COMMENT '打印模板配置' AFTER `task_after_trigger_setting`;
