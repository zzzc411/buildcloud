<#
.SYNOPSIS
    筑云 BuildCloud — 本地服务一键启动脚本（Windows PowerShell）。
.DESCRIPTION
    1) 读取 docker/.env（中间件真实凭据，gitignored），导出为环境变量；
    2) 若各服务缺 src/main/resources/application-local.yaml，则从同目录
       application-local.yaml.template 复制，并提示按需核对；
    3) 按 基座（system→infra→bpm→gateway）+ 自研（project→material→report→ai）
       顺序启动，逐个等待「端口可连接」后视为就绪。
    依赖：已完成 `mvn -DskipTests -Dmaven.test.skip=true package`（jar 在各模块 target/ 下）；
          中间件已由 docker/start-middleware.ps1 启动且 healthy。

.NOTES
    健康探活为什么用 TCP 端口探测，而不是 /actuator/health？
    基座各服务的 actuator 来自 yudao-spring-boot-starter-monitor，其中 actuator 依赖为
    <optional>true</optional>，不会传递到业务服务，因此 /actuator/health 在本地通常直接 404。
    端口探测只验证「进程已起来并监听端口」，不依赖任何服务端实现，慢启动服务（如 bpm 首启
    建 Flowable 表约 60 秒）也不会误报超时。需要业务级就绪时，可另行访问
    GET /admin-api/<svc>/health（自研模块）或
    GET /admin-api/system/user/get（基座，需登录态）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1 -Services system,infra
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1 -Services buildcloud,gateway
#>
[CmdletBinding()]
param(
    [string]$JavaHome = 'D:\project\tools\jdk-17.0.21.1+2',
    [string[]]$Services = @('system', 'infra', 'bpm', 'gateway', 'project', 'material', 'report', 'ai'),
    [int]$HealthTimeoutSec = 300,
    [switch]$SkipHealthCheck,
    [switch]$SkipConfigCheck
)

$ErrorActionPreference = 'Stop'

# PowerShell 以 -File 方式调用脚本时，`-Services system,infra` 会被当成**一个**字符串
# "system,infra" 绑定到 [string[]]，导致按单个未知服务报错。这里统一按逗号再拆一次。
$Services = @($Services | ForEach-Object { $_ -split '[,; ]' } | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
if ($Services -contains 'all') {
    $Services = @('system', 'infra', 'bpm', 'gateway', 'project', 'material', 'report', 'ai')
}
# 便捷别名：-Services buildcloud 等价于四个自研模块
if ($Services -contains 'buildcloud') {
    $Services = @($Services | Where-Object { $_ -ne 'buildcloud' }) + @('project', 'material', 'report', 'ai')
}

$RepoRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $RepoRoot 'docker\.env'
$EnvTemplate = Join-Path $RepoRoot 'docker\.env.template'

# ---------------------------------------------------------------- 环境检查
if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "未找到 docker/.env。请先复制 docker/.env.template 为 docker/.env 并填写中间件密码（见 README「快速开始」）。"
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
function Test-TcpPort([string]$ComputerName, [int]$Port, [int]$TimeoutMs = 2000) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($ComputerName, $Port, $null, $null)
        if (-not $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) { return $false }
        $client.EndConnect($iar)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

if (Test-TcpPort '127.0.0.1' ([int]$nacosPort)) {
    Write-Host "[OK] Nacos 可达 127.0.0.1:$nacosPort"
} else {
    Write-Warning "Nacos 127.0.0.1:$nacosPort 不可达。请先运行 docker\start-middleware.ps1 并等待 nacos healthy。"
}

# ---------------------------------------------------------------- 服务定义
# 启动顺序：基座 system → infra → bpm → gateway，再到自研 project → material → report → ai。
# 自研服务依赖 Nacos 与 MySQL/Redis，也依赖 system-server 的 RPC（租户/字典），故排在基座之后。
# 端口段与 spec §3.2 / W2-P3 一致：基座 48080-48083，自研 48084-48087。
$definitions = @(
    [pscustomobject]@{ Key = 'system';   Port = 48081; Module = 'yudao-module-system\yudao-module-system-server';         Jar = 'yudao-module-system-server.jar' },
    [pscustomobject]@{ Key = 'infra';    Port = 48082; Module = 'yudao-module-infra\yudao-module-infra-server';           Jar = 'yudao-module-infra-server.jar' },
    [pscustomobject]@{ Key = 'bpm';      Port = 48083; Module = 'yudao-module-bpm\yudao-module-bpm-server';               Jar = 'yudao-module-bpm-server.jar' },
    [pscustomobject]@{ Key = 'gateway';  Port = 48080; Module = 'yudao-gateway';                                          Jar = 'yudao-gateway.jar' },
    [pscustomobject]@{ Key = 'project';  Port = 48084; Module = 'buildcloud-module-project\buildcloud-module-project-server';   Jar = 'buildcloud-module-project-server.jar' },
    [pscustomobject]@{ Key = 'material'; Port = 48085; Module = 'buildcloud-module-material\buildcloud-module-material-server'; Jar = 'buildcloud-module-material-server.jar' },
    [pscustomobject]@{ Key = 'report';   Port = 48086; Module = 'buildcloud-module-report\buildcloud-module-report-server';     Jar = 'buildcloud-module-report-server.jar' },
    [pscustomobject]@{ Key = 'ai';       Port = 48087; Module = 'buildcloud-module-ai\buildcloud-module-ai-server';             Jar = 'buildcloud-module-ai-server.jar' }
)

$allKeys = $definitions | Select-Object -ExpandProperty Key
$unknown = $Services | Where-Object { $allKeys -notcontains $_ }
if ($unknown) {
    throw "未知的 -Services 取值：$($unknown -join ',')。可选：$($allKeys -join ' / ')（或用 all 表示全部、buildcloud 表示四个自研服务）。"
}

$definitions = $definitions | Where-Object { $Services -contains $_.Key }
if (-not $definitions -or $definitions.Count -eq 0) {
    throw "未选择任何服务。可选：$($allKeys -join ' / ')。"
}

$LogDir = Join-Path $RepoRoot 'logs'
if (-not (Test-Path -LiteralPath $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

# ---------------------------------------------------------------- 配置自检
if (-not $SkipConfigCheck) {
    foreach ($def in $definitions) {
        $resDir = Join-Path $RepoRoot (Join-Path $def.Module 'src\main\resources')
        if (-not (Test-Path -LiteralPath $resDir)) { continue }
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

function Get-ServiceJar([string]$ModuleRelPath, [string]$PreferredJarName = '') {
    $targetDir = Join-Path $RepoRoot (Join-Path $ModuleRelPath 'target')
    if (-not (Test-Path -LiteralPath $targetDir)) { return $null }
    $jars = Get-ChildItem -LiteralPath $targetDir -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' }
    if ($jars.Count -eq 0) { return $null }
    # 优先用「不带版本号的 server jar」（各 -server 模块 finalName 固定为 artifactId）；
    # 兜底取最新写入的 jar（兼容改名或带版本号的情况）。
    if ($PreferredJarName) {
        $exact = $jars | Where-Object { $_.Name -eq $PreferredJarName } | Select-Object -First 1
        if ($exact) { return $exact.FullName }
    }
    return ($jars | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

# 启动子进程并重定向日志。
# 为什么不直接用 Start-Process -RedirectStandardOutput？
# 某些环境同时存在大小写重名环境变量（如 NO_PROXY 与 no_proxy），Windows PowerShell 5.1 的
# Environment provider 会报「已添加项。字典中的关键字"NO_PROXY"...」导致 Start-Process 直接失败。
# 用 .NET ProcessStartInfo（CreateNoWindow + 输出走 shell 重定向）可绕开该 provider，
# 行为等价：后台静默运行，stdout/stderr 分别落盘。
# 注意：返回值保留 .Id / .HasExited 语义，便于汇总段打印 pid。
function Start-ServiceProcess([string]$FileName, [string]$ArgumentLine, [string]$WorkingDirectory, [string]$OutLog, [string]$ErrLog) {
    # 用 cmd 做重定向与输出编码切换（chcp 65001），避免 Java 输出中文乱码
    $quoted = '"' + $FileName + '" ' + $ArgumentLine
    $cmdLine = '/c chcp 65001 > nul & ' + $quoted + ' > "' + $OutLog + '" 2> "' + $ErrLog + '"'
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $env:ComSpec
    $psi.Arguments = $cmdLine
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($psi)
    # 直接返回 Process 对象：保留 -PassThru 语义（.Id / .HasExited 均可用）
    return $process
}

# 端口探测就绪判定（见 .NOTES：基座服务没有 /actuator/health）
function Wait-ServicePort([int]$Port, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort '127.0.0.1' $Port 1500) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# ---------------------------------------------------------------- 顺序启动
$started = @()
foreach ($def in $definitions) {
    $jar = Get-ServiceJar $def.Module $def.Jar
    if (-not $jar) {
        Write-Warning "[启动] $($def.Key)：未找到 jar（$($def.Module)\target\*.jar）。请先执行全量 package（mvn -DskipTests -Dmaven.test.skip=true package）。"
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
    $argLine = ($argList | ForEach-Object { if ($_ -match '[\s]') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $proc = Start-ServiceProcess -FileName $JavaExe -ArgumentLine $argLine -WorkingDirectory $RepoRoot -OutLog $outLog -ErrLog $errLog
    $started += [pscustomobject]@{ Key = $def.Key; Port = $def.Port; Process = $proc; OutLog = $outLog; ErrLog = $errLog }

    if (-not $SkipHealthCheck) {
        Write-Host "[等待] $($def.Key) 端口 $($def.Port) 可连接（最长 $HealthTimeoutSec 秒；bpm 首启建 Flowable 表约 60 秒）..."
        if (Wait-ServicePort $def.Port $HealthTimeoutSec) {
            Write-Host "[就绪] $($def.Key) 127.0.0.1:$($def.Port) 已监听"
        } else {
            Write-Warning "[超时] $($def.Key) 端口 $($def.Port) 未在 $HealthTimeoutSec 秒内监听；日志末尾："
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
    Write-Host ("{0,-9} pid={1,-7} port={2} 状态={3} 日志={4}" -f $s.Key, $s.Process.Id, $s.Port, ($(if ($alive) { '运行中' } else { '已退出' })), $s.OutLog)
}
Write-Host '网关入口：http://127.0.0.1:48080'
Write-Host '自研健康检查（经网关）：http://127.0.0.1:48080/admin-api/buildcloud-project/health（另三个同理）'
Write-Host '停止服务：Get-Process java | Stop-Process（或按上面 pid 逐个 Stop-Process -Id <pid>）'
Write-Host 'API 文档（网关聚合）：http://127.0.0.1:48080/doc.html'
