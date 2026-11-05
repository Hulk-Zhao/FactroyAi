# 演示用：90 秒预算的六单全量求解（真实调用 POST /api/v1/solve-tasks）
# 默认即剧本口径：订单 DM-SO-1001,DM-SO-1002,DM-SO-1003,DM-SO-1004,DM-SO-1005,DM-SO-1006，
#   预算 timeoutSeconds=90 / unimprovedSecondsSpentLimit=90（见 docs/guide/demo-playbook.md §3.2）。
# 可用 -OrderNos 覆盖订单集合；-WhatIf 只打印将要发送的请求体，不提交求解。
# 用法（先设置演示写密钥，密钥见 docs/guide/demo-checklist.md 一.3）：
#   $env:APS_DEMO_API_KEY='<演示写密钥>'
#   .\demo-assets\solve-90.ps1 -BaseUrl http://127.0.0.1:18085
#   .\demo-assets\solve-90.ps1 -WhatIf
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Low')]
param(
    [string]$BaseUrl = 'http://127.0.0.1:18085',
    [string]$ApiKey = $env:APS_DEMO_API_KEY,
    [string]$TenantId = '00000000-0000-0000-0000-000000000001',
    [string[]]$OrderNos = @('DM-SO-1001', 'DM-SO-1002', 'DM-SO-1003', 'DM-SO-1004', 'DM-SO-1005', 'DM-SO-1006'),
    [int]$TimeoutSeconds = 90,
    [int]$UnimprovedSeconds = 90
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$headers = @{
    'X-API-Key'    = $ApiKey
    'X-Tenant-Id'  = $TenantId
    'Content-Type' = 'application/json'
}
$body = @{
    taskNo     = 'SOLVE-DEMO-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    sandboxId  = '00000000-0000-0000-0000-000000000401'
    engine     = 'TIMEFOLD'
    orderNos   = $OrderNos
    parameters = @{ timeoutSeconds = $TimeoutSeconds; unimprovedSecondsSpentLimit = $UnimprovedSeconds }
} | ConvertTo-Json -Depth 5

if ($WhatIfPreference) {
    Write-Host '[-WhatIf] 未提交求解；以下是将要发送的请求体：' -ForegroundColor Yellow
    Write-Host "POST $BaseUrl/api/v1/solve-tasks"
    Write-Host $body
    exit 0
}

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Host '缺少 API 密钥：先执行 $env:APS_DEMO_API_KEY=... 再运行本脚本。' -ForegroundColor Red
    exit 1
}

$sw = [Diagnostics.Stopwatch]::StartNew()
try {
    $r = Invoke-WebRequest -Uri "$BaseUrl/api/v1/solve-tasks" -Method Post -Headers $headers `
        -Body $body -TimeoutSec 30 -UseBasicParsing
} catch {
    Write-Host "提交失败：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
$env1 = $r.Content | ConvertFrom-Json
$taskId = $env1.data.taskId
$traceId = $env1.traceId
Write-Host "已提交 taskId=$taskId traceId=$traceId" -ForegroundColor Cyan
Write-Host "预算 timeoutSeconds=$TimeoutSeconds / unimprovedSecondsSpentLimit=$UnimprovedSeconds；等待真实求解，不要重复点击。" -ForegroundColor Yellow

$terminal = @('SOLVED', 'FAILED', 'INFEASIBLE', 'CANCELLED', 'TIMEOUT', 'ERROR')
$lastPrint = 0
$detail = $null
while ($sw.Elapsed.TotalSeconds -lt 260) {
    Start-Sleep -Seconds 2
    try {
        $g = Invoke-WebRequest -Uri "$BaseUrl/api/v1/solve-tasks/$taskId" -Headers $headers `
            -TimeoutSec 10 -UseBasicParsing
        $detail = ($g.Content | ConvertFrom-Json).data
    } catch {
        Write-Host "  查询失败（继续轮询）：$($_.Exception.Message)" -ForegroundColor DarkYellow
        continue
    }
    $t = [int]$sw.Elapsed.TotalSeconds
    if ($t - $lastPrint -ge 10 -or $terminal -contains $detail.status) {
        Write-Host ("  [t={0,3}s] status={1} bestScore={2} elapsed={3}s" -f `
            $t, $detail.status, $detail.bestScore, [math]::Round($detail.elapsedMs / 1000, 1))
        $lastPrint = $t
    }
    if ($terminal -contains $detail.status) { break }
}

if ($null -eq $detail) {
    Write-Host '260 秒内没拿到终态；去 GET /solve-tasks 查事实源，不要反复提交。' -ForegroundColor Red
    exit 3
}

$m = $detail.metrics
Write-Host ''
Write-Host '================ 终态 ================' -ForegroundColor Cyan
Write-Host "status        : $($detail.status)"
Write-Host "bestScore     : $($detail.bestScore)"
Write-Host "durationMs    : $($m.durationMs)"
Write-Host "elapsedMs     : $($detail.elapsedMs)"
Write-Host "tasks         : expected=$($m.expectedTaskCount) assigned=$($m.assignedTaskCount) persisted=$($m.persistedTaskCount) unassigned=$($m.unassignedTaskCount)"
Write-Host "attemptCount  : $($m.attemptCount)  terminationReason=$($m.terminationReason)"
Write-Host "traceId       : $traceId"
Write-Host "taskId        : $taskId"

if ($detail.status -eq 'SOLVED') {
    Write-Host '结果合格：SOLVED / 0hard / 0soft。切到浏览器刷新 /gantt?source=api 看新排程。' -ForegroundColor Green
    exit 0
}

Write-Host ''
Write-Host '未成功——现场按以下口径接话，不要连点：' -ForegroundColor Yellow
Write-Host "  errorMessage: $($detail.errorMessage)"
Write-Host "  retryReasons: $($m.retryReasons -join ',')"
Write-Host '  服务端最多自动重试 1 次，固定间隔 30 秒；最坏耗时约 2×timeout+30s。'
Write-Host '  若已终态 FAILED：回到上一版 SOLVED 甘特讲基线，不要当场改种子/改代码。'
exit 2
