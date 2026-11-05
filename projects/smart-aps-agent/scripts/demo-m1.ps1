#Requires -Version 5.1
<#
.SYNOPSIS
    M1 closed-loop demo: seed -> start backend on 18086 -> confirm -> solve -> task checks -> gantt checks -> re-run.

.DESCRIPTION
    Every step is asserted. Any FAIL prints "FAIL ..." and exits with code 1 immediately
    (the backend started by this script is stopped in the finally block).

    Steps / assertions:
      1  backend health 200
      2  confirm 8 DRAFT orders -> 200 + CONFIRMED
      3  confirm the no-BOM order -> 422 + business code 42200
      4  POST /api/v1/solve-tasks -> 200 + numeric taskId; poll GET /solve-tasks/{id}
         until terminal, assert status SOLVED and read tasks[] from the GET payload
      5  solve_task row -> SOLVED, score_hard=0, durationMs > 0, persisted count == demo task rows
      6  task rows -> end > start, setup_end = start, setup_start offset, 1..n sequence,
         and every demo task row appears in the GET tasks snapshot
      7  GET /api/v1/gantt/resource -> resource/task counts match task table, sampled fields equal
      8  run solve a second time -> new taskId, GET tasks count unchanged, old rows replaced,
         no duplicates

    契约演进说明（C20 形状统一，2026-11-04）：
      M2 起 POST /solve-tasks 一律返回 SolveTaskDetail，状态使用六值枚举
      PENDING/RUNNING/SOLVED/FAILED/CANCELLED/TIMEOUT（SUCCESS 已废弃）。
      因此求解环节的断言由「POST 回包内联 tasks + status=SUCCESS」迁移为
      「POST 回包取数字 taskId → 轮询 GET 到终态 → 从 GET 取 tasks → 与库内 task 行数比对」。
      这是契约演进导致的断言迁移，不是放宽验收：断言数量不减少、口径不放松
      （tasks 条数仍必须等于库内行数，重跑仍要求 48→48 且旧行清除）。

.PARAMETER SkipSeed
    Reuse the current demo data instead of re-running scripts/seed-m1-demo.ps1.

.PARAMETER KeepBackend
    Do not stop the backend started by this script at the end.

.EXAMPLE
    .\scripts\demo-m1.ps1
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:18086',
    [string]$Toolchain = $(if ($env:APS_TOOLCHAIN) { $env:APS_TOOLCHAIN } else { 'D:\tools\_aps-toolchain' }),
    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 55432 }),
    [string]$DbName = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'aps_local' }),
    [string]$DbUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'aps' }),
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'aps_dev_pwd' }),
    [string]$TenantId = '00000000-0000-0000-0000-000000000001',
    [string]$SandboxId = '00000000-0000-0000-0000-000000000401',
    [string]$StrategyId = 'd0000000-0000-0000-0000-000000000801',
    [int]$Port = 18086,
    [switch]$SkipSeed,
    [switch]$KeepBackend
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$BaseUrl = $BaseUrl.TrimEnd('/')
$psql = Join-Path $Toolchain 'pgsql\bin\psql.exe'
$javaExe = Join-Path $Toolchain 'jdk-21.0.13+9\bin\java.exe'
$jarPath = Join-Path $repoRoot 'aps-admin\target\aps-admin-0.1.0-SNAPSHOT.jar'
$evidenceDir = Join-Path $repoRoot 'target\m1-demo'
$stdoutLog = Join-Path $evidenceDir 'backend.out.log'
$stderrLog = Join-Path $evidenceDir 'backend.err.log'

$script:PassCount = 0
$script:StartedProcess = $null
$script:StartedBackend = $false
$exitCode = 0

function Write-Step {
    param([string]$Message)
    Write-Host ''
    Write-Host "[STEP] $Message" -ForegroundColor Cyan
}

function Assert-True {
    param([bool]$Condition, [string]$Label, [string]$Detail)
    if (-not $Condition) {
        Write-Host ("FAIL  {0}  {1}" -f $Label, $Detail) -ForegroundColor Red
        throw "ASSERT_FAILED: $Label ($Detail)"
    }
    $script:PassCount++
    Write-Host ("PASS  {0}  {1}" -f $Label, $Detail) -ForegroundColor Green
}

function Invoke-Api {
    param([string]$Method, [string]$Url, $Body)
    $opts = @{ Uri = $Url; Method = $Method; TimeoutSec = 300; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $opts.ContentType = 'application/json; charset=utf-8'
        $opts.Body = ($Body | ConvertTo-Json -Depth 10 -Compress)
    }
    try {
        $resp = Invoke-WebRequest @opts
        # Windows PowerShell 5.1 decodes application/json as ISO-8859-1 when the
        # response has no charset; re-decode the raw bytes as UTF-8 so Chinese stays readable.
        $text = $resp.Content
        if ($null -ne $resp.RawContentStream) {
            $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        }
        return [pscustomobject]@{
            Status = [int]$resp.StatusCode
            Body   = $text
            Json   = ($text | ConvertFrom-Json)
        }
    }
    catch {
        $webResp = $_.Exception.Response
        if ($null -eq $webResp) { throw }
        $status = [int]$webResp.StatusCode
        $stream = $webResp.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
        $text = $reader.ReadToEnd()
        $reader.Dispose()
        $json = $null
        try { $json = $text | ConvertFrom-Json } catch { }
        return [pscustomobject]@{ Status = $status; Body = $text; Json = $json }
    }
}

function Test-Health {
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/health" -UseBasicParsing -TimeoutSec 5
        if ([int]$resp.StatusCode -ne 200) { return $false }
        $json = $resp.Content | ConvertFrom-Json
        return ($json.code -eq 0 -and $json.data.status -eq 'UP')
    }
    catch {
        return $false
    }
}

function Get-PortOwner {
    param([int]$LocalPort)
    $conn = Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $conn) { return [int]$conn.OwningProcess }
    return 0
}

function Invoke-Psql {
    param([string]$Sql)
    $lines = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -A -t -F '|' -v ON_ERROR_STOP=1 -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed (exit $LASTEXITCODE): $Sql" }
    return @($lines | ForEach-Object { $_.TrimEnd("`r") })
}

function Get-Scalar {
    param([string]$Sql)
    $lines = @(Invoke-Psql -Sql $Sql | Where-Object { $_ -ne '' })
    if ($lines.Count -eq 0) { return $null }
    return $lines[0]
}

function Convert-IsoToEpoch {
    param([string]$Iso)
    return [long]([datetimeoffset]::Parse($Iso, [System.Globalization.CultureInfo]::InvariantCulture).ToUnixTimeSeconds())
}

function Format-EpochUtc {
    param([long]$Epoch)
    return ([datetimeoffset]::FromUnixTimeSeconds($Epoch)).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
}

function Wait-SolveTerminal {
    param([long]$TaskId, [int]$TimeoutSeconds = 180)
    $terminal = @('SOLVED', 'FAILED', 'CANCELLED', 'TIMEOUT')
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $detail = Invoke-Api -Method Get -Url "$BaseUrl/api/v1/solve-tasks/$TaskId"
        if ($detail.Status -ne 200 -or $detail.Json.code -ne 0) {
            throw "GET /solve-tasks/$TaskId failed: http=$($detail.Status) body=$($detail.Body)"
        }
        $status = $detail.Json.data.status
        if ($terminal -contains $status) { return $detail.Json.data }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "solve task $TaskId did not reach terminal within $TimeoutSeconds s"
}

$validOrders = @(
    'SO-20261104-0001', 'SO-20261104-0002', 'SO-20261104-0003', 'SO-20261104-0004',
    'SO-20261104-0005', 'SO-20261104-0006', 'SO-20261104-0007', 'SO-20261104-0008'
)
$negativeOrder = 'SO-20261104-0009'
$validOrderSql = ($validOrders | ForEach-Object { "'$_'" }) -join ','
$expectedOps = @(10, 20, 30, 40, 50, 60)
$expectedSetupByOp = @{ 10 = 30; 20 = 30; 30 = 30; 40 = 10; 50 = 10; 60 = 10 }
$humanId = 'd0000000-0000-0000-0000-000000000306'
$qaId = 'd0000000-0000-0000-0000-000000000305'

try {
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    $env:PGPASSWORD = $DbPassword

    if (-not (Test-Path $psql)) { throw "psql not found: $psql" }
    if (-not (Test-Path $javaExe)) { throw "java not found: $javaExe" }

    $dbListening = Test-NetConnection -ComputerName $DbHost -Port $DbPort -InformationLevel Quiet -WarningAction SilentlyContinue
    if (-not $dbListening) {
        Write-Step "Port $DbHost`:$DbPort is closed, starting local PostgreSQL"
        & (Join-Path $PSScriptRoot 'local-db-up.ps1') -Toolchain $Toolchain -Port $DbPort -User $DbUser -Password $DbPassword
    }

    if ($SkipSeed) {
        Write-Step 'SkipSeed: reusing current demo data'
    }
    else {
        Write-Step 'Seeding idempotent M1 demo dataset'
        & (Join-Path $PSScriptRoot 'seed-m1-demo.ps1') -Toolchain $Toolchain -DbHost $DbHost -DbPort $DbPort `
            -DbName $DbName -DbUser $DbUser -DbPassword $DbPassword -TenantId $TenantId
    }

    Write-Step ("Starting backend on port {0} (dev profile, real DB)" -f $Port)
    if (Test-Health) {
        Write-Host "    reusing healthy backend at $BaseUrl (not started by this script)" -ForegroundColor Yellow
    }
    else {
        $owner = Get-PortOwner -LocalPort $Port
        if ($owner -ne 0) {
            throw "Port $Port is occupied by PID $owner but /api/v1/health is not healthy"
        }
        if (-not (Test-Path $jarPath)) {
            Write-Step 'Jar missing, building aps-admin (module-level, skip tests)'
            $env:JAVA_HOME = Join-Path $Toolchain 'jdk-21.0.13+9'
            $env:PATH = "$env:JAVA_HOME\bin;$(Join-Path $Toolchain 'apache-maven-3.9.16\bin');$env:PATH"
            & (Join-Path $Toolchain 'apache-maven-3.9.16\bin\mvn.cmd') -B -o -pl aps-admin -am -DskipTests package
            if ($LASTEXITCODE -ne 0) { throw "mvn package failed with exit code $LASTEXITCODE" }
            if (-not (Test-Path $jarPath)) { throw "jar still missing after build: $jarPath" }
        }
        $env:SERVER_PORT = "$Port"
        $env:SPRING_PROFILES_ACTIVE = 'dev'
        $env:DB_HOST = $DbHost
        $env:DB_PORT = "$DbPort"
        $env:DB_NAME = $DbName
        $env:DB_USER = $DbUser
        $env:DB_PASSWORD = $DbPassword
        Remove-Item $stdoutLog, $stderrLog -Force -ErrorAction SilentlyContinue
        $proc = Start-Process -FilePath $javaExe -ArgumentList @('-jar', $jarPath) `
            -WorkingDirectory $repoRoot -RedirectStandardOutput $stdoutLog `
            -RedirectStandardError $stderrLog -PassThru -NoNewWindow
        $script:StartedProcess = $proc
        $script:StartedBackend = $true
        $ready = $false
        for ($i = 0; $i -lt 150; $i++) {
            if ($proc.HasExited) { break }
            if (Test-Health) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) {
            $errTail = ''
            if (Test-Path $stderrLog) { $errTail = (Get-Content $stderrLog -Tail 40) -join "`n" }
            throw "Backend did not become healthy on $BaseUrl after 150s. stderr tail:`n$errTail"
        }
        Write-Host "    backend started, PID=$($proc.Id), log=$stdoutLog" -ForegroundColor Gray
    }

    # --------------------------------------------------------------------
    # 1. health
    # --------------------------------------------------------------------
    Write-Step '1/8 health check'
    $health = Invoke-Api -Method Get -Url "$BaseUrl/api/v1/health"
    Assert-True -Condition ($health.Status -eq 200) -Label 'health.http' -Detail ("http={0}" -f $health.Status)
    Assert-True -Condition ($health.Json.code -eq 0 -and $health.Json.data.status -eq 'UP') `
        -Label 'health.body' -Detail ("code={0} status={1}" -f $health.Json.code, $health.Json.data.status)

    # --------------------------------------------------------------------
    # 2. confirm 8 orders
    # --------------------------------------------------------------------
    Write-Step '2/8 confirm 8 DRAFT orders'
    foreach ($orderNo in $validOrders) {
        $r = Invoke-Api -Method Put -Url "$BaseUrl/api/v1/orders/$orderNo/confirm"
        Assert-True -Condition ($r.Status -eq 200 -and $r.Json.code -eq 0) `
            -Label "confirm.$orderNo" -Detail ("http={0} code={1}" -f $r.Status, $r.Json.code)
        Assert-True -Condition ($r.Json.data.status -eq 'CONFIRMED') `
            -Label "confirm.$orderNo.status" -Detail ("status={0} version={1}" -f $r.Json.data.status, $r.Json.data.version)
    }

    # --------------------------------------------------------------------
    # 3. negative confirm (no ACTIVE BOM)
    # --------------------------------------------------------------------
    Write-Step '3/8 negative confirm: no ACTIVE BOM'
    $neg = Invoke-Api -Method Put -Url "$BaseUrl/api/v1/orders/$negativeOrder/confirm"
    Assert-True -Condition ($neg.Status -eq 422) -Label 'confirm.noBom.http' -Detail ("http={0}" -f $neg.Status)
    Assert-True -Condition ($null -ne $neg.Json -and $neg.Json.code -eq 42200) `
        -Label 'confirm.noBom.code' -Detail ("code={0} message={1}" -f $neg.Json.code, $neg.Json.message)

    # --------------------------------------------------------------------
    # 4. solve (first run)
    # --------------------------------------------------------------------
    Write-Step '4/8 solve 8 confirmed orders'
    $solveBody = @{
        taskNo     = 'SOLVE-20261104-0001'
        sandboxId  = $SandboxId
        strategyId = $StrategyId
        orderNos   = $validOrders
        parameters = @{
            timeoutSeconds            = 30
            unimprovedSecondsSpentLimit = 10
            moveThreadCount           = 1
        }
    }
    $solve1 = Invoke-Api -Method Post -Url "$BaseUrl/api/v1/solve-tasks" -Body $solveBody
    Assert-True -Condition ($solve1.Status -eq 200 -and $solve1.Json.code -eq 0) `
        -Label 'solve.run1.http' -Detail ("http={0} code={1}" -f $solve1.Status, $solve1.Json.code)
    Assert-True -Condition ([long]$solve1.Json.data.taskId -gt 0) `
        -Label 'solve.run1.taskId' -Detail ("taskId={0}" -f $solve1.Json.data.taskId)
    $solve1Detail = Wait-SolveTerminal -TaskId ([long]$solve1.Json.data.taskId)
    Assert-True -Condition ($solve1Detail.taskId -eq $solve1.Json.data.taskId) `
        -Label 'solve.run1.get.taskId' -Detail ("POST={0} GET={1}" -f $solve1.Json.data.taskId, $solve1Detail.taskId)
    Assert-True -Condition ($solve1Detail.status -eq 'SOLVED') `
        -Label 'solve.run1.status' -Detail ("status={0}" -f $solve1Detail.status)
    $responseTaskCount = @($solve1Detail.tasks).Count
    $sandboxTaskCount = [int](Get-Scalar -Sql "SELECT count(*) FROM task WHERE tenant_id = '$TenantId' AND sandbox_id = '$SandboxId' AND deleted = false;")
    Assert-True -Condition ($responseTaskCount -eq $sandboxTaskCount) `
        -Label 'solve.run1.getTasksEqSandboxRows' -Detail ("GET tasks={0} sandbox rows={1}" -f $responseTaskCount, $sandboxTaskCount)
    Write-Host ("    solve.run1 taskId={0} status={1} GET tasks: {2} (sandbox rows: {3})" -f $solve1Detail.taskId, $solve1Detail.status, $responseTaskCount, $sandboxTaskCount) -ForegroundColor DarkGray

    # --------------------------------------------------------------------
    # 5. solve_task row
    # --------------------------------------------------------------------
    Write-Step '5/8 solve_task row (SOLVED / score / durationMs)'
    $solveRow = @(Invoke-Psql -Sql @"
SELECT id, status, score_hard, score_soft, metrics->>'durationMs', metrics->>'taskCount',
       metrics->>'persistedTaskCount', metrics->>'justificationCount',
       metrics->>'calendarFallback', metrics->>'dependencyFallback', metrics->>'setupMatrixFallback'
FROM solve_task
WHERE tenant_id = '$TenantId' AND strategy_id = '$StrategyId'
ORDER BY id DESC LIMIT 1;
"@ | Where-Object { $_ -ne '' })
    Assert-True -Condition ($solveRow.Count -eq 1) -Label 'solveTask.rowCount' -Detail ("rows={0}" -f $solveRow.Count)
    $s = $solveRow[0].Split('|')
    Write-Host ("    solve_task id={0} status={1} score={2}hard/{3}soft durationMs={4} taskCount={5} persisted={6} justifications={7}" -f `
            $s[0], $s[1], $s[2], $s[3], $s[4], $s[5], $s[6], $s[7]) -ForegroundColor DarkGray
    Write-Host ("    fallbacks: calendar={0} dependency={1} setupMatrix={2}" -f $s[8], $s[9], $s[10]) -ForegroundColor DarkGray
    Assert-True -Condition ($s[1] -eq 'SOLVED') -Label 'solveTask.status' -Detail ("status={0}" -f $s[1])
    Assert-True -Condition ([long]$s[2] -eq 0) -Label 'solveTask.scoreHard' -Detail ("score_hard={0}" -f $s[2])
    Assert-True -Condition ([long]$s[4] -gt 0) -Label 'solveTask.durationMs' -Detail ("durationMs={0}" -f $s[4])
    $demoTaskCount = [int](Get-Scalar -Sql @"
SELECT count(*) FROM task t JOIN orders o ON o.id = t.order_id
WHERE t.tenant_id = '$TenantId' AND t.sandbox_id = '$SandboxId' AND t.deleted = false
  AND o.order_no IN ($validOrderSql);
"@)
    Assert-True -Condition ([int]$s[6] -eq $demoTaskCount) `
        -Label 'solveTask.persistedTaskCount' -Detail ("persisted={0} demoRows={1}" -f $s[6], $demoTaskCount)

    # --------------------------------------------------------------------
    # 6. task table consistency
    # --------------------------------------------------------------------
    Write-Step '6/8 task rows: time range / setup / sequence / secondary resources'
    $taskSql = @"
SELECT t.id, o.order_no, t.operation_index, r.code,
       extract(epoch FROM t.start_time)::bigint,
       extract(epoch FROM t.end_time)::bigint,
       extract(epoch FROM t.setup_start_time)::bigint,
       extract(epoch FROM t.setup_end_time)::bigint,
       t.qty::text, t.status, COALESCE(t.sequence_in_resource, -1),
       t.secondary_resource_ids::text, t.resource_id
FROM task t
JOIN orders o ON o.id = t.order_id
LEFT JOIN resource r ON r.id = t.resource_id
WHERE t.tenant_id = '$TenantId' AND t.sandbox_id = '$SandboxId' AND t.deleted = false
  AND o.order_no IN ($validOrderSql)
ORDER BY o.order_no, t.operation_index;
"@
    $rawTaskRows = @(Invoke-Psql -Sql $taskSql | Where-Object { $_ -ne '' })
    $tasks = @()
    foreach ($line in $rawTaskRows) {
        $p = $line.Split('|')
        if ($p.Count -lt 13) { throw "unexpected task row: $line" }
        $tasks += [pscustomobject]@{
            Id          = [long]$p[0]
            OrderNo     = $p[1]
            OpIndex     = [int]$p[2]
            ResourceCode= $p[3]
            Start       = [long]$p[4]
            End         = [long]$p[5]
            SetupStart  = [long]$p[6]
            SetupEnd    = [long]$p[7]
            Qty         = [decimal]$p[8]
            Status      = $p[9]
            Seq         = [int]$p[10]
            Secondary   = $p[11]
            ResourceId  = $p[12]
        }
    }
    Assert-True -Condition ($tasks.Count -eq $demoTaskCount) `
        -Label 'task.count' -Detail ("taskRows={0} demoRows={1} (GET sandbox tasks={2})" -f $tasks.Count, $demoTaskCount, $responseTaskCount)

    $getTaskSet = @{}
    foreach ($gt in @($solve1Detail.tasks)) {
        $getTaskSet[('{0}|{1}|{2}|{3}' -f $gt.orderNo, $gt.resourceCode, (Convert-IsoToEpoch $gt.startTime), (Convert-IsoToEpoch $gt.endTime))] = $true
    }
    $missingInGet = @($tasks | Where-Object {
            -not $getTaskSet.ContainsKey(('{0}|{1}|{2}|{3}' -f $_.OrderNo, $_.ResourceCode, $_.Start, $_.End)) })
    Assert-True -Condition ($missingInGet.Count -eq 0) -Label 'solve.run1.getTasksCoverDb' `
        -Detail ("missing={0} of {1} demo rows in GET tasks" -f $missingInGet.Count, $tasks.Count)

    $badTime = @($tasks | Where-Object { $_.End -le $_.Start })
    Assert-True -Condition ($badTime.Count -eq 0) -Label 'task.endGtStart' `
        -Detail ("violations={0}" -f $badTime.Count)

    $badSetupEnd = @($tasks | Where-Object { $_.SetupEnd -ne $_.Start })
    Assert-True -Condition ($badSetupEnd.Count -eq 0) -Label 'task.setupEndEqStart' `
        -Detail ("violations={0} (all setup_end_time = start_time)" -f $badSetupEnd.Count)

    $badSetupSpan = @($tasks | Where-Object {
            ($_.Start - $_.SetupStart) -ne $expectedSetupByOp[$_.OpIndex] })
    Assert-True -Condition ($badSetupSpan.Count -eq 0) -Label 'task.setupSpan' `
        -Detail ("violations={0} (expected 30s for ops 10/20/30, 10s for 40/50/60)" -f $badSetupSpan.Count)

    $badStatus = @($tasks | Where-Object { $_.Status -ne 'PLANNED' })
    Assert-True -Condition ($badStatus.Count -eq 0) -Label 'task.status' -Detail ("nonPlanned={0}" -f $badStatus.Count)

    $unexpectedOps = @($tasks | Where-Object { $expectedOps -notcontains $_.OpIndex })
    Assert-True -Condition ($unexpectedOps.Count -eq 0) -Label 'task.ops' `
        -Detail ("unexpectedOps={0}" -f $unexpectedOps.Count)

    $orderQtyRows = @(Invoke-Psql -Sql "SELECT order_no, qty::text FROM orders WHERE tenant_id = '$TenantId' AND order_no IN ($validOrderSql) AND status = 'CONFIRMED';" |
            Where-Object { $_ -ne '' })
    $orderQty = @{}
    foreach ($line in $orderQtyRows) {
        $p = $line.Split('|')
        $orderQty[$p[0]] = [decimal]$p[1]
    }
    $badQty = @($tasks | Where-Object { $orderQty[$_.OrderNo] -ne $_.Qty })
    Assert-True -Condition ($badQty.Count -eq 0) -Label 'task.qty' -Detail ("mismatches={0}" -f $badQty.Count)

    $seqViolations = @()
    foreach ($group in @($tasks | Group-Object ResourceCode)) {
        $seqs = @($group.Group | ForEach-Object { $_.Seq } | Sort-Object)
        for ($i = 0; $i -lt $seqs.Count; $i++) {
            if ($seqs[$i] -ne ($i + 1)) {
                $seqViolations += ("{0}:{1}!={2}" -f $group.Name, $seqs[$i], ($i + 1))
            }
        }
    }
    Assert-True -Condition ($seqViolations.Count -eq 0) -Label 'task.sequenceContiguous' `
        -Detail ("violations={0} (1..n per resource, resources={1})" -f $seqViolations.Count, @($tasks | Group-Object ResourceCode).Count)

    $opsViolations = @()
    foreach ($group in @($tasks | Group-Object OrderNo)) {
        $ops = @($group.Group | ForEach-Object { $_.OpIndex } | Sort-Object)
        if (($ops -join ',') -ne ($expectedOps -join ',')) {
            $opsViolations += ("{0}={1}" -f $group.Name, ($ops -join ','))
        }
    }
    Assert-True -Condition ($opsViolations.Count -eq 0) -Label 'task.orderOpChain' `
        -Detail ("violations={0} (each order has ops 10..60)" -f $opsViolations.Count)

    $secondaryOp40 = @($tasks | Where-Object { $_.OpIndex -eq 40 -and $_.Secondary -like "*$humanId*" })
    $secondaryOp50 = @($tasks | Where-Object { $_.OpIndex -eq 50 -and $_.Secondary -like "*$qaId*" })
    Assert-True -Condition ($secondaryOp40.Count -eq 8) -Label 'task.secondaryHuman' `
        -Detail ("op40 with HUMAN={0}/8" -f $secondaryOp40.Count)
    Assert-True -Condition ($secondaryOp50.Count -eq 8) -Label 'task.secondaryQa' `
        -Detail ("op50 with QA={0}/8" -f $secondaryOp50.Count)

    $primaryCodes = @($tasks | ForEach-Object { $_.ResourceCode } | Sort-Object -Unique)
    Write-Host ("    task rows={0}, primary resources={1} ({2}), orders={3}" -f `
            $tasks.Count, $primaryCodes.Count, ($primaryCodes -join ','), @($tasks | Group-Object OrderNo).Count) -ForegroundColor DarkGray

    # --------------------------------------------------------------------
    # 7. gantt cross-check
    # --------------------------------------------------------------------
    Write-Step '7/8 gantt read model matches task table'
    $minStart = ($tasks | Measure-Object -Property Start -Minimum).Minimum
    $maxEnd = ($tasks | Measure-Object -Property End -Maximum).Maximum
    $fromIso = Format-EpochUtc -Epoch ([long]$minStart - 3600)
    $toIso = Format-EpochUtc -Epoch ([long]$maxEnd + 3600)
    $codesParam = [uri]::EscapeDataString(($primaryCodes -join ','))
    $ganttUrl = "$BaseUrl/api/v1/gantt/resource?sandboxId=$SandboxId&from=$([uri]::EscapeDataString($fromIso))&to=$([uri]::EscapeDataString($toIso))&resourceCodes=$codesParam"
    $ganttResp = Invoke-Api -Method Get -Url $ganttUrl
    Assert-True -Condition ($ganttResp.Status -eq 200 -and $ganttResp.Json.code -eq 0) `
        -Label 'gantt.http' -Detail ("http={0} code={1} from={2} to={3}" -f $ganttResp.Status, $ganttResp.Json.code, $fromIso, $toIso)
    $ganttResources = @($ganttResp.Json.data.resources)
    $ganttTasks = @()
    foreach ($resource in $ganttResources) {
        foreach ($task in @($resource.tasks)) { $ganttTasks += $task }
    }
    Write-Host ("    gantt resources={0}, tasks={1}" -f $ganttResources.Count, $ganttTasks.Count) -ForegroundColor DarkGray

    Assert-True -Condition ($ganttResources.Count -eq $primaryCodes.Count) `
        -Label 'gantt.resourceCount' -Detail ("gantt={0} taskTable={1}" -f $ganttResources.Count, $primaryCodes.Count)
    Assert-True -Condition ($ganttTasks.Count -eq $tasks.Count) `
        -Label 'gantt.taskCount' -Detail ("gantt={0} taskTable={1}" -f $ganttTasks.Count, $tasks.Count)

    $taskById = @{}
    foreach ($task in $tasks) { $taskById[[string]$task.Id] = $task }
    $missingInDb = @()
    $duplicateInGantt = @()
    foreach ($g in $ganttTasks) {
        $key = [string]$g.id
        if (-not $taskById.ContainsKey($key)) { $missingInDb += $key }
        $count = @($ganttTasks | Where-Object { [string]$_.id -eq $key }).Count
        if ($count -gt 1) { $duplicateInGantt += $key }
    }
    Assert-True -Condition ($missingInDb.Count -eq 0) -Label 'gantt.idsExistInTask' `
        -Detail ("missing={0}" -f $missingInDb.Count)
    Assert-True -Condition ($duplicateInGantt.Count -eq 0) -Label 'gantt.idsUnique' `
        -Detail ("duplicates={0}" -f $duplicateInGantt.Count)

    $sampleGantt = $ganttTasks | Where-Object { $_.operationIndex -eq 40 -and $_.orderNo -eq 'SO-20261104-0001' } | Select-Object -First 1
    Assert-True -Condition ($null -ne $sampleGantt) -Label 'gantt.sampleFound' `
        -Detail 'SO-20261104-0001 / op40'
    $sampleDb = $taskById[[string]$sampleGantt.id]
    Assert-True -Condition ($null -ne $sampleDb) -Label 'gantt.sampleInDb' `
        -Detail ("taskId={0}" -f $sampleGantt.id)
    Assert-True -Condition ($sampleGantt.orderNo -eq $sampleDb.OrderNo) -Label 'gantt.sample.orderNo' `
        -Detail ("gantt={0} db={1}" -f $sampleGantt.orderNo, $sampleDb.OrderNo)
    Assert-True -Condition ($sampleGantt.operationIndex -eq $sampleDb.OpIndex) -Label 'gantt.sample.opIndex' `
        -Detail ("gantt={0} db={1}" -f $sampleGantt.operationIndex, $sampleDb.OpIndex)
    Assert-True -Condition ($sampleGantt.resourceCode -eq $sampleDb.ResourceCode) -Label 'gantt.sample.resource' `
        -Detail ("gantt={0} db={1}" -f $sampleGantt.resourceCode, $sampleDb.ResourceCode)
    Assert-True -Condition ((Convert-IsoToEpoch $sampleGantt.startTime) -eq $sampleDb.Start) -Label 'gantt.sample.start' `
        -Detail ("epoch={0}" -f $sampleDb.Start)
    Assert-True -Condition ((Convert-IsoToEpoch $sampleGantt.endTime) -eq $sampleDb.End) -Label 'gantt.sample.end' `
        -Detail ("epoch={0}" -f $sampleDb.End)
    Assert-True -Condition ((Convert-IsoToEpoch $sampleGantt.setupStartTime) -eq $sampleDb.SetupStart) -Label 'gantt.sample.setupStart' `
        -Detail ("epoch={0}" -f $sampleDb.SetupStart)
    Assert-True -Condition ((Convert-IsoToEpoch $sampleGantt.setupEndTime) -eq $sampleDb.SetupEnd) -Label 'gantt.sample.setupEnd' `
        -Detail ("epoch={0}" -f $sampleDb.SetupEnd)
    Assert-True -Condition ([decimal]$sampleGantt.qty -eq $sampleDb.Qty) -Label 'gantt.sample.qty' `
        -Detail ("gantt={0} db={1}" -f $sampleGantt.qty, $sampleDb.Qty)
    Assert-True -Condition ($sampleGantt.status -eq $sampleDb.Status) -Label 'gantt.sample.status' `
        -Detail ("gantt={0} db={1}" -f $sampleGantt.status, $sampleDb.Status)
    Assert-True -Condition ($sampleGantt.sequenceInResource -eq $sampleDb.Seq) -Label 'gantt.sample.sequence' `
        -Detail ("gantt={0} db={1}" -f $sampleGantt.sequenceInResource, $sampleDb.Seq)
    $sampleSecondary = @($sampleGantt.secondaryResourceCodes)
    Assert-True -Condition ($sampleSecondary.Contains('RES-OP-01')) -Label 'gantt.sample.secondary' `
        -Detail ("secondaryCodes={0}" -f ($sampleSecondary -join ','))

    # --------------------------------------------------------------------
    # 8. re-run solve, no duplicate tasks
    # --------------------------------------------------------------------
    Write-Step '8/8 re-run solve: old task rows replaced, no duplicates'
    $count1 = $tasks.Count
    $getTasksBefore = $responseTaskCount
    $maxId1 = ($tasks | Measure-Object -Property Id -Maximum).Maximum
    Write-Host ("    before re-run: taskRows={0} maxTaskId={1}" -f $count1, $maxId1) -ForegroundColor DarkGray

    $solve2 = Invoke-Api -Method Post -Url "$BaseUrl/api/v1/solve-tasks" -Body $solveBody
    Assert-True -Condition ($solve2.Status -eq 200 -and $solve2.Json.code -eq 0) `
        -Label 'solve.run2.http' -Detail ("http={0} code={1}" -f $solve2.Status, $solve2.Json.code)
    Assert-True -Condition ([long]$solve2.Json.data.taskId -gt [long]$solve1.Json.data.taskId) `
        -Label 'solve.run2.taskId' -Detail ("taskId={0} previous={1}" -f $solve2.Json.data.taskId, $solve1.Json.data.taskId)
    $solve2Detail = Wait-SolveTerminal -TaskId ([long]$solve2.Json.data.taskId)
    Assert-True -Condition ($solve2Detail.status -eq 'SOLVED') `
        -Label 'solve.run2.status' -Detail ("status={0}" -f $solve2Detail.status)
    $count2Get = @($solve2Detail.tasks).Count
    $sandboxTaskCount2 = [int](Get-Scalar -Sql "SELECT count(*) FROM task WHERE tenant_id = '$TenantId' AND sandbox_id = '$SandboxId' AND deleted = false;")
    Assert-True -Condition ($count2Get -eq $getTasksBefore) `
        -Label 'rerun.getTasksStable' -Detail ("GET tasks before={0} after={1}" -f $getTasksBefore, $count2Get)
    Assert-True -Condition ($count2Get -eq $sandboxTaskCount2) `
        -Label 'rerun.getTasksEqSandboxRows' -Detail ("GET tasks={0} sandbox rows={1}" -f $count2Get, $sandboxTaskCount2)

    $solve2Row = @(Invoke-Psql -Sql @"
SELECT id, status, score_hard, metrics->>'durationMs'
FROM solve_task
WHERE tenant_id = '$TenantId' AND strategy_id = '$StrategyId'
ORDER BY id DESC LIMIT 1;
"@ | Where-Object { $_ -ne '' })
    $s2 = $solve2Row[0].Split('|')
    Write-Host ("    solve_task id={0} status={1} score_hard={2} durationMs={3}" -f $s2[0], $s2[1], $s2[2], $s2[3]) -ForegroundColor DarkGray
    Assert-True -Condition ($s2[1] -eq 'SOLVED') -Label 'solve.run2.solveTaskStatus' -Detail ("status={0}" -f $s2[1])

    $rawTask2 = @(Invoke-Psql -Sql $taskSql | Where-Object { $_ -ne '' })
    $count2 = $rawTask2.Count
    Assert-True -Condition ($count2 -eq $count1) -Label 'rerun.taskCountStable' `
        -Detail ("before={0} after={1}" -f $count1, $count2)

    $oldRows = Get-Scalar -Sql @"
SELECT count(*) FROM task t JOIN orders o ON o.id = t.order_id
WHERE t.tenant_id = '$TenantId' AND t.sandbox_id = '$SandboxId' AND t.deleted = false
  AND o.tenant_id = '$TenantId' AND o.order_no IN ($validOrderSql)
  AND t.id <= $maxId1;
"@
    Assert-True -Condition ([int]$oldRows -eq 0) -Label 'rerun.oldRowsRemoved' `
        -Detail ("rowsWithId<=$maxId1 = {0}" -f $oldRows)

    $newMaxId = Get-Scalar -Sql @"
SELECT COALESCE(max(t.id), 0) FROM task t JOIN orders o ON o.id = t.order_id
WHERE t.tenant_id = '$TenantId' AND t.sandbox_id = '$SandboxId' AND t.deleted = false
  AND o.tenant_id = '$TenantId' AND o.order_no IN ($validOrderSql);
"@
    Assert-True -Condition ([long]$newMaxId -gt [long]$maxId1) -Label 'rerun.newIds' `
        -Detail ("newMaxId={0} oldMaxId={1}" -f $newMaxId, $maxId1)

    $dupPairs = Get-Scalar -Sql @"
SELECT count(*) FROM (
  SELECT o.order_no, t.operation_index, count(*) AS c
  FROM task t JOIN orders o ON o.id = t.order_id
  WHERE t.tenant_id = '$TenantId' AND t.sandbox_id = '$SandboxId' AND t.deleted = false
    AND o.tenant_id = '$TenantId' AND o.order_no IN ($validOrderSql)
  GROUP BY o.order_no, t.operation_index
  HAVING count(*) > 1
) dup;
"@
    Assert-True -Condition ([int]$dupPairs -eq 0) -Label 'rerun.noDuplicatePairs' `
        -Detail ("duplicate(order,op) groups={0}" -f $dupPairs)

    Write-Host ''
    Write-Host ("ALL PASS: {0} assertions" -f $script:PassCount) -ForegroundColor Green
    Write-Host ("first solve: taskRows={0}, second solve: taskRows={1}, newMaxTaskId={2}" -f $count1, $count2, $newMaxId) -ForegroundColor Green
}
catch {
    Write-Host ''
    Write-Host ("FAILED: {0}" -f $_.Exception.Message) -ForegroundColor Red
    $exitCode = 1
}
finally {
    if ($script:StartedBackend -and -not $KeepBackend -and $null -ne $script:StartedProcess) {
        try {
            Stop-Process -Id $script:StartedProcess.Id -Force -ErrorAction Stop
            Write-Host ("Backend stopped (PID={0}). Seed data is kept." -f $script:StartedProcess.Id) -ForegroundColor Yellow
        }
        catch {
            Write-Host ("WARN: failed to stop backend PID={0}: {1}" -f $script:StartedProcess.Id, $_.Exception.Message) -ForegroundColor Yellow
        }
    }
    elseif ($script:StartedBackend -and $KeepBackend) {
        Write-Host ("KeepBackend: backend left running (PID={0})." -f $script:StartedProcess.Id) -ForegroundColor Yellow
    }
    Write-Host 'Cleanup demo data: .\scripts\seed-m1-demo.ps1 -Clean'
}

exit $exitCode
