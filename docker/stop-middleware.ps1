# 筑云 BuildCloud — 中间件一键停止（W1 Phase 1）
# 用法：
#   docker/stop-middleware.ps1          # 停止全部容器（保留数据卷，下次启动数据还在）
#   docker/stop-middleware.ps1 -Purge   # 停止并删除数据卷【不可逆】会清空数据库/存储数据，确认后再用
param(
    [switch]$Purge
)

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot 'middleware.yaml'

if (-not (Test-Path (Join-Path $PSScriptRoot '.env'))) {
    Write-Warning "未找到 docker/.env：停止/清理不需要密码，但 compose 可能提示缺变量。此时可直接用：docker compose -f docker/middleware.yaml down"
}

& docker compose -f $composeFile down

if ($Purge) {
    Write-Warning '-Purge：正在删除全部数据卷（mysql/redis/nacos/rocketmq/minio/pg 数据），此操作不可逆！'
    & docker compose -f $composeFile down -v
}
