#Requires -Version 5.1
<#
.SYNOPSIS
    M2-QA-01 复跑矩阵：同一数据集 + 同一批次执行 6 个固定用例，输出可比对的汇总。

.DESCRIPTION
    本脚本只编排 scripts\run-m2-perf.ps1，不修改数据集、不改代码、不做 git 操作。
    默认按顺序执行 6 个用例（与首轮报告一致）：
      01-50-default   50 订单 / 默认 60s
      02-50-t180      50 订单 / 180s
      03-50-t300      50 订单 / 300s
      04-50-threads7  50 订单 / 默认 60s + moveThreadCount=7
      05-500-default  500 订单 / 默认 60s
      06-500-t300     500 订单 / 300s
    每个用例单独写结果 JSON，批次级写 matrix-<BatchLabel>.json / .csv，
    并记录数据集指纹（订单数/订单内容 MD5/资源数/节点数/日历数）与脚本哈希，
    保证复跑是“同样输入前后对比”。

    前置：目标库已有 500 订单压测数据（scripts\seed-m2-perf.ps1 生成）；
          目标实例已启动（默认 18089）。实例未启动时脚本会明确报错，不会重装/重建。

.PARAMETER DryRun
    只打印将执行的命令与数据集指纹，不调用接口、不运行求解。
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:18089',
    [int]$JavaPid = 0,
    [string]$Toolchain = 'D:\tools\_aps-toolchain',
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 55432,
    [string]$DbName = 'aps_qa_perf',
    [string]$DbUser = 'aps',
    [string]$DbPassword = 'aps_dev_pwd',
    [string]$GcLog = 'D:\tools\_aps-localdata\qa-perf\gc-18089.log',
    [string]$OutDir = 'D:\tools\_aps-localdata\qa-perf\results',
    [string]$BatchLabel = (Get-Date -Format 'yyyyMMdd-HHmmss'),
    [int]$BetweenRunsSeconds = 15,
    [switch]$Reseed,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $PSScriptRoot 'run-m2-perf.ps1'
$seeder = Join-Path $PSScriptRoot 'seed-m2-perf.ps1'
$psql = Join-Path $Toolchain 'pgsql\bin\psql.exe'
$env:PGPASSWORD = $DbPassword
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if ($DbName -like 'aps_local*') { throw "禁止在 $DbName 上做压测（请使用独立库 aps_qa_perf）" }
if (-not (Test-Path $runner)) { throw "runner not found: $runner" }
if (-not (Test-Path $psql)) { throw "psql not found: $psql" }

# 固定用例（与首轮报告 #1~#6 对齐）
$cases = @(
    [pscustomobject]@{ Name = '01-50-default';  Orders = 50;  Timeout = 0;   Unimproved = 0;  Threads = 0 },
    [pscustomobject]@{ Name = '02-50-t180';     Orders = 50;  Timeout = 180; Unimproved = 30; Threads = 0 },
    [pscustomobject]@{ Name = '03-50-t300';     Orders = 50;  Timeout = 300; Unimproved = 60; Threads = 0 },
    [pscustomobject]@{ Name = '04-50-threads7'; Orders = 50;  Timeout = 0;   Unimproved = 0;  Threads = 7 },
    [pscustomobject]@{ Name = '05-500-default'; Orders = 500; Timeout = 0;   Unimproved = 0;  Threads = 0 },
    [pscustomobject]@{ Name = '06-500-t300';     Orders = 500; Timeout = 300; Unimproved = 60; Threads = 0 }
)

function Get-DatasetFingerprint {
    $raw = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A -F '|' -c @"
SELECT
  (SELECT count(*) FROM orders WHERE remark = '高负载推演数据集'),
  (SELECT coalesce(md5(string_agg(order_no || '|' || qty || '|' || due_date || '|' || priority, ',' ORDER BY order_no)), '-')
     FROM orders WHERE remark = '高负载推演数据集'),
  (SELECT count(*) FROM resource WHERE
     (code >= 'RES-CNC-11' AND code <= 'RES-CNC-30') OR
     (code >= 'RES-HEAT-11' AND code <= 'RES-HEAT-15') OR
     (code >= 'RES-ASSY-11' AND code <= 'RES-ASSY-25') OR
     (code >= 'RES-QC-11' AND code <= 'RES-QC-15') OR
     (code >= 'RES-OP-11' AND code <= 'RES-OP-15')),
  (SELECT count(*) FROM routing_node WHERE routing_id IN (
      SELECT r.id FROM routing r JOIN material m ON m.id = r.product_id
      WHERE m.code >= 'MAT-FG-101' AND m.code <= 'MAT-FG-110')),
  (SELECT count(*) FROM calendar WHERE code IN (
      'CAL-FAC-11','CAL-RES-HEAT-11','CAL-RES-HEAT-12','CAL-RES-HEAT-13','CAL-RES-HEAT-14','CAL-RES-HEAT-15'));
"@
    if ($LASTEXITCODE -ne 0) { throw '数据集指纹查询失败' }
    $parts = ($raw -join '').Trim().Split('|')
    return [ordered]@{
        orders    = [int]$parts[0]
        ordersMd5 = $parts[1]
        resources = [int]$parts[2]
        nodes     = [int]$parts[3]
        calendars = [int]$parts[4]
    }
}

function Get-FileHashSafe([string]$Path) {
    if (Test-Path $Path) { return (Get-FileHash $Path -Algorithm SHA256).Hash } else { return $null }
}

$before = Get-DatasetFingerprint
if ($before.orders -ne 500) {
    if ($Reseed) {
        Write-Host '[..] 数据集订单数不是 500，按 -Reseed 重建'
        & powershell -NoProfile -ExecutionPolicy Bypass -File $seeder -OrderCount 500 `
            -Toolchain $Toolchain -DbHost $DbHost -DbPort $DbPort -DbName $DbName `
            -DbUser $DbUser -DbPassword $DbPassword
        if ($LASTEXITCODE -ne 0) { throw '重建数据集失败' }
        $before = Get-DatasetFingerprint
    } else {
        throw "数据集订单数为 $($before.orders)，不是 500。请先运行 scripts\seed-m2-perf.ps1 -OrderCount 500，或加 -Reseed"
    }
}

if (-not $DryRun) {
    try {
        $health = Invoke-RestMethod -Uri "$BaseUrl/api/v1/health" -Method Get -TimeoutSec 5
        Write-Host ("[OK] health: " + ($health.data | ConvertTo-Json -Compress))
    } catch {
        throw "实例不可用（$BaseUrl），请先启动独立实例（建议 18089）后再复跑"
    }
}

$manifest = [ordered]@{
    batchLabel       = $BatchLabel
    startedAt        = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    baseUrl          = $BaseUrl
    dbName           = $DbName
    datasetBefore    = $before
    runnerSha256     = Get-FileHashSafe $runner
    seederSha256     = Get-FileHashSafe $seeder
    matrixSha256     = Get-FileHashSafe $PSCommandPath
    cases            = @($cases)
    betweenRunsSec   = $BetweenRunsSeconds
}
Write-Host ''
Write-Host ("[BATCH] {0} | db={1} | url={2} | ordersMd5={3}" -f $BatchLabel, $DbName, $BaseUrl, $before.ordersMd5)

$summary = @()
$index = 0
foreach ($case in $cases) {
    $index++
    if (-not $DryRun -and $index -gt 1 -and $BetweenRunsSeconds -gt 0) {
        Start-Sleep -Seconds $BetweenRunsSeconds
    }
    $label = "$BatchLabel-$($case.Name)"
    $hardLimit = if ($case.Timeout -gt 0) { $case.Timeout + 80 } else { 120 }
    $argList = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runner,
        '-Orders', "$($case.Orders)",
        '-Label', $label,
        '-BaseUrl', $BaseUrl,
        '-Toolchain', $Toolchain,
        '-DbHost', $DbHost, '-DbPort', "$DbPort", '-DbName', $DbName,
        '-DbUser', $DbUser, '-DbPassword', $DbPassword,
        '-GcLog', $GcLog,
        '-JavaPid', "$JavaPid",
        '-HardLimitSeconds', "$hardLimit",
        '-OutDir', $OutDir
    )
    if ($case.Threads -gt 0) { $argList += @('-MoveThreadCount', "$($case.Threads)") }
    if ($case.Timeout -gt 0) {
        $argList += @('-TimeoutSeconds', "$($case.Timeout)", '-UnimprovedSecondsSpentLimit', "$($case.Unimproved)")
    }

    Write-Host ''
    Write-Host ("=== CASE {0}/{1}: {2} (orders={3}, timeout={4}, threads={5}) ===" -f `
            $index, $cases.Count, $case.Name, $case.Orders,
        $(if ($case.Timeout -gt 0) { $case.Timeout } else { 'default' }),
        $(if ($case.Threads -gt 0) { $case.Threads } else { 'default' }))
    Write-Host ("    powershell " + ($argList -join ' '))

    if ($DryRun) {
        $summary += [ordered]@{ case = $case.Name; label = $label; dryRun = $true; status = 'NOT_RUN' }
        continue
    }

    & powershell @argList
    if ($LASTEXITCODE -ne 0) { throw "用例 $($case.Name) 执行失败 exit=$LASTEXITCODE" }

    $resultFile = Join-Path $OutDir ("{0}.json" -f $label)
    if (-not (Test-Path $resultFile)) { throw "缺少结果文件：$resultFile" }
    $r = Get-Content $resultFile -Raw | ConvertFrom-Json
    $summary += [ordered]@{
        case                = $case.Name
        label               = $label
        orders              = $r.orders
        taskId              = $r.taskId
        status              = $r.status
        bestScore           = $r.bestScore
        e2eMs               = $r.e2eMs
        engineDurationMs    = $r.engineDurationMs
        totalDurationMs     = $r.totalDurationMs
        persistedTaskCount  = $r.persistedTaskCount
        dbPersistedRows     = $r.dbPersistedRows
        taskCountMetric     = $r.taskCountMetric
        heapPeakMb          = $r.heapPeakMb
        workingSetPeakMb    = $r.workingSetPeakMb
        engineFallback      = $r.engineFallback
        terminationReason   = $r.terminationReason
        errorCode           = $r.errorCode
    }
}

$after = Get-DatasetFingerprint
$manifest.finishedAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
$manifest.datasetAfter = $after
$manifest.datasetUnchanged = ($before.ordersMd5 -eq $after.ordersMd5 -and $before.orders -eq $after.orders)

$batchFile = Join-Path $OutDir ("matrix-{0}.json" -f $BatchLabel)
@{ manifest = $manifest; cases = $summary } | ConvertTo-Json -Depth 8 |
    Set-Content -Path $batchFile -Encoding UTF8

$csvFile = Join-Path $OutDir ("matrix-{0}.csv" -f $BatchLabel)
$summary | ConvertTo-Csv -NoTypeInformation | Set-Content -Path $csvFile -Encoding UTF8

Write-Host ''
Write-Host '==== MATRIX SUMMARY ===='
if ($DryRun) {
    Write-Host ("DryRun: {0}/{1} 个用例命令已生成，未调用接口、未执行求解" -f $summary.Count, $cases.Count)
} else {
    $summary | Format-Table case, orders, status, bestScore, e2eMs, engineDurationMs, persistedTaskCount, dbPersistedRows, heapPeakMb, engineFallback -AutoSize
}
Write-Host ("datasetUnchanged={0}  batchJson={1}  batchCsv={2}" -f $manifest.datasetUnchanged, $batchFile, $csvFile)
