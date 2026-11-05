#requires -version 5.1
<#
  M2-QA-02 P1 回归脚本（可重复执行，同一请求 / 同一断言）

  判据已按契约对齐（依据见 docs/qa/m2-qa-02-defects.md 第 5 节）：
    R-01a  engine=ortools 降级路径：终态详情可读 + 降级标记齐全 + 四个声明字段恒输出
    R-01b  含显式 null 的 metrics：详情必须 200（历史任务即可，不依赖造数）
    R-02a  sandboxId 非法 UUID 格式：400/40000（反序列化层）
    R-02b  sandboxId 格式合法但不存在：404/40400（契约：场景不存在 → 40400）
    R-03   显式 orderNos 点名不存在订单：400/40000 且逐字点名

  用法：
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/qa/m2-qa-02-p1-regress.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts/qa/m2-qa-02-p1-regress.ps1 -BaseUrl http://localhost:18086/api/v1

  退出码：FAIL 条数（0 = 全通过）。TBD-contract 项不计入 FAIL，单独列出。
  数据：只写 QA2- 前缀自造实体，不触碰共享 sandbox 00000000-0000-0000-0000-000000000401。
#>
param(
    [string]$BaseUrl = 'http://localhost:18085/api/v1',
    [string]$ApiKey = $(if ($env:APS_DEV_API_KEY) { $env:APS_DEV_API_KEY } else { 'aps-dev-write-key-local-only' }),
    [string]$SandboxId = 'a2a2a2a2-0000-4000-8000-000000000001',
    [string]$OrderNo = 'QA2-SO-003',
    [string[]]$NullMetricsTaskIds = @('77', '74', '73', '58'),
    [int]$PollTimeoutSeconds = 30
)

$script:Failures = 0
$script:Passes = 0

function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Body)
    # 传输层退避：接口按契约 L1434-L1450 约 1 次/秒限流，超限返回 HTTP 429 / code 42900。
    # 这里只在收到 429 时等待重发同一请求，不改变任何判据的期望值。
    $status = 0
    $payload = ''
    $obj = $null
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        $tmp = Join-Path $env:TEMP ('qa2reg_' + [guid]::NewGuid().ToString('N') + '.json')
        $argv = @('-s', '-w', "`n%{http_code}", '-X', $Method, ($BaseUrl + $Path), '-H', "X-API-Key: $ApiKey")
        if ($null -ne $Body -and $Body.Length -gt 0) {
            [IO.File]::WriteAllText($tmp, $Body, (New-Object Text.UTF8Encoding($false)))
            $argv += @('-H', 'Content-Type: application/json', '--data-binary', "@$tmp")
        }
        $raw = (& curl.exe @argv) -join "`n"
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        $lines = $raw -split "`n"
        $status = 0
        $payload = ''
        if ($lines.Count -ge 2) {
            $status = [int]($lines[-1].Trim())
            $payload = (($lines[0..($lines.Count - 2)]) -join "`n").Trim()
        } else {
            $payload = $raw.Trim()
        }
        $obj = $null
        try { $obj = $payload | ConvertFrom-Json } catch { $obj = $null }
        if ($status -eq 429) { Start-Sleep -Milliseconds 1300; continue }
        break
    }
    return [pscustomobject]@{ Http = $status; Raw = $payload; Json = $obj }
}

function Report {
    param([string]$Id, [bool]$Pass, [string]$Detail)
    if ($Pass) {
        $script:Passes++
        Write-Output ('[PASS] ' + $Id + ' :: ' + $Detail)
    } else {
        $script:Failures++
        Write-Output ('[FAIL] ' + $Id + ' :: ' + $Detail)
    }
}

function Trace-Of {
    param($Resp)
    if ($Resp.Json -and $Resp.Json.PSObject.Properties.Name -contains 'traceId') { return [string]$Resp.Json.traceId }
    return '<none>'
}

function Code-Of {
    param($Resp)
    if ($Resp.Json -and $Resp.Json.PSObject.Properties.Name -contains 'code') { return [int]$Resp.Json.code }
    return -1
}

function Message-Of {
    param($Resp)
    if ($Resp.Json -and $Resp.Json.PSObject.Properties.Name -contains 'message') { return [string]$Resp.Json.message }
    return ''
}

Write-Output '== M2-QA-02 P1 regression (contract-aligned) =='
Write-Output ('base=' + $BaseUrl + ' sandbox=' + $SandboxId + ' order=' + $OrderNo)
Write-Output ''

# ------------------------------------------------- R-01a (QA2-01 降级路径可读)
Write-Output '--- R-01a engine=ortools degradation path is readable and fully marked ---'
$bodyA = @{ engine = 'ortools'; sandboxId = $SandboxId; orderNos = @($OrderNo); parameters = @{} } | ConvertTo-Json -Compress
$postA = Invoke-Api 'POST' '/solve-tasks' $bodyA
Write-Output ('POST /solve-tasks {engine=ortools} -> HTTP ' + $postA.Http + ' traceId=' + (Trace-Of $postA))
if ($postA.Http -ne 200 -or $postA.Json.code -ne 0) {
    Report 'R-01a' $false ('accept failed: HTTP=' + $postA.Http + ' body=' + $postA.Raw)
} else {
    $tidA = [string]$postA.Json.data.taskId
    Write-Output ('taskId=' + $tidA)
    $saw500A = $false
    $trace500A = @()
    $httpSeqA = @()
    $consec500A = 0
    $lastA = $null
    $deadlineA = (Get-Date).AddSeconds($PollTimeoutSeconds)
    while ((Get-Date) -lt $deadlineA) {
        Start-Sleep -Milliseconds 500
        $g = Invoke-Api 'GET' ('/solve-tasks/' + $tidA) $null
        $lastA = $g
        $httpSeqA += [string]$g.Http
        if ($g.Http -ge 500 -or (Code-Of $g) -eq 50000) {
            $saw500A = $true
            $trace500A += (Trace-Of $g)
            $consec500A++
        } else {
            $consec500A = 0
        }
        $st = ''
        if ($g.Json -and $g.Json.data) { $st = [string]$g.Json.data.status }
        if ($st -in @('SOLVED', 'FAILED', 'TIMEOUT', 'CANCELLED')) { break }
        if ($consec500A -ge 3) { break }
    }
    if ($null -eq $lastA -or -not $lastA.Json -or -not $lastA.Json.data) {
        Report 'R-01a' $false ('detail unreadable (task ' + $tidA + '), HTTP seq=[' + ($httpSeqA -join ',') +
                                '] trace500=[' + ($trace500A -join ',') + ']')
        Write-Output ('        raw=' + $lastA.Raw)
    } else {
        $dA = $lastA.Json.data
        $mNamesA = @($dA.metrics.PSObject.Properties.Name)
        $fbA = ($mNamesA -contains 'engineFallback') -and [bool]$dA.metrics.engineFallback
        $reasonA = if ($mNamesA -contains 'engineFallbackReason') { [string]$dA.metrics.engineFallbackReason } else { '' }
        $detailA = if ($mNamesA -contains 'engineFallbackDetail') { [string]$dA.metrics.engineFallbackDetail } else { '' }
        $degA = ($dA.PSObject.Properties.Name -contains 'degraded') -and [bool]$dA.degraded
        $topNames = @($dA.PSObject.Properties.Name)
        $fourA = ($topNames -contains 'bestScore') -and ($topNames -contains 'resultTaskId') -and
                 ($topNames -contains 'errorCode') -and ($topNames -contains 'errorMessage')
        $stA = [string]$dA.status
        $okA = (-not $saw500A) -and ($lastA.Http -eq 200) -and ((Code-Of $lastA) -eq 0) -and
               $fbA -and ($reasonA -eq 'SOLVER_UNAVAILABLE') -and ($detailA -eq 'ORTOOLS_NOT_ENABLED') -and
               $degA -and $fourA -and ($stA -ne 'FAILED')
        Report 'R-01a' $okA ('final HTTP=' + $lastA.Http + ' code=' + (Code-Of $lastA) + ' status=' + $stA +
                              ' degraded=' + $degA + ' engineFallback=' + $fbA +
                              ' reason=' + $reasonA + ' detail=' + $detailA +
                              ' fourKeys=' + $fourA + ' saw500=' + $saw500A +
                              ' trace500=[' + ($trace500A -join ',') + ']')
        if (-not $okA) { Write-Output ('        raw=' + $lastA.Raw) }
    }
}
Write-Output ''

# ------------------------------------------------- R-01b (QA2-01 空值 metrics 可读)
Write-Output '--- R-01b detail of a task whose metrics contain an explicit null must be 200 ---'
$okB = $false
$hitB = ''
$triedB = @()
$trace500B = @()
foreach ($tidB in $NullMetricsTaskIds) {
    $gB = Invoke-Api 'GET' ('/solve-tasks/' + $tidB) $null
    $triedB += ($tidB + ':HTTP' + $gB.Http)
    if ($gB.Http -eq 200 -and (Code-Of $gB) -eq 0 -and $gB.Json -and $gB.Json.data) {
        $mNamesB = @($gB.Json.data.metrics.PSObject.Properties.Name)
        $nullKeys = @($mNamesB | Where-Object { $null -eq $gB.Json.data.metrics.$_ })
        if ($nullKeys.Count -gt 0) {
            $okB = $true
            $hitB = ('task ' + $tidB + ' nullKeys=[' + ($nullKeys -join ',') + '] traceId=' + (Trace-Of $gB))
            break
        }
    } elseif ($gB.Http -ge 500) {
        $trace500B += ('task' + $tidB + '=' + (Trace-Of $gB))
    }
}
if ($okB) {
    Report 'R-01b' $true $hitB
} else {
    Report 'R-01b' $false ('no readable null-valued metrics task; tried=[' + ($triedB -join ' ') +
                           '] trace500=[' + ($trace500B -join ',') + ']')
    Write-Output '        NOTE: candidates are historical QA2 tasks; if purged, create one via the degraded-failure path and pass its id with -NullMetricsTaskIds'
}
Write-Output ''

# ------------------------------------------------- R-02a (sandboxId 格式非法)
Write-Output '--- R-02a malformed sandboxId (not a UUID) must be 400/40000 ---'
$body2a = @{ sandboxId = 'not-a-uuid'; parameters = @{} } | ConvertTo-Json -Compress
$r2a = Invoke-Api 'POST' '/solve-tasks' $body2a
$ok2a = ($r2a.Http -eq 400) -and ((Code-Of $r2a) -eq 40000)
Report 'R-02a' $ok2a ('HTTP=' + $r2a.Http + ' code=' + (Code-Of $r2a) + ' message=' + (Message-Of $r2a) +
                      ' traceId=' + (Trace-Of $r2a))
if (-not $ok2a) { Write-Output ('        raw=' + $r2a.Raw) }
Write-Output ''

# ------------------------------------------------- R-02b (sandboxId 合法但不存在)
Write-Output '--- R-02b well-formed but unknown sandboxId must be 404/40400 ---'
$body2b = @{ sandboxId = 'a2a2a2a2-0000-4000-8000-0000000000ff'; parameters = @{} } | ConvertTo-Json -Compress
$r2b = Invoke-Api 'POST' '/solve-tasks' $body2b
$msg2b = Message-Of $r2b
$ok2b = ($r2b.Http -eq 404) -and ((Code-Of $r2b) -eq 40400) -and
        ($msg2b -match '场景不存在|sandbox') -and ($r2b.Http -lt 500)
Report 'R-02b' $ok2b ('HTTP=' + $r2b.Http + ' code=' + (Code-Of $r2b) + ' message=' + $msg2b +
                      ' traceId=' + (Trace-Of $r2b))
if (-not $ok2b) { Write-Output ('        raw=' + $r2b.Raw) }
Write-Output ''

# ------------------------------------------------- R-03 (不存在订单号)
Write-Output '--- R-03 explicit unknown orderNo must be 400/40000 naming the order ---'
$body3 = @{ sandboxId = $SandboxId; orderNos = @('QA2-NOT-EXIST-ORDER'); parameters = @{} } | ConvertTo-Json -Compress
$r3 = Invoke-Api 'POST' '/solve-tasks' $body3
$msg3 = Message-Of $r3
$ok3 = ($r3.Http -eq 400) -and ((Code-Of $r3) -eq 40000) -and ($msg3 -match 'QA2-NOT-EXIST-ORDER') -and ($r3.Http -ne 200)
Report 'R-03' $ok3 ('HTTP=' + $r3.Http + ' code=' + (Code-Of $r3) + ' message=' + $msg3 +
                    ' traceId=' + (Trace-Of $r3))
if (-not $ok3) { Write-Output ('        raw=' + $r3.Raw) }
Write-Output ''

Write-Output 'TBD-contract: R-01a does not assert terminal status value (contract L1397 vs L1754 tension); only status != FAILED is enforced.'
Write-Output ('SUMMARY: PASS=' + $script:Passes + ' FAIL=' + $script:Failures)
exit $script:Failures
