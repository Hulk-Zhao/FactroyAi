#requires -Version 5.1
<#
.SYNOPSIS
    M2-SE-05 软约束归因（justification）闭环验证脚本。

.DESCRIPTION
    流程：造一个独立空沙箱 -> 选一个 8 条规则且权重合计 100 的启用策略 -> 取少量已确认订单 ->
    POST /solve-tasks 求解 -> 轮询到终态 -> GET /solve-tasks/{id}/justifications ->
    汇总 deduction 并与 bestScore 的软分比对 -> 无论成功失败都清理本次沙箱的全部数据。

    数据来源说明：沙箱由脚本自建；策略与订单复用基线种子数据（不新建主数据）。
    脚本每次使用随机沙箱 UUID，因此可重复执行；结束后按 sandbox_id 删除
    task_adjustment / task / plan_version / solve_task / sandbox，并按 solve_task 删除 justification。

.PARAMETER BaseUrl
    运行中包含 M2-SE-05 代码的后端地址，默认 http://127.0.0.1:18085。

.PARAMETER ApiKey
    写端点 API Key（aps.security.mode=dev 时需要）。
    目标实例必须显式开启 aps.security.dev.api-key-enabled=true（默认关闭）；
    本仓库统一由 scripts/local-verify.ps1 以启动参数传入，默认密钥 aps-dev-write-key-local-only。

.PARAMETER PgHost/PgPort/PgDatabase/PgUser/PgPassword
    共享库连接参数，默认 aps_local:55432。

.PARAMETER PsqlPath
    psql 可执行文件路径，默认工具链内置路径。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/verify-se5-justification.ps1 -BaseUrl http://127.0.0.1:18092
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:18085',
    [string]$ApiKey = $(if ($env:APS_DEV_API_KEY) { $env:APS_DEV_API_KEY } else { 'aps-dev-write-key-local-only' }),
    [string]$PgHost = '127.0.0.1',
    [int]$PgPort = 55432,
    [string]$PgDatabase = 'aps_local',
    [string]$PgUser = 'aps',
    [string]$PgPassword = 'aps_dev_pwd',
    [string]$PsqlPath = 'D:\tools\_aps-toolchain\pgsql\bin\psql.exe',
    [int]$SolveTimeoutSeconds = 20,
    [int]$PollLimitSeconds = 90
)

$ErrorActionPreference = 'Stop'
$env:PGPASSWORD = $PgPassword
$sandboxId = [guid]::NewGuid().ToString()
$solveTaskId = $null

if (-not (Test-Path $PsqlPath)) {
    throw "psql 不存在：$PsqlPath（请用 -PsqlPath 指定）"
}

function Invoke-Psql([string]$sql) {
    $out = & $PsqlPath -h $PgHost -p $PgPort -U $PgUser -d $PgDatabase -t -A -v ON_ERROR_STOP=1 -c $sql
    if ($LASTEXITCODE -ne 0) {
        throw "psql 执行失败：$sql`n$out"
    }
    return ($out | Out-String).Trim()
}

function Invoke-Api([string]$method, [string]$path, $body) {
    $params = @{
        Uri         = "$BaseUrl$path"
        Method      = $method
        TimeoutSec  = 120
        UseBasicParsing = $true
    }
    if ($method -ne 'GET') {
        $params.Headers = @{ 'X-API-Key' = $ApiKey }
    }
    if ($null -ne $body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 5))
    }
    $response = Invoke-WebRequest @params
    return ($response.Content | ConvertFrom-Json)
}

function Remove-Fixture {
    if ($null -eq $script:solveTaskId) {
        $solveIds = Invoke-Psql "select id from solve_task where sandbox_id = '$sandboxId'"
        if ([string]::IsNullOrWhiteSpace($solveIds)) {
            $solveIds = ''
        } else {
            $solveIds = (($solveIds -split "`n") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' }) -join ','
        }
    } else {
        $solveIds = [string]$script:solveTaskId
    }
    if (-not [string]::IsNullOrWhiteSpace($solveIds)) {
        Invoke-Psql "delete from constraint_justification where solve_task_id in ($solveIds)" | Out-Null
    }
    Invoke-Psql "delete from task_adjustment where sandbox_id = '$sandboxId'" | Out-Null
    Invoke-Psql "delete from task where sandbox_id = '$sandboxId'" | Out-Null
    Invoke-Psql "delete from plan_version where sandbox_id = '$sandboxId'" | Out-Null
    Invoke-Psql "delete from solve_task where sandbox_id = '$sandboxId'" | Out-Null
    Invoke-Psql "delete from sandbox where id = '$sandboxId'" | Out-Null
}

try {
    Write-Host "[1/7] 建独立空沙箱 $sandboxId"
    $tenantId = Invoke-Psql "select id from tenant limit 1"
    if ([string]::IsNullOrWhiteSpace($tenantId)) {
        throw '租户不存在，无法造数'
    }
    Invoke-Psql ("insert into sandbox (id, tenant_id, name, type, snapshot) " +
        "values ('$sandboxId', '$tenantId', 'SE5-VERIFY', 'WHAT_IF', '{}'::jsonb)") | Out-Null

    Write-Host '[2/7] 选择策略（8 条规则且权重合计 100）'
    $strategyId = Invoke-Psql ("select s.id from strategy s join strategy_rule r " +
        "on r.strategy_id = s.id and r.deleted = false " +
        "where s.deleted = false group by s.id having count(*) = 8 and sum(r.weight) = 100 " +
        "order by max(s.created_at) desc limit 1")
    if ([string]::IsNullOrWhiteSpace($strategyId)) {
        throw '未找到 8 条规则且权重合计 100 的启用策略'
    }

    Write-Host '[3/7] 取已确认订单（按交期前 5 条）'
    $orderLines = Invoke-Psql "select order_no from orders where status = 'CONFIRMED' and deleted = false order by due_date, order_no limit 5"
    $orderNos = @(($orderLines -split "`n") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' })
    if ($orderNos.Count -eq 0) {
        throw '没有 CONFIRMED 订单，无法求解'
    }
    Write-Host ("      订单：" + ($orderNos -join ', '))

    Write-Host '[4/7] 提交求解'
    $submit = Invoke-Api 'POST' '/api/v1/solve-tasks' @{
        taskNo     = "SE5-VERIFY-$($sandboxId.Substring(0, 8))"
        sandboxId  = $sandboxId
        strategyId = $strategyId
        engine     = 'timefold'
        orderNos   = $orderNos
        parameters = @{ timeoutSeconds = $SolveTimeoutSeconds }
    }
    $script:solveTaskId = $submit.data.taskId

    Write-Host "[5/7] 轮询求解任务 $($script:solveTaskId)"
    $detail = $null
    for ($i = 0; $i -lt $PollLimitSeconds; $i += 2) {
        Start-Sleep -Seconds 2
        $detail = (Invoke-Api 'GET' "/api/v1/solve-tasks/$($script:solveTaskId)" $null).data
        if ($detail.status -in @('SOLVED', 'FAILED', 'CANCELLED', 'TIMEOUT')) {
            break
        }
    }
    if ($null -eq $detail) {
        throw '轮询超时，未取得求解任务详情'
    }
    Write-Host ("      status={0} scoreSoft={1} elapsedMs={2}" -f $detail.status, $detail.scoreSoft, $detail.elapsedMs)
    if ($detail.status -ne 'SOLVED') {
        throw "求解未成功：status=$($detail.status) errorCode=$($detail.errorCode)"
    }

    Write-Host '[6/7] 查询 justification'
    $justifications = (Invoke-Api 'GET' "/api/v1/solve-tasks/$($script:solveTaskId)/justifications" $null).data
    $rows = @($justifications)
    $sum = [decimal]0
    foreach ($row in $rows) {
        if ($null -ne $row.deduction) {
            $sum += [decimal]$row.deduction
        }
    }
    Write-Host ("      归因行数={0}，deduction 之和={1}" -f $rows.Count, $sum)
    foreach ($row in $rows) {
        Write-Host ("      - {0} weight={1} deduction={2}" -f $row.constraintCode, $row.weight, $row.deduction)
    }

    $softScore = [decimal]$detail.scoreSoft
    if ($sum -eq (-1 * $softScore)) {
        Write-Host ("各条 deduction 之和 = {0}，bestScore soft = {1}，一致" -f $sum, $softScore)
    } else {
        Write-Host ("各条 deduction 之和 = {0}，bestScore soft = {1}，不一致" -f $sum, $softScore)
        throw "归因扣分与软分不一致：sum=$sum softScore=$softScore"
    }
} finally {
    $wasSuccessful = ($null -ne $detail) -and ($detail.status -eq 'SOLVED')
    Write-Host '[7/7] 清理本次沙箱数据'
    try {
        Remove-Fixture
    } catch {
        Write-Warning "清理失败，请手工按 sandbox_id=$sandboxId 清理：$($_.Exception.Message)"
    }
    $leftTasks = Invoke-Psql "select count(*) from task where sandbox_id = '$sandboxId'"
    $leftJustifications = Invoke-Psql ("select count(*) from constraint_justification c " +
        "join solve_task s on s.id = c.solve_task_id where s.sandbox_id = '$sandboxId'")
    $leftSandbox = Invoke-Psql "select count(*) from sandbox where id = '$sandboxId'"
    Write-Host ("      清理后残留：task={0} justification={1} sandbox={2}" -f $leftTasks, $leftJustifications, $leftSandbox)
    if (-not $wasSuccessful) {
        Write-Host '各条 deduction 之和 = 未取得，bestScore soft = 未取得，不一致（求解未成功）'
    }
}
