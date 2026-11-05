#Requires -Version 5.1
<#
.SYNOPSIS
    M2-QA-01 求解压测执行器：POST /api/v1/solve-tasks → 轮询终态 → 采集耗时/评分/指标/堆峰值。

.DESCRIPTION
    只访问本机独立 QA 实例（默认 18089），只读查询独立库（默认 aps_qa_perf）。
    不在仓库内做任何写操作；结果 JSON 落到 D:\tools\_aps-localdata\qa-perf\results。

    指标口径：
      e2eMs            = 本地 POST 发起 → 首次轮询到终态的墙钟耗时（含轮询粒度误差）
      submitMs         = POST 请求本身耗时
      durationMs       = 引擎内部求解耗时（服务端 metrics）
      totalDurationMs  = ASSEMBLING→PERSISTING 完成的服务端总耗时
      persistApproxMs  = totalDurationMs - durationMs（装配+落库合计，非纯落库）
      persistWindowMs  = 轮询首次见 PERSISTING → 首次见终态的间隔（落库窗口近似）
      heapPeakMb       = 本次运行窗口内 GC 日志中 GC 前已用堆的最大值（JVM 堆峰值）

.PARAMETER Orders
    参与求解的订单数（取 SO-20261104-1001..N），默认 500。
.PARAMETER Label
    本次运行标签，用于结果文件名。
.PARAMETER MoveThreadCount
    0 表示不传（走引擎默认 CPU-1）。
.PARAMETER TimeoutSeconds
    0 表示不传（默认 60）。
.PARAMETER UnimprovedSecondsSpentLimit
    0 表示不传（默认 10）。
.PARAMETER Acceptors
    0 表示不传。
#>
[CmdletBinding()]
param(
    [int]$Orders = 500,
    [string]$Label = "orders-$Orders",
    [string]$BaseUrl = 'http://127.0.0.1:18089',
    [string]$Toolchain = 'D:\tools\_aps-toolchain',
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 55432,
    [string]$DbName = 'aps_qa_perf',
    [string]$DbUser = 'aps',
    [string]$DbPassword = 'aps_dev_pwd',
    [string]$ApiKey = $(if ($env:APS_DEV_API_KEY) { $env:APS_DEV_API_KEY } else { 'aps-dev-write-key-local-only' }),
    [string]$GcLog = 'D:\tools\_aps-localdata\qa-perf\gc-18089.log',
    [int]$JavaPid = 0,
    [int]$MoveThreadCount = 0,
    [int]$TimeoutSeconds = 0,
    [int]$UnimprovedSecondsSpentLimit = 0,
    [int]$PollIntervalMs = 900,
    [int]$HardLimitSeconds = 180,
    [string]$OutDir = 'D:\tools\_aps-localdata\qa-perf\results'
)

$ErrorActionPreference = 'Stop'
$tenant = '00000000-0000-0000-0000-000000000001'
$sandbox = 'e9000000-0000-0000-0000-000000000401'
$strategy = 'e9000000-0000-0000-0000-000000000001'
$psql = Join-Path $Toolchain 'pgsql\bin\psql.exe'
$env:PGPASSWORD = $DbPassword
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$orderNos = @()
for ($i = 1; $i -le $Orders; $i++) { $orderNos += ('SO-20261104-{0:d4}' -f (1000 + $i)) }

$parameters = @{}
if ($MoveThreadCount -gt 0) { $parameters['moveThreadCount'] = $MoveThreadCount }
if ($TimeoutSeconds -gt 0) { $parameters['timeoutSeconds'] = $TimeoutSeconds }
if ($UnimprovedSecondsSpentLimit -gt 0) { $parameters['unimprovedSecondsSpentLimit'] = $UnimprovedSecondsSpentLimit }

$body = @{
    taskNo     = $Label
    sandboxId  = $sandbox
    strategyId = $strategy
    engine     = 'TIMEFOLD'
    orderNos   = $orderNos
    parameters = $parameters
} | ConvertTo-Json -Depth 6 -Compress

$postHeaders = @{
    'Content-Type'    = 'application/json'
    'X-API-Key'       = $ApiKey
    'Idempotency-Key' = ('m2qa-' + [guid]::NewGuid().ToString('N'))
}
$pollHeaders = @{
    'Authorization' = ('Bearer m2qa-' + [guid]::NewGuid().ToString('N'))
}

# ---------------------------------------------------------------------------
# GC 日志基线（运行窗口增量）
# ---------------------------------------------------------------------------
$gcStartLine = 0
if (Test-Path $GcLog) { $gcStartLine = (Get-Content $GcLog -ErrorAction SilentlyContinue).Count }

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$submitStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
try {
    $post = Invoke-RestMethod -Uri "$BaseUrl/api/v1/solve-tasks" -Method Post -Headers $postHeaders -Body $body -TimeoutSec 30
}
catch {
    $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { -1 }
    throw "POST failed (status=$statusCode): $($_.Exception.Message)"
}
$submitStopwatch.Stop()
$submitMs = $submitStopwatch.ElapsedMilliseconds

$detail = $post.data
$taskId = $detail.taskId
$createdAt = $detail.createdAt
Write-Host ("[POST] taskId={0} status={1} phase={2} submitMs={3}" -f $taskId, $detail.status, $detail.phase, $submitMs)

$terminal = @('SOLVED', 'FAILED', 'CANCELLED', 'TIMEOUT')
$phaseTimeline = [ordered]@{}
$phaseTimeline[$detail.phase] = [int]$stopwatch.ElapsedMilliseconds
$pollCount = 0
$workingSetPeakMb = 0
$final = $detail

while ($true) {
    if ($stopwatch.Elapsed.TotalSeconds -gt $HardLimitSeconds) {
        throw "轮询超过硬上限 ${HardLimitSeconds}s（status=$($final.status)）"
    }
    Start-Sleep -Milliseconds $PollIntervalMs
    $pollCount++
    $final = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/solve-tasks/$taskId" -Method Get -Headers $pollHeaders -TimeoutSec 30).data
    if (-not $phaseTimeline.Contains($final.phase)) {
        $phaseTimeline[$final.phase] = [int]$stopwatch.ElapsedMilliseconds
    }
    if ($JavaPid -gt 0) {
        try {
            $ws = (Get-Process -Id $JavaPid -ErrorAction Stop).WorkingSet64 / 1MB
            if ($ws -gt $workingSetPeakMb) { $workingSetPeakMb = $ws }
        } catch { }
    }
    if ($terminal -contains $final.status) { break }
}
$stopwatch.Stop()
$e2eMs = $stopwatch.ElapsedMilliseconds

$m = $final.metrics
$durationMs = if ($m.durationMs) { [long]$m.durationMs } else { 0 }
$totalDurationMs = if ($m.totalDurationMs) { [long]$m.totalDurationMs } else { 0 }
$persisted = if ($m.persistedTaskCount) { [long]$m.persistedTaskCount } else { 0 }
$persistApproxMs = [Math]::Max(0, $totalDurationMs - $durationMs)
$persistWindowMs = $null
if ($phaseTimeline.Contains('PERSISTING') -and $terminal -contains $final.status) {
    $persistWindowMs = [int]($e2eMs - $phaseTimeline['PERSISTING'])
}

# 独立核对库内落库行数
# 中文 SQL 一律写临时 .sql 文件（UTF-8 无 BOM）再用 psql -f 执行：
# PS 5.1 把含中文的 -c 参数按 GBK 传给 psql，服务端按 UTF-8 解析会报 0xb8 编码错误。
$dbRows = -1
if (Test-Path $psql) {
    $verifySql = "select count(*) from task t join orders o on o.id = t.order_id " +
        "where t.tenant_id = '$tenant' and t.sandbox_id = '$sandbox' " +
        "and o.remark = '高负载推演数据集' and t.created_at >= timestamptz '$createdAt';"
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $verifySqlFile = Join-Path ([System.IO.Path]::GetTempPath()) ("aps-perf-verify-{0}.sql" -f ([guid]::NewGuid().ToString('N')))
    try {
        [System.IO.File]::WriteAllText($verifySqlFile, $verifySql, $utf8NoBom)
        $raw = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1 -f $verifySqlFile
        if ($LASTEXITCODE -eq 0) { $dbRows = [int](($raw -join '').Trim()) }
        else { Write-Host "[WARN] 库内落库行数核对失败（psql exit=$LASTEXITCODE），dbRows 记为 -1" -ForegroundColor Yellow }
    }
    finally {
        Remove-Item $verifySqlFile -Force -ErrorAction SilentlyContinue
    }
}

# GC 日志增量解析（堆峰值 / 停顿）
$heapPeakMb = 0
$heapPeakAfterGcMb = 0
$gcCount = 0
$gcPauseMs = 0.0
if (Test-Path $GcLog) {
    $lines = Get-Content $GcLog -ErrorAction SilentlyContinue
    if ($lines.Count -gt $gcStartLine) {
        $newLines = $lines[$gcStartLine..($lines.Count - 1)]
        foreach ($line in $newLines) {
            if ($line -match '(\d+)M->(\d+)M\((\d+)M\)') {
                $gcCount++
                $before = [int]$Matches[1]
                $after = [int]$Matches[2]
                if ($before -gt $heapPeakMb) { $heapPeakMb = $before }
                if ($after -gt $heapPeakAfterGcMb) { $heapPeakAfterGcMb = $after }
                if ($line -match '([\d.]+)ms\s*$') { $gcPauseMs += [double]$Matches[1] }
            }
        }
    }
}
$heapPeakSource = 'GC log pre-pause used'
if ($heapPeakMb -eq 0 -and $JavaPid -gt 0) {
    $heapInfo = & (Join-Path $Toolchain 'jdk-21.0.13+9\bin\jcmd.exe') $JavaPid GC.heap_info 2>$null
    $text = ($heapInfo -join ' ')
    if ($text -match 'used\s+(\d+)K') { $heapPeakMb = [Math]::Round([int]$Matches[1] / 1024) }
    $heapPeakSource = 'jcmd GC.heap_info (no GC event in window)'
}

$result = [ordered]@{
    label                  = $Label
    orders                 = $Orders
    taskId                 = $taskId
    status                 = $final.status
    phase                  = $final.phase
    bestScore              = $final.bestScore
    scoreHard              = $final.scoreHard
    scoreSoft              = $final.scoreSoft
    elapsedMsServer        = $final.elapsedMs
    e2eMs                  = $e2eMs
    submitMs               = $submitMs
    pollIntervalMs         = $PollIntervalMs
    pollCount              = $pollCount
    phaseTimelineMs        = $phaseTimeline
    engineDurationMs       = $durationMs
    totalDurationMs        = $totalDurationMs
    persistApproxMs        = $persistApproxMs
    persistWindowMs        = $persistWindowMs
    taskCountMetric        = $m.taskCount
    persistedTaskCount     = $persisted
    dbPersistedRows        = $dbRows
    justificationCount     = $m.justificationCount
    stepCount              = $m.stepCount
    terminationReason      = $m.terminationReason
    unimprovedStepCount    = $m.unimprovedStepCount
    engineFallback         = $m.engineFallback
    engineFallbackReason   = $m.engineFallbackReason
    fallbackMode           = $m.fallbackMode
    calendarFallback       = $m.calendarFallback
    dependencyFallback     = $m.dependencyFallback
    setupMatrixFallback    = $m.setupMatrixFallback
    errorCode              = $final.errorCode
    errorMessage           = $final.errorMessage
    returnedTasks          = @($final.tasks).Count
    heapPeakMb             = $heapPeakMb
    heapPeakAfterGcMb      = $heapPeakAfterGcMb
    heapPeakSource         = $heapPeakSource
    gcCount                = $gcCount
    gcPauseMs              = [Math]::Round($gcPauseMs, 1)
    workingSetPeakMb       = [Math]::Round($workingSetPeakMb)
    moveThreadCount        = $(if ($MoveThreadCount -gt 0) { $MoveThreadCount } else { 'default(CPU-1)' })
    timeoutSeconds         = $(if ($TimeoutSeconds -gt 0) { $TimeoutSeconds } else { 'default(60)' })
    unimprovedSeconds      = $(if ($UnimprovedSecondsSpentLimit -gt 0) { $UnimprovedSecondsSpentLimit } else { 'default(10)' })
    parametersSent         = $parameters
    finishedAt             = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    baseUrl                = $BaseUrl
    dbName                 = $DbName
}

$outFile = Join-Path $OutDir ("{0}.json" -f $Label)
$result | ConvertTo-Json -Depth 6 | Set-Content -Path $outFile -Encoding UTF8

Write-Host ''
Write-Host '==== M2-QA-01 PERF RUN ===='
Write-Host ("orders={0}  status={1}  bestScore={2}" -f $Orders, $final.status, $final.bestScore)
Write-Host ("e2eMs={0}  engineDurationMs={1}  totalDurationMs={2}  persistApproxMs={3}  persistWindowMs={4}" -f `
        $e2eMs, $durationMs, $totalDurationMs, $persistApproxMs, $persistWindowMs)
Write-Host ("persistedTaskCount={0}  dbRows={1}  justifications={2}" -f $persisted, $dbRows, $m.justificationCount)
Write-Host ("steps={0}  termination={1}  unimprovedSteps={2}" -f $m.stepCount, $m.terminationReason, $m.unimprovedStepCount)
Write-Host ("heapPeakMb={0} (afterGc={1}, source={2})  gcCount={3}  gcPauseMs={4}  workingSetPeakMb={5}" -f `
        $heapPeakMb, $heapPeakAfterGcMb, $heapPeakSource, $gcCount, ([Math]::Round($gcPauseMs, 1)), ([Math]::Round($workingSetPeakMb)))
Write-Host ("engineFallback={0}  reason={1}  timeout={2}  errors={3}" -f $m.engineFallback, $m.engineFallbackReason, ($final.status -eq 'TIMEOUT'), $final.errorMessage)
Write-Host ("phases: {0}" -f (($phaseTimeline.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)ms" }) -join ' -> '))
Write-Host ("result json: {0}" -f $outFile)
