<#
.SYNOPSIS
    筑云 BuildCloud — 本地基座服务一键启动脚本（Windows PowerShell）。
.DESCRIPTION
    1) 读取 docker/.env（中间件真实凭据，gitignored），导出为环境变量；
    2) 若各服务缺 src/main/resources/application-local.yaml，则从同目录
       application-local.yaml.template 复制，并提示按需核对；
    3) 按 system → infra → bpm → gateway 顺序启动，逐个等待 /actuator/health 就绪。
    依赖：已完成 `mvn -DskipTests -Dmaven.test.skip=true package`（jar 在各模块 target/ 下）；
          中间件已由 docker/start-middleware.ps1 启动且 healthy。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1 -Services system,infra
#>
[CmdletBinding()]
param(
    [string]$JavaHome = 'D:\project\tools\jdk-17.0.21.1+2',
    [string[]]$Services = @('system', 'infra', 'bpm', 'gateway'),
    [int]$HealthTimeoutSec = 300,
    [switch]$SkipHealthCheck,
    [switch]$SkipConfigCheck
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $RepoRoot 'docker\.env'
$EnvTemplate = Join-Path $RepoRoot 'docker\.env.template'

# ---------------------------------------------------------------- 环境检查
if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "未找到 docker/.env。请先复制 docker/.env.template 为 docker/.env 并填写中间件密码（见 docs/ops/deploy-local.md §2）。"
}

$JavaExe = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $JavaExe)) {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $JavaHome = $env:JAVA_HOME
        $JavaExe = Join-Path $JavaHome 'bin\java.exe'
        Write-Warning "参数 -JavaHome 不存在，改用 JAVA_HOME=$JavaHome"
    } else {
        throw "未找到 JDK 17：$JavaExe。请用 -JavaHome 指定（本机默认 D:\project\tools\jdk-17.0.21.1+2）。"
    }
}
$env:JAVA_HOME = $JavaHome

# ---------------------------------------------------------------- 读取 docker/.env
function Read-DotEnv([string]$Path) {
    $map = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $idx = $t.IndexOf('=')
        if ($idx -lt 1) { continue }
        $key = $t.Substring(0, $idx).Trim()
        $val = $t.Substring($idx + 1).Trim()
        if ($val.Length -ge 2 -and (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'")))) {
            $val = $val.Substring(1, $val.Length - 2)
        }
        $map[$key] = $val
    }
    return $map
}

function Get-EnvValue($Map, [string]$Key, [string]$Default = '') {
    if ($Map.ContainsKey($Key) -and $Map[$Key] -ne '') { return $Map[$Key] }
    return $Default
}

$dotenv = Read-DotEnv $EnvFile

$mysqlPort = Get-EnvValue $dotenv 'MYSQL_HOST_PORT' '13306'
$redisPort = Get-EnvValue $dotenv 'REDIS_HOST_PORT' '16379'
$nacosPort = Get-EnvValue $dotenv 'NACOS_HTTP_HOST_PORT' '8848'

# 与 application-local.yaml.template 中的占位符一一对应；敏感项无默认值，仅从 .env 注入
$env:MYSQL_HOST = '127.0.0.1'
$env:MYSQL_PORT = $mysqlPort
$env:MYSQL_DATABASE = Get-EnvValue $dotenv 'MYSQL_DATABASE' 'ruoyi-vue-pro'
$env:MYSQL_USER = Get-EnvValue $dotenv 'MYSQL_USER' 'yudao'
$env:MYSQL_PASSWORD = Get-EnvValue $dotenv 'MYSQL_PASSWORD' ''
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = $redisPort
$env:REDIS_PASSWORD = Get-EnvValue $dotenv 'REDIS_PASSWORD' ''
$env:NACOS_SERVER_ADDR = "127.0.0.1:$nacosPort"
$env:NACOS_NAMESPACE = 'public'
$env:NACOS_USERNAME = ''
$env:NACOS_PASSWORD = ''
$env:SPRING_BOOT_ADMIN_USERNAME = 'admin'
$env:SPRING_BOOT_ADMIN_PASSWORD = 'admin'
$env:XXL_JOB_ACCESS_TOKEN = Get-EnvValue $dotenv 'XXL_JOB_ACCESS_TOKEN' 'default_token'

if ([string]::IsNullOrEmpty($env:MYSQL_PASSWORD)) { Write-Warning 'docker/.env 未设置 MYSQL_PASSWORD，服务将无法连接数据库。' }
if ([string]::IsNullOrEmpty($env:REDIS_PASSWORD)) { Write-Warning 'docker/.env 未设置 REDIS_PASSWORD（本地 Redis 若开启 requirepass 会连接失败）。' }

# ---------------------------------------------------------------- Nacos 可达性
try {
    $client = New-Object System.Net.Sockets.TcpClient
    $iar = $client.BeginConnect('127.0.0.1', [int]$nacosPort, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne(2000, $false)) { throw 'timeout' }
    $client.EndConnect($iar)
    $client.Close()
    Write-Host "[OK] Nacos 可达 127.0.0.1:$nacosPort"
} catch {
    Write-Warning "Nacos 127.0.0.1:$nacosPort 不可达。请先运行 docker\start-middleware.ps1 并等待 nacos healthy。"
}

# ---------------------------------------------------------------- 服务定义
$definitions = @(
    [pscustomobject]@{ Key = 'system';  Port = 48081; Module = 'yudao-module-system\yudao-module-system-server' },
    [pscustomobject]@{ Key = 'infra';   Port = 48082; Module = 'yudao-module-infra\yudao-module-infra-server' },
    [pscustomobject]@{ Key = 'bpm';     Port = 48083; Module = 'yudao-module-bpm\yudao-module-bpm-server' },
    [pscustomobject]@{ Key = 'gateway'; Port = 48080; Module = 'yudao-gateway' }
)

$definitions = $definitions | Where-Object { $Services -contains $_.Key }
if (-not $definitions -or $definitions.Count -eq 0) {
    throw "未知的 -Services 取值：$($Services -join ',')。可选：system / infra / bpm / gateway。"
}

$LogDir = Join-Path $RepoRoot 'logs'
if (-not (Test-Path -LiteralPath $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

# ---------------------------------------------------------------- 配置自检
if (-not $SkipConfigCheck) {
    foreach ($def in $definitions) {
        $resDir = Join-Path $RepoRoot (Join-Path $def.Module 'src\main\resources')
        $localPath = Join-Path $resDir 'application-local.yaml'
        $tplPath = Join-Path $resDir 'application-local.yaml.template'
        if (Test-Path -LiteralPath $localPath) {
            Write-Host "[配置] $($def.Key)：已存在 application-local.yaml（gitignored）"
        } elseif (Test-Path -LiteralPath $tplPath) {
            Copy-Item -LiteralPath $tplPath -Destination $localPath
            Write-Host "[配置] $($def.Key)：已从模板生成 application-local.yaml"
            Write-Host '        提示：模板中的 ${环境变量:默认值} 占位符由环境变量注入；本脚本已按 docker/.env 导出，一般无需手改。'
        } else {
            Write-Warning "[配置] $($def.Key)：缺少 application-local.yaml 且无模板，服务可能无法连中间件。"
        }
    }
}

function Get-ServiceJar([string]$ModuleRelPath) {
    $targetDir = Join-Path $RepoRoot (Join-Path $ModuleRelPath 'target')
    if (-not (Test-Path -LiteralPath $targetDir)) { return $null }
    $jars = Get-ChildItem -LiteralPath $targetDir -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
        Sort-Object LastWriteTime -Descending
    if ($jars.Count -eq 0) { return $null }
    return $jars[0].FullName
}

function Wait-Service([string]$Name, [int]$Port, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $uri = "http://127.0.0.1:$Port/actuator/health"
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-WebRequest -Uri $uri -UseBasicParsing -TimeoutSec 3
            if ($resp.StatusCode -eq 200) { return $true }
        } catch { }
        Start-Sleep -Seconds 2
    }
    return $false
}

# ---------------------------------------------------------------- 顺序启动
$started = @()
foreach ($def in $definitions) {
    $jar = Get-ServiceJar $def.Module
    if (-not $jar) {
        Write-Warning "[启动] $($def.Key)：未找到 jar（$($def.Module)\target\*.jar）。请先执行全量 package。"
        continue
    }

    $outLog = Join-Path $LogDir ("{0}.out.log" -f $def.Key)
    $errLog = Join-Path $LogDir ("{0}.err.log" -f $def.Key)
    # application-local.yaml 是本地未跟踪文件（gitignored），打包时不一定进 jar；
    # 用 additional-location 指到模块源码 resources 目录，确保运行期能读到本地配置。
    $resDir = Join-Path $RepoRoot (Join-Path $def.Module 'src\main\resources')
    $resDirArg = '--spring.config.additional-location=optional:file:' + ($resDir -replace '\\', '/') + '/'
    $argList = @(
        '-Xms128m', '-Xmx768m', '-XX:MaxMetaspaceSize=360m', '-XX:+UseSerialGC',
        '-jar', $jar, $resDirArg, '--spring.profiles.active=local'
    )
    Write-Host "[启动] $($def.Key)：$([IO.Path]::GetFileName($jar))（端口 $($def.Port)）"
    $proc = Start-Process -FilePath $JavaExe -ArgumentList $argList -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog -WindowStyle Hidden -PassThru
    $started += [pscustomobject]@{ Key = $def.Key; Port = $def.Port; Process = $proc; OutLog = $outLog; ErrLog = $errLog }

    if (-not $SkipHealthCheck) {
        Write-Host "[等待] $($def.Key) /actuator/health 就绪（最长 $HealthTimeoutSec 秒；bpm 首启建 Flowable 表约 60 秒）..."
        if (Wait-Service $def.Key $def.Port $HealthTimeoutSec) {
            Write-Host "[就绪] $($def.Key) http://127.0.0.1:$($def.Port)/actuator/health"
        } else {
            Write-Warning "[超时] $($def.Key) 未在 $HealthTimeoutSec 秒内就绪；日志末尾："
            if (Test-Path -LiteralPath $outLog) { Get-Content -LiteralPath $outLog -Tail 20 | ForEach-Object { "  $_" } }
            if (Test-Path -LiteralPath $errLog) { Get-Content -LiteralPath $errLog -Tail 10 | ForEach-Object { "  $_" } }
        }
    }
}

# ---------------------------------------------------------------- 汇总
Write-Host ''
Write-Host '================ 启动汇总 ================'
foreach ($s in $started) {
    $alive = -not $s.Process.HasExited
    Write-Host ("{0,-8} pid={1,-7} port={2} 状态={3} 日志={4}" -f $s.Key, $s.Process.Id, $s.Port, ($(if ($alive) { '运行中' } else { '已退出' })), $s.OutLog)
}
Write-Host '网关入口：http://127.0.0.1:48080'
Write-Host '停止服务：Get-Process java | Stop-Process（或按上面 pid 逐个 Stop-Process -Id <pid>）'
Write-Host 'API 文档（网关聚合）：http://127.0.0.1:48080/doc.html'
