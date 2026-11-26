-- 单元测试结束后清理数据（BaseDbUnitTest 通过 @Sql 在 AFTER_TEST_METHOD 阶段执行）
-- 约定：只清本模块自研表，基座表由各基座模块自己的 clean.sql 负责。

DELETE FROM "bc_project";
