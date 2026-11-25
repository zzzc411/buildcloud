<#
.SYNOPSIS
    筑云 BuildCloud — 密钥 / 敏感信息扫描脚本（提交前自查，AGENTS.md R-1 / R8）。
.DESCRIPTION
    对「全仓已跟踪 + 未忽略的未跟踪文件」或「暂存区 diff」做常见密钥模式扫描：
      - LLM / AI Key（sk- 前缀）
      - AWS AccessKey（AKIA）
      - 密码 / Secret / api-key / token 明文赋值
      - 连接串内嵌密码（user:pass@host）
      - 私网 IP（192.168. / 10. / 172.16-31.）
      - PEM 私钥块
    白名单：占位符与示例值（ChangeMe / replace-with / sk-xxxx / your- / <...> 等），
            也支持仓库根目录可选文件 .secretsignore（每行一个「包含即忽略」的文本，# 开头为注释）。
    发现命中输出清单并以退出码 1 结束；无命中退出码 0。
.PARAMETER Staged
    只扫描暂存区新增内容（git diff --cached），用于 commit 前自查。
.PARAMETER Root
    仓库根目录，默认为脚本所在目录的上一级。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\scan-secrets.ps1
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\scan-secrets.ps1 -Staged
#>
[CmdletBinding()]
param(
    [string]$Root = '',
    [switch]$Staged
)

$ErrorActionPreference = 'Stop'

if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
if (-not (Test-Path -LiteralPath (Join-Path $Root '.git'))) {
    throw "未在 $Root 找到 .git，请用 -Root 指定仓库根目录。"
}

# ---------------------------------------------------------------- 扫描规则
# Extensions = 仅扫描这些扩展名（为空表示全部支持的类型）；Java 源码里的
# 字符串变量声明与 @Schema 示例地址属于代码噪音，不参与「赋值 / 私网 IP」类规则。
$ConfigExtensions = @(
    '.yaml', '.yml', '.properties', '.json', '.xml', '.env', '.example', '.template',
    '.ps1', '.sh', '.conf', '.ini', '.toml', '.md', '.txt', '.sql', '.gitignore'
)

$Rules = @(
    [pscustomobject]@{ Name = 'LLM/AI 密钥（sk-）'; Pattern = 'sk-[A-Za-z0-9_\-]{16,}'; Extensions = @() },
    [pscustomobject]@{ Name = 'AWS AccessKey（AKIA）'; Pattern = 'AKIA[0-9A-Z]{16}'; Extensions = @() },
    [pscustomobject]@{ Name = '密码明文赋值'; Pattern = '(?i)\b(password|passwd|pwd)\s*[:=]\s*[\x22\x27]?[^\s\x22\x27\x60<$#]{1,}'; Extensions = $ConfigExtensions },
    [pscustomobject]@{ Name = 'Secret / api-key / token 明文赋值'; Pattern = '(?i)\b(secret|api[-_]?key|access[-_]?key|client[-_]?secret|auth[-_]?token|access[-_]?token|private[-_]?key)\s*[:=]\s*[\x22\x27]?[^\s\x22\x27\x60<$#]{1,}'; Extensions = $ConfigExtensions },
    [pscustomobject]@{ Name = '连接串内嵌密码（user:pass@host）'; Pattern = '(?i)://[^\s:/]+:[^\s@/]+@'; Extensions = @() },
    [pscustomobject]@{ Name = '私网 IP'; Pattern = '\b(192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})\b'; Extensions = $ConfigExtensions; SkipUnderTest = $true },
    [pscustomobject]@{ Name = 'PEM 私钥块'; Pattern = '-----BEGIN [A-Z ]*PRIVATE KEY-----'; Extensions = @() }
)

# 占位符 / 公开示例值白名单（按「行内包含」判定，忽略大小写）
$AllowTokens = @(
    'ChangeMe',
    'change-me',
    'replace-with',
    'sk-xxxx',
    'your-',
    'your_',
    'placeholder',
    '<...>',
    'default_token',
    'devkey',
    'xxx',
    'secret-poc-key-min-32-chars-required-here',
    'YnVpbGRjbG91ZC1sb2NhbC1uYWNvcy10b2tlbi1jaGFuZ2UtbWU='   # docker/.env.template 的公开演示 Nacos Token（base64 of "buildcloud-local-nacos-token-change-me"）
)

# 可选仓库级补充白名单
$SecretsIgnore = Join-Path $Root '.secretsignore'
if (Test-Path -LiteralPath $SecretsIgnore) {
    foreach ($l in [IO.File]::ReadAllLines($SecretsIgnore, [Text.Encoding]::UTF8)) {
        $t = $l.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $AllowTokens += $t
    }
}

$ExcludePathPatterns = @(
    '(^|/)\.git/',
    '(^|/)target/',
    '(^|/)node_modules/',
    '(^|/)dist/',
    '(^|/)logs/',
    '(^|/)\.idea/',
    '^_tmp_',
    '\.flattened-pom\.xml$',
    '(^|/)yudao-cloud-ref/'   # 只读参考克隆（如放仓库内）
)

$ScanExtensions = @(
    '.yaml', '.yml', '.properties', '.json', '.xml', '.env', '.example', '.template',
    '.ps1', '.sh', '.conf', '.ini', '.toml', '.md', '.txt', '.java', '.sql', '.gitignore'
)

function Test-ExcludedPath([string]$RelPath) {
    foreach ($p in $ExcludePathPatterns) {
        if ($RelPath -match $p) { return $true }
    }
    return $false
}

function Test-AllowedLine([string]$Line) {
    foreach ($token in $AllowTokens) {
        if ($Line.IndexOf($token, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    }
    return $false
}

# 屏蔽 ${...} 占位符（含 $$ {...}、嵌套）与 YAML 行内注释，避免误报
function Convert-MaskedLine([string]$Line) {
    $s = $Line
    for ($i = 0; $i -lt 6; $i++) {
        $next = [regex]::Replace($s, '\$\{[^{}]*\}', '<ENV>')
        if ($next -eq $s) { break }
        $s = $next
    }
    $s = [regex]::Replace($s, '\s+#.*$', '')
    return $s
}

$Hits = New-Object System.Collections.ArrayList

function Scan-Line([string]$DisplayPath, [int]$LineNo, [string]$RawLine) {
    $masked = Convert-MaskedLine $RawLine
    if ($masked.Trim() -eq '') { return }
    $ext = ''
    try { $ext = [IO.Path]::GetExtension($DisplayPath) } catch { }
    foreach ($rule in $Rules) {
        if ($rule.Extensions.Count -gt 0 -and ($rule.Extensions -notcontains $ext)) { continue }
        if ($rule.SkipUnderTest -and $DisplayPath -match '(^|/)src/test/') { continue }
        if ($masked -match $rule.Pattern) {
            if (Test-AllowedLine $RawLine) { continue }
            [void]$Hits.Add([pscustomobject]@{
                File    = $DisplayPath
                Line    = $LineNo
                Rule    = $rule.Name
                Content = $RawLine.Trim()
            })
        }
    }
}

# ---------------------------------------------------------------- 暂存区 diff 模式
if ($Staged) {
    Write-Host '模式：暂存区 diff（git diff --cached -U0）'
    $diff = & git -C $Root diff --cached --unified=0 --no-color --no-ext-diff 2>$null
    $currentFile = ''
    $newLineNo = 0
    foreach ($line in $diff) {
        if ($line -match '^\+\+\+ b/(.+)$') { $currentFile = $Matches[1]; continue }
        if ($line -match '^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@') { $newLineNo = [int]$Matches[1]; continue }
        if ($line.StartsWith('+') -and -not $line.StartsWith('+++')) {
            if ($currentFile -and -not (Test-ExcludedPath $currentFile)) {
                Scan-Line $currentFile $newLineNo ($line.Substring(1))
            }
            $newLineNo++
        }
    }
} else {
    Write-Host '模式：全仓扫描（已跟踪 + 未忽略的未跟踪文件）'
    $files = & git -C $Root -c core.quotePath=false ls-files -co --exclude-standard 2>$null
    $scanned = 0
    foreach ($rel in $files) {
        if (-not $rel) { continue }
        if (Test-ExcludedPath $rel) { continue }
        $ext = ''
        try { $ext = [IO.Path]::GetExtension($rel) } catch { continue }
        if ($ScanExtensions -notcontains $ext) { continue }
        $full = Join-Path $Root $rel
        if (-not (Test-Path -LiteralPath $full)) { continue }
        $lines = $null
        try { $lines = [IO.File]::ReadAllLines($full, [Text.Encoding]::UTF8) } catch { continue }
        $scanned++
        for ($i = 0; $i -lt $lines.Count; $i++) {
            Scan-Line $rel ($i + 1) $lines[$i]
        }
    }
    Write-Host "已扫描文件数：$scanned"
}

# ---------------------------------------------------------------- 输出
Write-Host ''
if ($Hits.Count -eq 0) {
    Write-Host '[通过] 未发现疑似密钥 / 私网 IP / 私钥块。' -ForegroundColor Green
    exit 0
}

Write-Host ("[发现] 共 {0} 处疑似敏感信息（请逐条确认；配置中的占位符请写成 Spring 环境变量占位或用 .secretsignore 白名单）：" -f $Hits.Count) -ForegroundColor Red
$Hits |
    Sort-Object File, Line |
    ForEach-Object {
        $content = $_.Content
        if ($content.Length -gt 160) { $content = $content.Substring(0, 160) + '...' }
        Write-Host ("{0}:{1}  [{2}]" -f $_.File, $_.Line, $_.Rule)
        Write-Host ("    {0}" -f $content)
    }

Write-Host ''
Write-Host ("汇总：{0} 处命中，涉及 {1} 个文件。" -f $Hits.Count, ($Hits | Select-Object -ExpandProperty File -Unique).Count)
exit 1
