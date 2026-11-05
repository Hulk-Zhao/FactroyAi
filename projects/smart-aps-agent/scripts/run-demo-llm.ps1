#Requires -Version 5.1
<#
.SYNOPSIS
    LLM 演示实例启动脚本：在独立端口起一个只用于现场演示的实例（绝不占用 18085）。

.DESCRIPTION
    只读环境变量，不写任何配置文件。环境变量契约（优先级：环境变量 > application.yml > 缺省）：

      APS_AGENT_LLM_PROVIDER        deepseek | qwen | ollama | none（缺省 none，即 AI 关闭）
      APS_AGENT_LLM_BASE_URL        OpenAI 兼容 base（DeepSeek/Qwen）；Ollama 为原生 base，如 http://127.0.0.1:11434
      APS_AGENT_LLM_API_KEY         密钥；Ollama 可留空
      APS_AGENT_LLM_MODEL           如 deepseek-chat / qwen3.5:9b
      APS_AGENT_LLM_TIMEOUT_SECONDS 秒；缺省 10

    -Provider 只覆盖本次启动进程的 APS_AGENT_LLM_PROVIDER，不修改调用者的环境变量。

    安全约定：脚本只打印 provider / model / 是否配置了 key（是或否），
    绝不打印 key 值本身，也不把 key 写入日志文件。

    前置条件（本脚本不构建、不打包）：
      - 各模块 target/classes 已编译存在（mvn -q -DskipTests compile）
      - aps-admin/target/aps-admin-*.jar 已存在：仅用于解压依赖 jar 到工具链目录
      - PostgreSQL 可连接；临时库缺省 aps_llm_demo，不存在时以 aps_local 为模板复制
        （aps_local / aps_demo 本身只读，脚本不会写入）

.PARAMETER Provider
    deepseek | qwen | ollama | none；覆盖 APS_AGENT_LLM_PROVIDER。

.PARAMETER Port
    演示实例端口，缺省 18099；禁止 18085。

.PARAMETER DbName
    演示库名，缺省 aps_llm_demo（不存在则从 aps_local 复制）。

.PARAMETER Toolchain
    工具链目录，缺省取 APS_TOOLCHAIN 或 D:\tools\_aps-toolchain。

.EXAMPLE
    # DeepSeek：先在当前会话填好密钥，再启动
    $env:APS_AGENT_LLM_BASE_URL = 'https://api.deepseek.com/v1'
    $env:APS_AGENT_LLM_API_KEY  = '<本地填入>'
    $env:APS_AGENT_LLM_MODEL    = 'deepseek-chat'
    .\scripts\run-demo-llm.ps1 -Provider deepseek

.EXAMPLE
    # 本机 Ollama（无需 key）
    .\scripts\run-demo-llm.ps1 -Provider ollama

.EXAMPLE
    # AI 关闭（演示“未启用”口径）
    .\scripts\run-demo-llm.ps1 -Provider none
#>
[CmdletBinding()]
param(
    [ValidateSet('deepseek', 'qwen', 'ollama', 'none')]
    [string]$Provider,
    [int]$Port = 18099,
    [string]$DbName = 'aps_llm_demo',
    [string]$Toolchain = $(if ($env:APS_TOOLCHAIN) { $env:APS_TOOLCHAIN } else { 'D:\tools\_aps-toolchain' }),
    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 55432 }),
    [string]$DbUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'aps' }),
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'aps_dev_pwd' }),
    [string]$TemplateDb = 'aps_local'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$psql = Join-Path $Toolchain 'pgsql\bin\psql.exe'
$javaExe = Join-Path $Toolchain 'jdk-21.0.13+9\bin\java.exe'
$logDir = Join-Path $repoRoot 'target\demo-llm'
$stdoutLog = Join-Path $logDir 'app.out.log'
$stderrLog = Join-Path $logDir 'app.err.log'
$libDir = Join-Path $Toolchain 'aps-llm-demo-lib'

function Fail {
    param([string]$Message)
    Write-Host "FAIL  $Message" -ForegroundColor Red
    exit 1
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { Fail $Message }
}

function Invoke-Psql {
    param([string]$Sql, [string]$Database = $DbName)
    $out = & $psql -h $DbHost -p $DbPort -U $DbUser -d $Database -A -t -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "psql 失败（exit $LASTEXITCODE）：$($out -join ' ')" }
    return (@($out) | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ -ne '' })
}

# 18085 是既有演示实例专用端口，演示脚本只允许独立端口。
if ($Port -eq 18085) { Fail '禁止使用 18085：请换一个独立端口，例如 -Port 18099' }

$effectiveProvider = if ($Provider) { $Provider } else { $env:APS_AGENT_LLM_PROVIDER }
if ([string]::IsNullOrWhiteSpace($effectiveProvider)) { $effectiveProvider = 'none' }
$effectiveProvider = $effectiveProvider.Trim().ToLowerInvariant()

$baseUrl = $env:APS_AGENT_LLM_BASE_URL
$model = $env:APS_AGENT_LLM_MODEL
$hasKey = -not [string]::IsNullOrWhiteSpace($env:APS_AGENT_LLM_API_KEY)
$timeoutSeconds = if ($env:APS_AGENT_LLM_TIMEOUT_SECONDS) { $env:APS_AGENT_LLM_TIMEOUT_SECONDS } else { '10（缺省）' }

if ($effectiveProvider -ne 'none') {
    if ([string]::IsNullOrWhiteSpace($baseUrl)) {
        Write-Host 'WARN  未设置 APS_AGENT_LLM_BASE_URL：explain 将降级为 EVIDENCE（NO_PROVIDER_CONFIGURED）' -ForegroundColor Yellow
    }
    if ([string]::IsNullOrWhiteSpace($model)) {
        Write-Host 'WARN  未设置 APS_AGENT_LLM_MODEL：explain 将降级为 EVIDENCE（NO_PROVIDER_CONFIGURED）' -ForegroundColor Yellow
    }
    if ($effectiveProvider -ne 'ollama' -and -not $hasKey) {
        Write-Host 'WARN  未设置 APS_AGENT_LLM_API_KEY：explain 将降级为 EVIDENCE（NO_PROVIDER_CONFIGURED）' -ForegroundColor Yellow
    }
}

Assert-True (Test-Path $psql) "找不到 psql：$psql"
Assert-True (Test-Path $javaExe) "找不到 java：$javaExe"

$env:PGPASSWORD = $DbPassword
$dbListening = Test-NetConnection -ComputerName $DbHost -Port $DbPort -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $dbListening) { Fail "PostgreSQL $DbHost`:$DbPort 不可达，请先执行 scripts/local-db-up.ps1" }

$exists = Invoke-Psql -Sql "SELECT 1 FROM pg_database WHERE datname = '$DbName'" -Database 'postgres'
if (-not $exists) {
    Write-Host "INFO  演示库 $DbName 不存在，以只读模板 $TemplateDb 复制一份" -ForegroundColor Cyan
    Invoke-Psql -Sql "CREATE DATABASE `"$DbName`" TEMPLATE `"$TemplateDb`"" -Database 'postgres' | Out-Null
}

$owner = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -ne $owner) { Fail "端口 $Port 已被 PID $($owner.OwningProcess) 占用，请先停止或换端口" }

# 类路径：各模块 target/classes（本次改动即时生效）+ 既有 fat jar 解压出的依赖 jar。
# 不执行 package / repackage；fat jar 只作为依赖来源。
$classDirs = @(
    'aps-common', 'aps-agent', 'aps-domain', 'aps-infrastructure',
    'aps-solver\solver-api', 'aps-solver\solver-dsl', 'aps-solver\solver-timefold', 'aps-solver\solver-ortools',
    'aps-service', 'aps-web', 'aps-admin'
) | ForEach-Object { Join-Path $repoRoot "$_\target\classes" }
foreach ($dir in $classDirs) {
    Assert-True (Test-Path $dir) "缺少已编译目录 $dir（请先执行 mvn -q -DskipTests compile）"
}

$fatJar = Get-ChildItem (Join-Path $repoRoot 'aps-admin\target') -Filter 'aps-admin-*-SNAPSHOT.jar' |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($null -eq $fatJar) { Fail '找不到 aps-admin fat jar（仅用于提供依赖 jar），请先在 CI 或本地构建一次' }

if (-not (Test-Path $libDir) -or (Get-ChildItem $libDir -Filter '*.jar').Count -lt 50) {
    Write-Host 'INFO  首次运行：从既有 fat jar 解压依赖到工具链目录（不重新打包）' -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $libDir | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($fatJar.FullName)
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -like 'BOOT-INF/lib/*.jar') {
                $target = Join-Path $libDir ([System.IO.Path]::GetFileName($entry.FullName))
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
        }
    }
    finally { $zip.Dispose() }
}

$depJars = Get-ChildItem $libDir -Filter '*.jar' |
    Where-Object { $_.Name -notlike 'aps-*' } |
    ForEach-Object { $_.FullName }
$classpath = ($classDirs + $depJars) -join ';'

New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Remove-Item $stdoutLog, $stderrLog -ErrorAction SilentlyContinue

$env:SERVER_PORT = "$Port"
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:DB_HOST = $DbHost
$env:DB_PORT = "$DbPort"
$env:DB_NAME = $DbName
$env:DB_USER = $DbUser
$env:DB_PASSWORD = $DbPassword
$env:APS_SECURITY_MODE = if ($env:APS_SECURITY_MODE) { $env:APS_SECURITY_MODE } else { 'dev' }
if (-not $env:APS_SECURITY_JWT_SECRET) {
    # 仅本地演示用的占位值，不是生产密钥
    $env:APS_SECURITY_JWT_SECRET = 'demo-local-llm-jwt-secret-32bytes!!'
}
$env:APS_SECURITY_DEV_API_KEY_ENABLED = 'true'
$env:APS_SECURITY_DEV_USER_ID_HEADER_ENABLED = 'true'
if (-not $env:APS_SECURITY_API_KEYS_WRITE) { $env:APS_SECURITY_API_KEYS_WRITE = 'demo-local-key' }
if (-not $env:APS_SECURITY_API_KEYS_READ) { $env:APS_SECURITY_API_KEYS_READ = 'demo-local-key' }
$env:APS_AGENT_LLM_PROVIDER = $effectiveProvider
$env:CLASSPATH = $classpath

Write-Host ''
Write-Host "[STEP] 启动演示实例 port=$Port db=$DbName" -ForegroundColor Cyan
$process = Start-Process -FilePath $javaExe `
    -ArgumentList 'com.hengce.aps.admin.ApsAdminApplication' `
    -WorkingDirectory $repoRoot -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog `
    -PassThru

$baseUrlHttp = "http://127.0.0.1:$Port"
$deadline = (Get-Date).AddSeconds(120)
$up = $false
while ((Get-Date) -lt $deadline) {
    if ($process.HasExited) { break }
    try {
        $resp = Invoke-WebRequest -Uri "$baseUrlHttp/api/v1/health" -UseBasicParsing -TimeoutSec 3
        if ([int]$resp.StatusCode -eq 200) { $up = $true; break }
    }
    catch { }
    Start-Sleep -Seconds 2
}
if (-not $up) {
    Write-Host "FAIL  实例未在 120s 内就绪，PID=$($process.Id)，日志：$stdoutLog / $stderrLog" -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host '演示实例已就绪（后台运行）' -ForegroundColor Green
Write-Host ("  PID            : {0}" -f $process.Id)
Write-Host ("  端口           : {0}" -f $Port)
Write-Host ("  库             : {0}" -f $DbName)
Write-Host ("  provider       : {0}" -f $effectiveProvider)
Write-Host ("  model          : {0}" -f $(if ($model) { $model } else { '（未设置）' }))
Write-Host ("  base-url       : {0}" -f $(if ($baseUrl) { $baseUrl } else { '（未设置）' }))
Write-Host ("  API key 已配置 : {0}" -f $(if ($hasKey) { '是' } else { '否' }))
Write-Host ("  超时(秒)       : {0}" -f $timeoutSeconds)
Write-Host ("  日志           : {0}" -f $stdoutLog)
Write-Host ''
Write-Host '演示前自检：GET /api/v1/agent/explain/46' -ForegroundColor Cyan
$selfCheckTaskId = if ($env:APS_DEMO_SELF_CHECK_TASK_ID) { $env:APS_DEMO_SELF_CHECK_TASK_ID } else { '46' }
try {
    $headers = @{ 'X-API-Key' = $env:APS_SECURITY_API_KEYS_READ }
    $resp = Invoke-WebRequest -Uri "$baseUrlHttp/api/v1/agent/explain/$selfCheckTaskId" -Headers $headers `
        -UseBasicParsing -TimeoutSec 90
    $body = if ($resp.RawContentStream) {
        [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    } else { $resp.Content }
    $json = $body | ConvertFrom-Json
    $source = $json.data.source
    $reason = $json.data.llm.fallbackReason
    $latency = $json.data.llm.latencyMillis
    Write-Host ("  HTTP {0}  source={1}  fallbackReason={2}  latencyMillis={3}  traceId={4}" -f `
        $resp.StatusCode, $source, $reason, $latency, $json.traceId)
    $expected = if ($effectiveProvider -eq 'none') { 'EVIDENCE' } else { 'LLM' }
    if ($source -eq $expected) {
        Write-Host ("  PASS  自检符合预期（provider={0} → source={1}）" -f $effectiveProvider, $expected) -ForegroundColor Green
    }
    else {
        Write-Host ("  NOTE  当前 source={0}，按 provider={1} 预期为 {2}（配置不全或上游不可用时降级为 EVIDENCE 属正常）" -f `
            $source, $effectiveProvider, $expected) -ForegroundColor Yellow
    }
}
catch {
    Write-Host "  NOTE  自检请求失败：$($_.Exception.Message)（可直接看日志排查）" -ForegroundColor Yellow
}
Write-Host ''
Write-Host '手动自检命令（dev API Key 取自 APS_SECURITY_API_KEYS_READ，未配置时脚本缺省用 demo-local-key）：'
Write-Host ('  $h=@{''X-API-Key''=$env:APS_SECURITY_API_KEYS_READ}; Invoke-WebRequest -Uri "' `
    + $baseUrlHttp + '/api/v1/agent/explain/46" -Headers $h -UseBasicParsing')
Write-Host '  期望：AI 开启时 "source":"LLM"；AI 关闭时 "source":"EVIDENCE" 且 fallbackReason 有值。'
Write-Host ''
Write-Host ("停止：Stop-Process -Id {0} -Force" -f $process.Id)
