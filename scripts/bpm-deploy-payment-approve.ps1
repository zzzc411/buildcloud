# =====================================================================================
# 筑云 BuildCloud - 进度款审批流程（payment-approve）部署脚本
#
# 作用：把 sql/buildcloud/bpm/payment-approve.bpmn20.xml 部署为 bpm-server 的流程定义：
#   步骤1 - 登录 admin 拿 accessToken（经验 L1-1d：中文 body 必须走 UTF-8 字节体）
#   步骤2 - POST /admin-api/bpm/model/create：建流程模型（BPMN 设计器类型 + 业务表单）
#            若同 key 模型已存在（错误码 1009002000 MODEL_KEY_EXISTS），复用已有模型
#   步骤3 - POST /admin-api/bpm/model/deploy：部署为 Flowable 流程定义（ACT_RE_PROCDEF）
#            并写扩展表 bpm_process_definition_info
#
# 与 bpm-deploy-project-approve.ps1 的关系：
#   本脚本是它的同构副本（W3-P5 进度款流程），**保留**了 P4 实测修过的 3 个缺陷：
#   ① 复用已有模型时必须显式刷新 bpmnXml（否则 deploy 部署的是旧 XML，L2-14）；
#   ② 单出线不用排他网关（BPMN 侧，见 payment-approve.bpmn20.xml 文件头，L2-13）；
#   ③ 文件必须 UTF-8 with BOM（否则 PS 5.1 解析中文注释失败，L1-3）。
#   差异只在：modelKey / modelName / category / 业务表单路径 / 默认 BPMN 文件名。
#
# 幂等性：重复执行会部署出「新版本」的流程定义，并把旧版本挂起（基座 deployModel 的正常语义，
#   只有最新版本可发起）。这是流程定义版本管理，不是脏数据，故无需额外去重。
#
# 用法（仓库根目录）：
#   powershell -ExecutionPolicy Bypass -File scripts/bpm-deploy-payment-approve.ps1
#
# 依赖：中间件 + system-server + bpm-server 已启动；已执行 sql/buildcloud/05-bpm-seed.sql
#       （流程模型引用分类 code=FINANCE，审批人引用用户组 9202 财务审批组）。
#
# 编码与语法注意（PS 5.1 实测踩坑，勿改）：
#   - 文件必须存为 UTF-8 with BOM（lessons-learned L1-3），否则中文注释解析报错；
#   - 注释里不要写带圈序号（U+2460 起）这类小众标点，PS 5.1 会把整段注释吞掉并与后续代码拼接，
#     报成看似毫无关联的 "Missing ')' in function parameter list"；
#   - 字符串里的数字不要写成 1_000_000 下划线分隔形态。
# =====================================================================================
[CmdletBinding()]
param(
    [string]$Gateway = 'http://127.0.0.1:48080',
    [string]$Username = 'admin',
    # 默认口令不入库（AGENTS R-1）：走环境变量 BUILDCLOUD_ADMIN_PASSWORD。
    # 本地演示环境基座默认口令见 README「快速开始」，不在此处硬编码。
    [string]$Password = $env:BUILDCLOUD_ADMIN_PASSWORD,
    [string]$TenantId = '1',
    [string]$ModelKey = 'payment-approve',
    [string]$ModelName = '进度款审批',
    [string]$Category = 'FINANCE',
    [int]$ModelKeyExistsCode = 1009002000,   # ErrorCodeConstants.MODEL_KEY_EXISTS
    [string]$BpmnFile = ''
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

# 口令必须由调用方提供（AGENTS R-1：仓库内不落真实/默认口令）
if ([string]::IsNullOrWhiteSpace($Password)) {
    throw 'Password is empty. Set it before running, e.g.:  $env:BUILDCLOUD_ADMIN_PASSWORD="<admin password>"; powershell -File scripts/bpm-deploy-payment-approve.ps1'
}

$RepoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($BpmnFile)) {
    $BpmnFile = Join-Path $RepoRoot 'sql\buildcloud\bpm\payment-approve.bpmn20.xml'
}
if (-not (Test-Path -LiteralPath $BpmnFile)) {
    throw "BPMN file not found: $BpmnFile"
}

# 统一请求工具：body 一律使用 UTF-8 字节数组（L1-1d：PS 5.1 默认 ISO-8859-1，中文会变 ?）
# 参数默认值不要写 = @{}（空哈希字面量紧跟参数声明时 PS 5.1 解析器会断），用 $null 表示「无额外请求头」。
function Invoke-Json {
    param (
        [string]$Method = 'GET',
        [string]$Path,
        [object]$Body = $null,
        [hashtable]$Headers = $null
    )
    $uri = $Gateway.TrimEnd('/') + $Path
    $base = @{ 'tenant-id' = $TenantId; 'Content-Type' = 'application/json; charset=utf-8' }
    if ($null -ne $Headers) {
        foreach ($k in $Headers.Keys) { $base[$k] = $Headers[$k] }
    }
    if ($null -eq $Body) {
        return Invoke-RestMethod -Uri $uri -Method $Method -Headers $base
    }
    $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 20 -Compress }
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Uri $uri -Method $Method -Headers $base -Body $bytes
}

# ---------------------------------------------------------------- 步骤1 登录
Write-Host '[1/3] login via gateway ...'
$login = Invoke-Json -Method POST -Path '/admin-api/system/auth/login' -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
if ($login.code -ne 0) { throw "login failed: code=$($login.code) msg=$($login.msg)" }
$auth = @{ 'Authorization' = 'Bearer ' + $login.data.accessToken }
Write-Host "      login ok, userId=$($login.data.userId)"

# ---------------------------------------------------------------- 步骤2 创建流程模型
Write-Host "[2/3] create bpm model (key=$ModelKey, name=$ModelName, category=$Category) ..."
$bpmnXml = [IO.File]::ReadAllText($BpmnFile, [Text.Encoding]::UTF8)
$createBody = [ordered]@{
    key                       = $ModelKey
    name                      = $ModelName
    category                  = $Category
    description               = 'Payment progress approval: finance approves, pass creates payment record and accumulates contract paid amount'
    type                      = 10          # BpmModelTypeEnum.BPMN
    formType                  = 20          # BpmModelFormTypeEnum.CUSTOM（业务表单）
    formCustomCreatePath      = '/buildcloud-project/contract/approval/submit'
    formCustomViewPath        = '/buildcloud-project/contract/approval/detail'
    visible                   = $true
    managerUserIds            = @(1)        # 模型管理员 admin，否则 deploy 报 MODEL_UPDATE_FAIL_NOT_MANAGER
    allowCancelRunningProcess = $true
    allowWithdrawTask         = $false
    bpmnXml                   = $bpmnXml
}

$modelId = $null
$create = Invoke-Json -Method POST -Path '/admin-api/bpm/model/create' -Body $createBody -Headers $auth -ErrorAction SilentlyContinue
if ($null -ne $create -and $create.code -eq 0) {
    $modelId = $create.data
    Write-Host "      model created, modelId=$modelId"
} else {
    $code = if ($null -ne $create) { $create.code } else { 'n/a' }
    $msg = if ($null -ne $create) { $create.msg } else { 'request error' }
    Write-Host "      model create returned code=$code ($msg), try to reuse existing model ..."
}

if ($null -eq $modelId) {
    $page = Invoke-Json -Method GET -Path "/admin-api/bpm/model/list?name=$([uri]::EscapeDataString($ModelName))" -Headers $auth
    if ($page.code -ne 0) { throw "list bpm model failed: code=$($page.code) msg=$($page.msg)" }
    $hit = @($page.data | Where-Object { $_.key -eq $ModelKey }) | Select-Object -First 1
    if ($null -eq $hit) { $hit = @($page.data) | Select-Object -First 1 }
    if ($null -eq $hit) { throw "model not created and not found by key=$ModelKey, check ACT_RE_MODEL manually" }
    $modelId = $hit.id
    Write-Host "      reuse existing model, modelId=$modelId"

    # ---------------------------------------------------------------- 步骤2b 复用模型时刷新 BPMN 内容
    # W3-P4 实测缺陷修复（lessons-learned L2-14）：model/create 在 key 已存在时返回 MODEL_KEY_EXISTS，
    # 脚本走「复用」分支，但**复用的模型里存的是当初创建时的旧 bpmnXml**——之后改了
    # sql/buildcloud/bpm/payment-approve.bpmn20.xml 再跑本脚本，deploy 部署的仍是旧 XML，
    # 表现为「改了文件但部署报的还是旧错误」。
    # 因此复用分支必须显式把最新 XML 写回模型。
    Write-Host '[2b/3] refresh bpmn xml of reused model ...'
    $refresh = Invoke-Json -Method PUT -Path '/admin-api/bpm/model/update-bpmn' -Headers $auth -Body ([ordered]@{
        id      = $modelId
        bpmnXml = $bpmnXml
    })
    if ($refresh.code -ne 0) { throw "update-bpmn failed: code=$($refresh.code) msg=$($refresh.msg)" }
    Write-Host '      bpmn xml refreshed'
}

# ---------------------------------------------------------------- 步骤3 部署
Write-Host '[3/3] deploy process definition ...'
$deploy = Invoke-Json -Method POST -Path "/admin-api/bpm/model/deploy?id=$modelId" -Headers $auth
if ($deploy.code -ne 0) { throw "deploy failed: code=$($deploy.code) msg=$($deploy.msg)" }

# 回查部署结果：流程定义 key/name/version 直接取 ACT_RE_PROCDEF，作为可核对的验收证据
$pd = Invoke-Json -Method GET -Path "/admin-api/bpm/process-definition/page?key=$ModelKey&pageNo=1&pageSize=5" -Headers $auth
if ($pd.code -eq 0 -and $null -ne $pd.data -and $null -ne $pd.data.list) {
    foreach ($item in $pd.data.list) {
        Write-Host ("      deployed: id={0} key={1} name={2} version={3} suspensionState={4}" -f $item.id, $item.key, $item.name, $item.version, $item.suspensionState)
    }
}

Write-Host ''
Write-Host '[OK] deploy finished. Verify with curl.exe (avoid PS console encoding issue):'
Write-Host "  curl.exe -s -H `"Authorization: Bearer $($login.data.accessToken)`" -H `"tenant-id: $TenantId`" `"$Gateway/admin-api/bpm/process-definition/page?key=$ModelKey`""
