#Requires -Version 5.1
<#
.SYNOPSIS
    无 Docker 本地验证一键脚本：便携 PostgreSQL + Flyway 实库迁移 + 后端/前端冒烟 + BD-01 真库回归。

.DESCRIPTION
    依赖 scripts\local-db-up.ps1（启停便携 PostgreSQL）与 aps-admin 打包产物。
    默认流程：
      1) 启动本地数据库（127.0.0.1:55432）
      2) 以 dev profile 启动后端 jar（启动时由 Flyway 执行 db/migration），校验 flyway_schema_history
      3) 冒烟 /api/v1/health、/v3/api-docs/{materials,solve,gantt} 与已落地列表端点
      4) 运行 BD-01 真库回归 harness（src/test 下的 test-scope 文件，13 项断言）
      5) 前端 npm ci + npm run build + vite dev 首页可达性（可用 -SkipFrontend 跳过）
    收尾默认停止后端（-KeepRunning 保留）；数据库默认保留运行，-StopDb 一并停止。

    注意：aps-frontend/vite.config.ts 代理固定 http://localhost:8080，前端冒烟时后端端口会切到 8080（不改前端源码）。

.EXAMPLE
    .\scripts\local-verify.ps1
.EXAMPLE
    .\scripts\local-verify.ps1 -SkipFrontend
.EXAMPLE
    .\scripts\local-verify.ps1 -SkipFrontend -KeepRunning -SkipRegression
#>
[CmdletBinding()]
param(
    [switch]$SkipFrontend,
    [switch]$SkipBackend,
    [switch]$SkipRegression,
    [switch]$KeepRunning,
    [switch]$StopDb,
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 5173,
    [int]$DbPort = 55432,
    [string]$DbName = 'aps_local',
    [string]$DbUser = 'aps',
    [string]$DbPassword = 'aps_dev_pwd'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$toolchain = if ($env:APS_TOOLCHAIN) { $env:APS_TOOLCHAIN } else { 'D:\tools\_aps-toolchain' }
$javaHome = Join-Path $toolchain 'jdk-21.0.13+9'
$mavenHome = Join-Path $toolchain 'apache-maven-3.9.16'
$nodeHome = Join-Path $toolchain 'node-v26.13.0-win-x64'
$pgHome = Join-Path $toolchain 'pgsql'
$workDir = if ($env:APS_LOCALDATA) { $env:APS_LOCALDATA } else { 'D:\tools\_aps-localdata' }
$jar = Join-Path $root 'aps-admin\target\aps-admin-0.1.0-SNAPSHOT.jar'
$backendLog = Join-Path $workDir 'verify-backend.log'
$frontendLog = Join-Path $workDir 'verify-frontend.log'
$harnessLog = Join-Path $workDir 'verify-harness.log'
$bootDir = Join-Path $workDir 'boot'
$harnessClasses = Join-Path $bootDir 'harness-classes'
$javaExe = Join-Path $javaHome 'bin\java.exe'
$javacExe = Join-Path $javaHome 'bin\javac.exe'
$jarTool = Join-Path $javaHome 'bin\jar.exe'

$script:checks = @()

function Write-Step { param([string]$Message) Write-Host "`n[..] $Message" -ForegroundColor Cyan }
function Write-Ok { param([string]$Message) Write-Host "[PASS] $Message" -ForegroundColor Green }
function Write-Bad { param([string]$Message) Write-Host "[FAIL] $Message" -ForegroundColor Red }
function Write-Note { param([string]$Message) Write-Host "       $Message" -ForegroundColor DarkGray }

function Add-Check {
    param([string]$Name, [bool]$Ok, [string]$Evidence)
    $script:checks += [pscustomobject]@{ Name = $Name; Ok = $Ok; Evidence = $Evidence }
    if ($Ok) { Write-Ok "$Name — $Evidence" } else { Write-Bad "$Name — $Evidence" }
}

function Wait-Http {
    param([string]$Url, [int]$Seconds = 60)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($r.StatusCode -eq 200) { return $r }
        }
        catch { Start-Sleep -Milliseconds 1000 }
    }
    return $null
}

function Invoke-HttpProbe {
    param([string]$Url, [int[]]$Expected)
    try {
        $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 15
        $trace = $r.Headers['X-Trace-Id']
        $body = if ($r.Content.Length -gt 120) { $r.Content.Substring(0, 120) + '...' } else { $r.Content }
        return [pscustomobject]@{ Status = $r.StatusCode; Trace = $trace; Body = $body }
    }
    catch {
        $resp = $_.Exception.Response
        if ($resp) {
            return [pscustomobject]@{ Status = [int]$resp.StatusCode; Trace = $resp.Headers['X-Trace-Id']; Body = '<error>' }
        }
        return [pscustomobject]@{ Status = 0; Trace = ''; Body = $_.Exception.Message }
    }
}

# ---------------------------------------------------------------- 1. 数据库
Write-Step "启动便携 PostgreSQL（127.0.0.1:$DbPort）"
& (Join-Path $PSScriptRoot 'local-db-up.ps1') -Port $DbPort -User $DbUser -Password $DbPassword -Databases @($DbName, 'aps_local_2')
if ($LASTEXITCODE -ne 0) { throw '本地数据库启动失败' }

# ---------------------------------------------------------------- 2. 后端 + Flyway
$backendProcess = $null
if (-not $SkipBackend) {
    if (-not (Test-Path $jar)) {
        throw "未找到后端 jar：$jar（请先执行 mvn -DskipTests package）"
    }
    $libEntries = (& $jarTool tf $jar | Where-Object { $_ -like 'BOOT-INF/lib/*' } | Measure-Object).Count
    if ($libEntries -eq 0) {
        throw "jar 不是可执行的 fat jar（缺少 BOOT-INF/lib，可能被并发构建覆盖）：$jar；请重新 mvn -DskipTests package"
    }

    if (-not $SkipFrontend -and $BackendPort -ne 8080) {
        Write-Note "前端 vite 代理固定 8080，后端端口由 $BackendPort 切换为 8080（不改前端源码）"
        $BackendPort = 8080
    }

    Write-Step "启动后端（dev profile, port=$BackendPort, db=$DbName）"
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:DB_HOST = '127.0.0.1'
    $env:DB_PORT = "$DbPort"
    $env:DB_NAME = $DbName
    $env:DB_USER = $DbUser
    $env:DB_PASSWORD = $DbPassword
    # dev API Key 统一由本脚本以启动参数显式开启（application-dev.yml 保持默认关闭）。
    # 依赖 X-API-Key 的脚本（verify-se5-justification/run-m2-perf/m2-qa-02-p1-regress）默认使用该密钥。
    $devApiKey = if ($env:APS_DEV_API_KEY) { $env:APS_DEV_API_KEY } else { 'aps-dev-write-key-local-only' }
    $backendProcess = Start-Process -FilePath $javaExe `
        -ArgumentList '-Daps.security.mode=dev', '-Daps.security.dev.api-key-enabled=true', `
            "-Daps.security.api-keys.write=$devApiKey", '-jar', $jar, "--server.port=$BackendPort" `
        -RedirectStandardOutput $backendLog -RedirectStandardError "$backendLog.err" `
        -NoNewWindow -PassThru

    $health = Wait-Http -Url "http://127.0.0.1:$BackendPort/api/v1/health" -Seconds 90
    Add-Check -Name '后端启动 + /api/v1/health' -Ok ($null -ne $health) `
        -Evidence $(if ($health) { "status=$($health.StatusCode) traceId=$($health.Headers['X-Trace-Id'])" } else { "启动失败，日志见 $backendLog" })
    if (-not $health) { throw '后端未能启动（可能受并发编辑阻塞或端口占用），详见日志' }

    Write-Step '校验 Flyway 迁移历史与数据库对象统计'
    $env:PGPASSWORD = $DbPassword
    $psql = Join-Path $pgHome 'bin\psql.exe'
    $migration = (& $psql -h 127.0.0.1 -p $DbPort -U $DbUser -d $DbName -tAc "SELECT version || '|' || description || '|' || success FROM flyway_schema_history ORDER BY installed_rank").Trim()
    $stats = (& $psql -h 127.0.0.1 -p $DbPort -U $DbUser -d $DbName -tAc "SELECT 'tables=' || count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'; SELECT 'indexes=' || count(*) FROM pg_indexes WHERE schemaname='public'; SELECT 'fks=' || count(*) FROM information_schema.table_constraints WHERE constraint_type='FOREIGN KEY' AND constraint_schema='public'; SELECT extname || '=' || extversion FROM pg_extension WHERE extname IN ('pgcrypto','pg_trgm') ORDER BY 1").Trim() -split "`r?`n"
    Add-Check -Name 'Flyway 迁移历史' -Ok ($migration -match '^1\|init\|true$') -Evidence ($migration -replace "`r?`n", '; ')
    Add-Check -Name 'V1 数据库对象' -Ok ($stats.Count -ge 5) -Evidence ($stats -join '; ')

    Write-Step '端点冒烟'
    foreach ($doc in @('materials', 'solve', 'gantt')) {
        $probe = Invoke-HttpProbe -Url "http://127.0.0.1:$BackendPort/v3/api-docs/$doc" -Expected @(200)
        Add-Check -Name "GET /v3/api-docs/$doc" -Ok ($probe.Status -eq 200) -Evidence "status=$($probe.Status) traceId=$($probe.Trace)"
    }
    $orders = Invoke-HttpProbe -Url "http://127.0.0.1:$BackendPort/api/v1/orders" -Expected @(200)
    Add-Check -Name 'GET /api/v1/orders' -Ok ($orders.Status -eq 200) -Evidence "status=$($orders.Status) traceId=$($orders.Trace) body=$($orders.Body)"
    $materials = Invoke-HttpProbe -Url "http://127.0.0.1:$BackendPort/api/v1/materials" -Expected @(200, 501)
    Add-Check -Name 'GET /api/v1/materials（BD-02 未落地时为契约占位）' -Ok ($materials.Status -in @(200, 501)) -Evidence "status=$($materials.Status) traceId=$($materials.Trace)"
    $templates = Invoke-HttpProbe -Url "http://127.0.0.1:$BackendPort/api/v1/operation-templates" -Expected @(200, 404)
    Add-Check -Name 'GET /api/v1/operation-templates（BD-06 未落地时为 404）' -Ok ($templates.Status -in @(200, 404)) -Evidence "status=$($templates.Status) traceId=$($templates.Trace)"
}

# ---------------------------------------------------------------- 3. BD-01 回归
if (-not $SkipRegression) {
    Write-Step '运行 BD-01 真库回归（test-scope harness）'
    if (-not (Test-Path (Join-Path $bootDir 'BOOT-INF\lib'))) {
        New-Item -ItemType Directory -Force -Path $bootDir | Out-Null
    }
    Push-Location $bootDir
    & $jarTool xf $jar BOOT-INF
    Pop-Location
    New-Item -ItemType Directory -Force -Path $harnessClasses | Out-Null
    $sources = Get-ChildItem -Recurse (Join-Path $root 'aps-admin\src\test\java') -Filter *.java | ForEach-Object { $_.FullName }
    $cp = "$bootDir\BOOT-INF\classes;$bootDir\BOOT-INF\lib\*"
    & $javacExe -encoding UTF-8 -cp $cp -d $harnessClasses $sources
    if ($LASTEXITCODE -ne 0) { throw '回归 harness 编译失败' }

    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:DB_HOST = '127.0.0.1'
    $env:DB_PORT = "$DbPort"
    $env:DB_NAME = $DbName
    $env:DB_USER = $DbUser
    $env:DB_PASSWORD = $DbPassword
    $runCp = "$harnessClasses;$bootDir\BOOT-INF\classes;$bootDir\BOOT-INF\lib\*"
    & $javaExe -cp $runCp com.hengce.aps.verify.LocalDbRegressionHarness 2>&1 | Tee-Object -FilePath $harnessLog | Out-Host
    $code = $LASTEXITCODE
    Add-Check -Name 'BD-01 真库回归（租户/乐观锁/逻辑删除/JSONB/审计）' -Ok ($code -eq 0) -Evidence "exit=$code，明细见 $harnessLog"
}

# ---------------------------------------------------------------- 4. 前端
if (-not $SkipFrontend) {
    Write-Step '前端 npm ci + build + dev 首页可达性'
    $npm = Join-Path $nodeHome 'npm.cmd'
    $frontend = Join-Path $root 'aps-frontend'
    $env:HTTP_PROXY = 'http://127.0.0.1:7890'
    $env:HTTPS_PROXY = 'http://127.0.0.1:7890'

    Push-Location $frontend
    try {
        & $npm ci 2>&1 | Select-Object -Last 10 | Out-Host
        $ciOk = ($LASTEXITCODE -eq 0)
        Add-Check -Name 'npm ci' -Ok $ciOk -Evidence "exit=$LASTEXITCODE"

        & $npm run build 2>&1 | Select-Object -Last 12 | Out-Host
        $buildOk = ($LASTEXITCODE -eq 0)
        Add-Check -Name 'npm run build' -Ok $buildOk -Evidence "exit=$LASTEXITCODE"
    }
    finally {
        Pop-Location
    }

    if ($ciOk -and $buildOk) {
        $frontendProcess = Start-Process -FilePath $npm -ArgumentList 'run', 'dev', '--', '--port', "$FrontendPort", '--strictPort' `
            -WorkingDirectory $frontend -RedirectStandardOutput $frontendLog -RedirectStandardError "$frontendLog.err" -NoNewWindow -PassThru
        $page = Wait-Http -Url "http://127.0.0.1:$FrontendPort/" -Seconds 60
        Add-Check -Name 'vite dev 首页' -Ok ($null -ne $page) -Evidence $(if ($page) { "status=$($page.StatusCode), 控制台报错需人工/浏览器自动化确认" } else { "未启动，日志见 $frontendLog" })
        if ($frontendProcess -and -not $frontendProcess.HasExited) { Stop-Process -Id $frontendProcess.Id -Force }
    }
}

# ---------------------------------------------------------------- 汇总
if ($backendProcess -and -not $KeepRunning -and -not $backendProcess.HasExited) {
    Write-Step '停止后端进程'
    Stop-Process -Id $backendProcess.Id -Force
}
if ($StopDb) {
    Write-Step '停止本地数据库'
    & (Join-Path $PSScriptRoot 'local-db-down.ps1')
}

Write-Host ''
Write-Host '==== 验证汇总 ====' -ForegroundColor Cyan
$script:checks | ForEach-Object { "{0,-6} {1} — {2}" -f $(if ($_.Ok) { 'PASS' } else { 'FAIL' }), $_.Name, $_.Evidence }
$failed = ($script:checks | Where-Object { -not $_.Ok }).Count
Write-Host "`n合计 $($script:checks.Count) 项，失败 $failed 项" -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'Red' })
Write-Host "日志目录：$workDir（后端 verify-backend.log / 回归 verify-harness.log / 前端 verify-frontend.log）"
if ($failed -gt 0) { exit 2 }
