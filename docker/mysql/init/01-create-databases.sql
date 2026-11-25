-- ============================================================================
-- 筑云 BuildCloud — MySQL 首次启动初始化（docker/middleware.yaml 挂载到
-- /docker-entrypoint-initdb.d/01-create-databases.sql，仅空数据卷首次启动时执行）
-- 依据：docs/plans/plan-w1-infra-up.md Phase 1；spec v1.1 §3.3 / D18
-- ============================================================================

-- 基座业务库：库名与 yudao-cloud master-jdk17 的 application-dev.yaml、
-- script/docker-compose/docker-compose.yml 保持一致（ruoyi-vue-pro，utf8mb4）
CREATE DATABASE IF NOT EXISTS `ruoyi-vue-pro` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- D18：材料申领流水「逻辑 4 库 × 8 表」以单机 MySQL 多 schema 模拟（W6 分片压测使用）
CREATE DATABASE IF NOT EXISTS `pm_log_0` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `pm_log_1` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `pm_log_2` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `pm_log_3` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- xxl_job 库由 03-tables_xxl_job.sql（xuxueli/xxl-job 2.4.0 官方 tables_xxl_job.sql）自建
-- seata-server 本地使用 file 存储模式，不建库；undo_log 表由各业务库按需创建（W2/W3）
