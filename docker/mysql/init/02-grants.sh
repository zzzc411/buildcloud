#!/usr/bin/env bash
# =============================================================================
# 筑云 BuildCloud — MySQL 初始化：账号授权（/docker-entrypoint-initdb.d/02-grants.sh）
# 在 MySQL 官方镜像的初始化流程中执行，容器环境变量由 docker/middleware.yaml 注入。
# R-1：密码全部来自环境变量，不落盘明文、不提交仓库。
# =============================================================================
set -euo pipefail

: "${MYSQL_ROOT_PASSWORD:?missing env MYSQL_ROOT_PASSWORD}"
: "${MYSQL_USER:?missing env MYSQL_USER}"
: "${MYSQL_PASSWORD:?missing env MYSQL_PASSWORD}"
XXL_JOB_DB_USER="${XXL_JOB_DB_USER:-xxl_job}"
: "${XXL_JOB_DB_PASSWORD:?missing env XXL_JOB_DB_PASSWORD}"

# 业务账号（MYSQL_USER@'%'）由 mysql 镜像自动创建并授权 MYSQL_DATABASE（ruoyi-vue-pro），
# 此处补充：D18 分片库授权 + XXL-Job 专用账号
mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOSQL
-- D18：分片库 pm_log_0..3 授权给业务账号（逻辑 4 库，单机多 schema 模拟）
GRANT ALL PRIVILEGES ON pm_log_0.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON pm_log_1.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON pm_log_2.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON pm_log_3.* TO '${MYSQL_USER}'@'%';

-- XXL-Job 调度中心专用账号（仅本地 compose 网络使用；xxl_job 库由 03 号脚本创建）
CREATE USER IF NOT EXISTS '${XXL_JOB_DB_USER}'@'%' IDENTIFIED BY '${XXL_JOB_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON xxl_job.* TO '${XXL_JOB_DB_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL

echo "[bc-init] grants done: pm_log_0..3 -> ${MYSQL_USER}@'%'; xxl_job.* -> ${XXL_JOB_DB_USER}@'%'"
