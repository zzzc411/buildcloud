-- =====================================================================================
-- 筑云 BuildCloud · 菜单树骨架初始化脚本（W2-P4）
-- 表：system_menu（基座菜单表，结构见 sql/mysql/ruoyi-vue-pro.sql）
-- 内容：五大菜单组的目录节点（type=1，parent_id=0），作为后续各业务菜单的挂载点：
--       9000 项目管理 / 9010 合同资金 / 9020 材料管理 / 9030 报表中心 / 9040 AI 助手
-- 依据：spec v1.2 §5（五个业务域）+ AGENTS.md §4（菜单/字典初始化 SQL 同步维护）
--
-- 【id 段位】基座 system_menu 现有 id 最大 25380，且基座用了 1~500+ 与后端生成的小号段；
--          本脚本固定占用 9000~9049 段（留 1000+ 号给子菜单使用），避免与基座冲突。
--
-- 【可重复执行】先 DELETE 9000~9049 段再 INSERT，重复执行结果一致（幂等）。
--             仅删本脚本自己占用的号段，不触碰基座任何菜单，可安全重跑。
--
-- 执行：mysql -u<user> -p"<PWD>" ruoyi-vue-pro < 02-menu.sql
-- =====================================================================================

-- ------------------------------------------------------------------ 清理本脚本号段
DELETE
FROM `system_menu`
WHERE `id` BETWEEN 9000 AND 9049;

-- ------------------------------------------------------------------ 五大菜单组（目录，type=1）
INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`,
                           `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`,
                           `creator`, `create_time`, `updater`, `update_time`, `deleted`)
VALUES
-- 1) 项目管理（项目台账、里程碑、成员，见 spec §5.1 项目子域）
(9000, '项目管理', '', 1, 10, 0, '/buildcloud-project', 'ep:office-building', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- 2) 合同资金（合同登记、收付款计划、变更签证，见 spec §5.1 合同子域）
(9010, '合同资金', '', 1, 20, 0, '/buildcloud-contract', 'ep:money', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- 3) 材料管理（物料/供应商/库存/申领，高并发主战场，见 spec §5.2）
(9020, '材料管理', '', 1, 30, 0, '/buildcloud-material', 'ep:box', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- 4) 报表中心（异步导出/看板，见 spec §5.4）
(9030, '报表中心', '', 1, 40, 0, '/buildcloud-report', 'ep:pie-chart', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- 5) AI 助手（RAG 知识库/问答，见 spec §5.5）
(9040, 'AI 助手', '', 1, 50, 0, '/buildcloud-ai', 'ep:magic-stick', NULL, NULL, 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');

-- ------------------------------------------------------------------ 项目菜单下的页面与按钮（代码生成器产物）
-- 来源：基座 infra 代码生成器对 bc_project 生成（上级菜单已指定 9000），
--       此处把生成器输出的 INSERT 改为固定 id（幂等），并放到 9000 段的 9001+ 号。
DELETE
FROM `system_menu`
WHERE `id` BETWEEN 9001 AND 9009;

INSERT INTO `system_menu` (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`, `icon`,
                           `component`, `component_name`, `status`, `visible`, `keep_alive`, `always_show`,
                           `creator`, `create_time`, `updater`, `update_time`, `deleted`)
VALUES
-- 页面菜单（type=2），component 指向前端工程路径
(9001, '项目台账', '', 2, 1, 9000, 'project', 'ep:list', 'project/project/index', 'Project', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
-- 按钮权限（type=3），与 ProjectController 的 @PreAuthorize 一一对应
(9002, '项目查询', 'project:project:query', 3, 1, 9001, '', '', '', '', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(9003, '项目创建', 'project:project:create', 3, 2, 9001, '', '', '', '', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(9004, '项目更新', 'project:project:update', 3, 3, 9001, '', '', '', '', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'),
(9005, '项目删除', 'project:project:delete', 3, 4, 9001, '', '', '', '', 0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0');

-- ------------------------------------------------------------------ 说明
-- 1. 子菜单（type=2 菜单 / type=3 按钮）在对应业务周随功能落地时补充，
--    约定父节点 id 即上面的 9000/9010/9020/9030/9040，子节点 id 从 9001 起顺排。
-- 2. 角色授权：给租户超管角色（system_role.code = 'super_admin'）授权由基座「角色管理」
--    界面点选即可，本脚本不写 system_role_menu，避免与基座 id 生成策略耦合。
-- 3. component 为 NULL 表示纯目录节点（不渲染具体页面），配合基座 vue3 路由的
--    layout 父级约定，子菜单才有 component。
