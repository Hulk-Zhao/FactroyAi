# =============================================================================
# 构建三个前端并把整站发布到腾讯云 COS（源站），由 CDN 对外提供服务。
#
# 发布后的桶内布局（与站点 URL 一一对应）：
#   /                         site/public（主页、博客、demo 目录）
#   /demo/mfg/                projects/mfg-agent/web（原生 JS 演示屏）
#   /demo/hogo-qa/            projects/hogo-qa/frontend（React 构建产物）
#   /demo/mfg-agent-platform/ projects/smart-aps-agent/aps-frontend（Vue 构建产物）
#
# 依赖：
#   - Node.js >= 20、npm
#   - 腾讯云官方 coscli（独立二进制，不写入 package.json）
#       https://cloud.tencent.com/document/product/436/63144
#     首次使用先配置：coscli config add -b hulkai-cdn-1301421942 -r ap-guangzhou
#
# 用法（仓库根目录）：
#   powershell -File deploy/cos/publish.ps1                            # 用默认桶 + 默认地域
#   powershell -File deploy/cos/publish.ps1 -Bucket <另一个桶> -Region <地域>
#   powershell -File deploy/cos/publish.ps1 -SkipBuild                 # 只重新同步
#   powershell -File deploy/cos/publish.ps1 -Delete                    # 删除桶内多余对象（谨慎）
#   powershell -File deploy/cos/publish.ps1 -SkipUpload                # 只组装 dist（灾备/自有源用）
#
# 默认值对应线上实测配置（可被参数或环境变量 COS_BUCKET / COS_REGION 覆盖）。
# 说明：本脚本不读取、不写入任何密钥；coscli 使用本机 ~/.cos.yaml 中的凭据。
# =============================================================================
[CmdletBinding()]
param(
    [string]$Bucket = $(if ($env:COS_BUCKET) { $env:COS_BUCKET } else { 'hulkai-cdn-1301421942' }),
    [string]$Region = $(if ($env:COS_REGION) { $env:COS_REGION } else { 'ap-guangzhou' }),
    [string]$ApiBase = $(if ($env:API_BASE) { $env:API_BASE } else { 'https://api.hulkai.cn' }),
    [string]$BucketPrefix = '',
    [switch]$SkipBuild,
    [switch]$SkipUpload,
    [switch]$Delete
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Bucket) -and -not $SkipUpload) {
    throw '缺少 -Bucket。默认桶为 hulkai-cdn-1301421942；可先用 coscli config add -b <bucket> -r <region> 配置。'
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$apiBase = $ApiBase.TrimEnd('/')
$workDir = Join-Path $PSScriptRoot 'dist'
$apiUri = [Uri]$apiBase
$wsScheme = if ($apiUri.Scheme -eq 'https') { 'wss' } else { 'ws' }
$wsBase = "$wsScheme`://$($apiUri.Authority)/aps"

function Assert-LastExitCode([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step 失败（exit=$LASTEXITCODE）"
    }
}

function Invoke-Npm([string]$WorkingDir, [string[]]$Arguments) {
    Push-Location $WorkingDir
    try {
        & npm @Arguments
        Assert-LastExitCode "npm $($Arguments -join ' ')"
    }
    finally {
        Pop-Location
    }
}

# ---------- 1. 构建 ----------
if (-not $SkipBuild) {
    $hogoFrontend = Join-Path $repoRoot 'projects\hogo-qa\frontend'
    $hogoDist = Join-Path $hogoFrontend 'dist-server'
    if (-not (Test-Path (Join-Path $hogoFrontend 'node_modules'))) {
        Invoke-Npm $hogoFrontend @('ci', '--no-audit', '--no-fund')
    }
    Write-Host "[1/3] 构建 hogo-qa -> /demo/hogo-qa/" -ForegroundColor Cyan
    $env:HOGO_BASE = '/demo/hogo-qa/'
    # 认证请求走 api 子域；未设置时仍回退到页面同源（保持本地/旧部署行为）
    $env:VITE_API_BASE = "$apiBase/hogo/api/"
    Invoke-Npm $hogoFrontend @('run', 'build:server')

    $apsFrontend = Join-Path $repoRoot 'projects\smart-aps-agent\aps-frontend'
    $apsDist = Join-Path $apsFrontend 'dist'
    if (-not (Test-Path (Join-Path $apsFrontend 'node_modules'))) {
        Invoke-Npm $apsFrontend @('ci', '--no-audit', '--no-fund')
    }
    Write-Host "[2/3] 构建智能 APS -> /demo/mfg-agent-platform/" -ForegroundColor Cyan
    $env:APS_BASE = '/demo/mfg-agent-platform/'
    # REST 与 WebSocket 都指向 api.hulkai.cn；WS 必须是 wss:// 绝对地址
    $env:VITE_API_BASE_URL = "$apiBase/aps/api/v1"
    $env:VITE_WS_BASE_URL = $wsBase
    $env:VITE_ENABLE_MOCK = 'false'
    Invoke-Npm $apsFrontend @('run', 'build')

    Write-Host "[3/3] mfg-agent 演示屏为原生静态文件，无需构建" -ForegroundColor Cyan
}
else {
    $hogoDist = Join-Path $repoRoot 'projects\hogo-qa\frontend\dist-server'
    $apsDist = Join-Path $repoRoot 'projects\smart-aps-agent\aps-frontend\dist'
    Write-Host '[-SkipBuild] 跳过构建，直接使用现有产物' -ForegroundColor Yellow
}

foreach ($dist in @($hogoDist, $apsDist)) {
    if (-not (Test-Path $dist)) {
        throw "缺少构建产物：$dist（去掉 -SkipBuild 重新构建）"
    }
}

# ---------- 2. 组装发布目录 ----------
Write-Host "组装发布目录：$workDir" -ForegroundColor Cyan
if (Test-Path $workDir) {
    Remove-Item $workDir -Recurse -Force
}
New-Item -ItemType Directory -Path $workDir | Out-Null

Copy-Item -Path (Join-Path $repoRoot 'site\public\*') -Destination $workDir -Recurse -Force
Copy-Item -Path (Join-Path $repoRoot 'projects\mfg-agent\web') -Destination (Join-Path $workDir 'demo\mfg') -Recurse -Force
Copy-Item -Path (Join-Path $hogoDist '*') -Destination (Join-Path $workDir 'demo\hogo-qa') -Recurse -Force
Copy-Item -Path (Join-Path $apsDist '*') -Destination (Join-Path $workDir 'demo\mfg-agent-platform') -Recurse -Force

# 静态页面与 API 不同源：写入部署期 API 基址（覆盖源码里的空串）
$mfgConfig = Join-Path $workDir 'demo\mfg\config.js'
$mfgConfigContent = @"
/**
 * 由 deploy/cos/publish.ps1 生成，请勿手工修改。
 * 静态站（COS/CDN）访问广州单机 API 的唯一入口。
 */
window.MFG_API_BASE = "$apiBase";
"@
[System.IO.File]::WriteAllText($mfgConfig, $mfgConfigContent, (New-Object System.Text.UTF8Encoding($false)))

# ---------- 3. 上传 ----------
if ($SkipUpload) {
    Write-Host ''
    Write-Host '-SkipUpload：已跳过 COS 上传。' -ForegroundColor Yellow
    Write-Host "  发布目录：$workDir"
    Write-Host "  API 基址：$apiBase"
    Write-Host '  下一步（仅灾备/自有源回滚时才需要）：同步到广州机的静态根目录，'
    Write-Host '    rsync -az --delete deploy/cos/dist/ root@129.204.108.45:/opt/hulkai/site/'
    Write-Host '  然后刷新 CDN 目录（见 deploy/README.md 第 7 节）。'
    return
}

$coscli = Get-Command coscli -ErrorAction SilentlyContinue
if (-not $coscli) {
    throw @"
未找到 coscli。请先安装腾讯云 COS CLI（无需写入项目依赖）：
  https://cloud.tencent.com/document/product/436/63144
安装后配置一次（桶与地域取自本次发布参数）：
  coscli config add -b $Bucket -r $Region
只想要本地发布目录（灾备/自有源回滚）时，改用 -SkipUpload。
"@
}

$prefix = $BucketPrefix.Trim('/')
$cosBase = if ($prefix) { "cos://$Bucket/$prefix/" } else { "cos://$Bucket/" }

Write-Host "上传到 $cosBase" -ForegroundColor Cyan
$syncArgs = @('sync', $workDir, $cosBase, '-r')
if ($Delete) {
    $syncArgs += '--delete'
}
& coscli @syncArgs
Assert-LastExitCode 'coscli sync'

Write-Host ''
Write-Host '发布完成。' -ForegroundColor Green
Write-Host "  源站前缀：$cosBase"
Write-Host "  桶访问域名：https://$Bucket.cos.$Region.myqcloud.com/"
Write-Host "  API 基址：$apiBase"
Write-Host '  下一步：在 CDN 控制台刷新目录（见 deploy/README.md 第 9.2 节）。'
Write-Host '  提示：HTML 不缓存、带 hash 的 assets 长缓存由 CDN 缓存规则保证。'
Write-Host "  发布目录保留在：$workDir（便于比对，可自行删除）"
