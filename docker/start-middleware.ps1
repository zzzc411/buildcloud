# 筑云 BuildCloud — 中间件一键启动（W1 Phase 1）
# 用法（PowerShell，仓库根目录或任意位置均可）：
#   docker/start-middleware.ps1              # 启动默认组（8 容器，不含 MinIO）
#   docker/start-middleware.ps1 -CoreOnly    # 只起基础层 mysql/redis/nacos（16G 机器分批启动）
#   docker/start-middleware.ps1 -WithMinio   # 追加 MinIO（需镜像可用，见 deploy-local.md §6 阻塞记录）
# 详见 docs/ops/middleware-limits.md（内存限额与分批建议）、docs/ops/deploy-local.md
param(
    [switch]$CoreOnly,
    [switch]$WithMinio
)

$ErrorActionPreference = 'Stop'
$dockerDir = $PSScriptRoot
$composeFile = Join-Path $dockerDir 'middleware.yaml'
$envFile = Join-Path $dockerDir '.env'

if (-not (Test-Path $envFile)) {
    Write-Error @"
未找到 docker/.env（本地环境变量，不入库）。
请先执行：Copy-Item docker/.env.template docker/.env
并修改其中的占位密码（ChangeMe_*）后再启动。
"@
}

# docker compose 会自动读取 compose 文件所在目录的 .env
$composeArgs = @('-f', $composeFile)
if ($WithMinio) { $composeArgs += @('--profile', 'minio') }
if ($CoreOnly) {
    Write-Host '[start] 仅启动基础层：mysql / redis / nacos' -ForegroundColor Cyan
    & docker compose @composeArgs up -d mysql redis nacos
} else {
    Write-Host '[start] 启动中间件默认组（8 容器；MinIO 因官方镜像停止匿名分发暂以 profile 门控，见 deploy-local.md §6）' -ForegroundColor Cyan
    & docker compose @composeArgs up -d
}

Write-Host '[start] 当前状态（等待 healthy 可用：docker compose -f docker/middleware.yaml ps）' -ForegroundColor Cyan
& docker compose @composeArgs ps
