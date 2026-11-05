#Requires -Version 5.1
<#
.SYNOPSIS
    高负载排产数据集生成（500 订单 / 50 资源 / 多工序路线 / 工作日历+停机窗口）。

.DESCRIPTION
    只写独立压测库（默认 aps_qa_perf），不触碰 aps_local 及他人取证库。
    脚本通过 psql 直连数据库写入，全部 UUID 固定，清理使用精确号段与专属 sandbox，
    不使用业务码前缀 LIKE 清理，可重复执行（幂等重建）。

    数据规模（默认 -OrderCount 500）：
      material/category : 10 个成品（MAT-FG-101..110）+ 10 个产品族（CAT-FAM-101..110）
      routing          : 每个成品 1 条 ACTIVE 路线（V1.0），6 道工序
                         （数控粗加工 -> 数控精加工 -> 热处理 -> 总装 -> 质量检验(辅) -> 包装）
      resource         : 50 个（RES-CNC-11..30 / RES-HEAT-11..15 / RES-ASSY-11..25 /
                         RES-QC-11..15 / RES-OP-11..15）
      calendar         : 1 个全厂日历（CAL-FAC-11）+ 5 个热处理资源专属日历
                         （CAL-RES-HEAT-11..15，含额外停机日）
      operation        : 8 个工序模板（OP-CNC-11/12/13、OP-HEAT-11、
                         OP-ASSY-11/12/13、OP-QC-11）
      strategy         : 1 个策略「均衡排产策略」+ 8 条软规则（权重合计 100）
      orders           : OrderCount 个 CONFIRMED 订单（SO-20261104-1001..），交期未来 2~8 天
      task（求解后）   : OrderCount × 6 行

    前置条件：目标库已由应用启动时 Flyway 建表（本脚本不代跑迁移，避免与 Flyway 历史冲突）。

.PARAMETER OrderCount
    订单数量，默认 500。
.PARAMETER Clean
    仅清理压测数据，不重建。
.PARAMETER DbName
    目标库，默认 aps_qa_perf（禁止填 aps_local，脚本会直接拒绝）。
#>
[CmdletBinding()]
param(
    [int]$OrderCount = 500,
    [string]$Toolchain = $(if ($env:APS_TOOLCHAIN) { $env:APS_TOOLCHAIN } else { 'D:\tools\_aps-toolchain' }),
    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 55432 }),
    [string]$DbName = 'aps_qa_perf',
    [string]$DbUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'aps' }),
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'aps_dev_pwd' }),
    [string]$TenantId = '00000000-0000-0000-0000-000000000001',
    [switch]$Clean,
    [switch]$SkipDbCheck
)

$ErrorActionPreference = 'Stop'
$psql = Join-Path $Toolchain 'pgsql\bin\psql.exe'

if ($DbName -eq 'aps_local' -or $DbName -like 'aps_local*') {
    throw "禁止向 $DbName 写入压测数据（隔离要求：请使用独立库，如 aps_qa_perf）"
}
if (-not (Test-Path $psql)) {
    throw "psql not found: $psql"
}

$env:PGPASSWORD = $DbPassword
$tenant = $TenantId
# 专属 sandbox：与 M1 基线 000...401 分离，清理时不会碰到他人任务
$sandbox = 'e9000000-0000-0000-0000-000000000401'
$strategy = 'e9000000-0000-0000-0000-000000000001'
$factoryCal = 'e9000000-0000-0000-0000-000000000201'
$orderRemark = '高负载推演数据集'

function Uuid([string]$prefix, [int]$n) {
    return ('{0}-0000-0000-0000-{1:x12}' -f $prefix, $n)
}

function Write-Step([string]$Message) { Write-Host "[..] $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message) { Write-Host "[OK] $Message" -ForegroundColor Green }

# ---------------------------------------------------------------------------
# 表结构检查（Flyway 必须先完成）
# ---------------------------------------------------------------------------
$tableCount = 0
if (-not $SkipDbCheck) {
    $raw = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A `
        -c "select count(*) from information_schema.tables where table_schema='public';"
    if ($LASTEXITCODE -ne 0) { throw "无法连接 $DbHost`:$DbPort/$DbName（库是否已创建？）" }
    $tableCount = [int]($raw.Trim())
}
if ($tableCount -eq 0) {
    throw "库 $DbName 为空表结构；请先启动 aps-admin（dev profile，Flyway 开启）完成 V1~V7 迁移后再执行本脚本"
}

# ---------------------------------------------------------------------------
# 清理（精确号段 + 专属 sandbox，幂等）
# ---------------------------------------------------------------------------
$cleanupSql = @"
BEGIN;
DELETE FROM constraint_justification cj
WHERE cj.tenant_id = '$tenant'
  AND cj.solve_task_id IN (SELECT id FROM solve_task WHERE tenant_id = '$tenant' AND sandbox_id = '$sandbox');
DELETE FROM task WHERE tenant_id = '$tenant' AND sandbox_id = '$sandbox';
DELETE FROM task_adjustment WHERE tenant_id = '$tenant' AND sandbox_id = '$sandbox';
DELETE FROM plan_version WHERE tenant_id = '$tenant' AND sandbox_id = '$sandbox';
DELETE FROM solve_task WHERE tenant_id = '$tenant' AND sandbox_id = '$sandbox';
-- 订单必须先于 routing 删除：orders.routing_id 外键指向 routing（fk_orders_routing），
-- 先删 routing 会让同库二次造数直接失败（不幂等）。
DELETE FROM orders WHERE tenant_id = '$tenant' AND remark = '$orderRemark'
  AND product_id IN (
      SELECT id FROM material WHERE tenant_id = '$tenant' AND code >= 'MAT-FG-101' AND code <= 'MAT-FG-110');
DELETE FROM routing_edge WHERE tenant_id = '$tenant' AND routing_id IN (
    SELECT r.id FROM routing r JOIN material m ON m.id = r.product_id
    WHERE r.tenant_id = '$tenant' AND m.code >= 'MAT-FG-101' AND m.code <= 'MAT-FG-110');
DELETE FROM routing_node WHERE tenant_id = '$tenant' AND routing_id IN (
    SELECT r.id FROM routing r JOIN material m ON m.id = r.product_id
    WHERE r.tenant_id = '$tenant' AND m.code >= 'MAT-FG-101' AND m.code <= 'MAT-FG-110');
DELETE FROM routing WHERE tenant_id = '$tenant' AND product_id IN (
    SELECT id FROM material WHERE tenant_id = '$tenant' AND code >= 'MAT-FG-101' AND code <= 'MAT-FG-110');
DELETE FROM strategy_rule WHERE tenant_id = '$tenant' AND strategy_id = '$strategy';
DELETE FROM strategy WHERE id = '$strategy';
DELETE FROM operation_template WHERE tenant_id = '$tenant' AND code IN (
    'OP-CNC-11','OP-CNC-12','OP-CNC-13','OP-HEAT-11',
    'OP-ASSY-11','OP-ASSY-12','OP-ASSY-13','OP-QC-11');
UPDATE resource SET calendar_id = NULL WHERE tenant_id = '$tenant' AND (
    (code >= 'RES-CNC-11' AND code <= 'RES-CNC-30') OR
    (code >= 'RES-HEAT-11' AND code <= 'RES-HEAT-15') OR
    (code >= 'RES-ASSY-11' AND code <= 'RES-ASSY-25') OR
    (code >= 'RES-QC-11' AND code <= 'RES-QC-15') OR
    (code >= 'RES-OP-11' AND code <= 'RES-OP-15'));
DELETE FROM calendar WHERE tenant_id = '$tenant' AND code IN (
    'CAL-FAC-11','CAL-RES-HEAT-11','CAL-RES-HEAT-12','CAL-RES-HEAT-13',
    'CAL-RES-HEAT-14','CAL-RES-HEAT-15');
DELETE FROM resource WHERE tenant_id = '$tenant' AND (
    (code >= 'RES-CNC-11' AND code <= 'RES-CNC-30') OR
    (code >= 'RES-HEAT-11' AND code <= 'RES-HEAT-15') OR
    (code >= 'RES-ASSY-11' AND code <= 'RES-ASSY-25') OR
    (code >= 'RES-QC-11' AND code <= 'RES-QC-15') OR
    (code >= 'RES-OP-11' AND code <= 'RES-OP-15'));
DELETE FROM sandbox WHERE tenant_id = '$tenant' AND id = '$sandbox';
DELETE FROM material WHERE tenant_id = '$tenant' AND code >= 'MAT-FG-101' AND code <= 'MAT-FG-110';
DELETE FROM material_category WHERE tenant_id = '$tenant' AND code >= 'CAT-FAM-101' AND code <= 'CAT-FAM-110';
COMMIT;
"@

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$tempSql = Join-Path ([System.IO.Path]::GetTempPath()) ("aps-perf-seed-{0}.sql" -f ([guid]::NewGuid().ToString('N')))

try {
    Write-Step "清理旧压测数据（租户 $TenantId / sandbox $sandbox）"
    [System.IO.File]::WriteAllText($tempSql, $cleanupSql, $utf8NoBom)
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q -f $tempSql
    if ($LASTEXITCODE -ne 0) { throw "cleanup SQL failed: $LASTEXITCODE" }

    if ($Clean) { Write-Ok 'Clean 完成（未重建）'; return }

    # -----------------------------------------------------------------------
    # 数据集构造
    # -----------------------------------------------------------------------
    $buildStart = Get-Date
    $today = (Get-Date).Date
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('BEGIN;')

    # sandbox
    [void]$sb.AppendLine(@"
INSERT INTO sandbox (id, tenant_id, name, type, snapshot, version)
VALUES ('$sandbox', '$tenant', '高负载推演场景', 'WHAT_IF', '{}'::jsonb, 1);
"@)

    # 产品族 + 成品（MAT-FG-101..110 / CAT-FAM-101..110）
    $specs = @(
        '48V 10Ah', '48V 15Ah', '48V 20Ah', '48V 25Ah', '51.2V 10Ah',
        '51.2V 15Ah', '51.2V 20Ah', '51.2V 25Ah', '76.8V 15Ah', '76.8V 20Ah'
    )
    for ($p = 1; $p -le 10; $p++) {
        $cat = Uuid 'e9000000' (900 + $p)
        $mat = Uuid 'e9000000' (800 + $p)
        $pn = 100 + $p
        $spec = $specs[$p - 1]
        [void]$sb.AppendLine("INSERT INTO material_category (id, tenant_id, code, name, sort_no, version) VALUES ('$cat', '$tenant', 'CAT-FAM-{0:d3}', '储能产品族 {0:d3}', $p, 1);" -f $pn)
        [void]$sb.AppendLine("INSERT INTO material (id, tenant_id, code, name, type, unit, spec, category_id, ext_attrs) VALUES ('$mat', '$tenant', 'MAT-FG-{0:d3}', '储能电芯模组 P-{0:d3}', 'FINISHED', 'PCS', '$spec', '$cat', '{{}}'::jsonb);" -f $pn)
    }

    # 日历：全厂（周一~周六三班）+ 交期窗口内停机日
    $holidayFactory = $today.AddDays(3).ToString('yyyy-MM-dd')
    [void]$sb.AppendLine(@"
INSERT INTO calendar (id, tenant_id, code, name, type, effective_from, shifts, holidays, calendar_version, version)
VALUES ('$factoryCal', '$tenant', 'CAL-FAC-11', '高负载全厂日历', 'FACTORY', DATE '2026-01-01',
        '[{"startTime":"00:00","endTime":"24:00","daysOfWeek":[1,2,3,4,5,6]}]'::jsonb,
        '[{"date":"$holidayFactory","name":"全厂停机保养","kind":"COMPANY"}]'::jsonb, 1, 1);
"@)
    # 5 个热处理资源专属日历（额外停机窗口）
    for ($h = 1; $h -le 5; $h++) {
        $cal = Uuid 'e9000000' (200 + $h)
        $hn = 10 + $h
        $downtime = $today.AddDays(5).ToString('yyyy-MM-dd')
        $heatCalSql = "INSERT INTO calendar (id, tenant_id, code, name, type, effective_from, shifts, holidays, calendar_version, version) " +
            "VALUES ('$cal', '$tenant', 'CAL-RES-HEAT-" + ('{0:d2}' -f $hn) + "', '热处理炉 " + ('{0:d2}' -f $hn) + " 专属日历', 'RESOURCE', DATE '2026-01-01', " +
            "'[{""startTime"":""00:00"",""endTime"":""24:00"",""daysOfWeek"":[1,2,3,4,5,6]}]'::jsonb, " +
            "'[{""date"":""$downtime"",""name"":""热处理炉停机检修"",""kind"":""COMPANY""}]'::jsonb, 1, 1);"
        [void]$sb.AppendLine($heatCalSql)
    }

    # 资源：50 个（CNC20 / HEAT5 / ASSY15 / QC5 / OP5）
    $resIds = @{}
    for ($i = 1; $i -le 20; $i++) {
        $id = Uuid 'e9000000' (1000 + $i)
        $resIds["CNC-$i"] = $id
        $rn = 10 + $i
        $cap = 4 + ($i % 5)
        [void]$sb.AppendLine(("INSERT INTO resource (id, tenant_id, code, name, type, capacity_mode, capacity_value, batch_capacity, efficiency, calendar_id, setup_seconds, teardown_seconds, maintenance_plan, industry_attrs) VALUES ('$id', '$tenant', 'RES-CNC-{0:d2}', '数控加工中心 {1} 号', 'PRIMARY', 'UNIT_PER_HOUR', $cap, NULL, 1.00, '$factoryCal', 0, 0, '{{}}'::jsonb, '{{}}'::jsonb);" -f $rn, $rn))
    }
    for ($i = 1; $i -le 5; $i++) {
        $id = Uuid 'e9000000' (1020 + $i)
        $resIds["HEAT-$i"] = $id
        $rn = 10 + $i
        $cal = Uuid 'e9000000' (200 + $i)
        [void]$sb.AppendLine(("INSERT INTO resource (id, tenant_id, code, name, type, capacity_mode, capacity_value, batch_capacity, efficiency, calendar_id, setup_seconds, teardown_seconds, maintenance_plan, industry_attrs) VALUES ('$id', '$tenant', 'RES-HEAT-{0:d2}', '热处理炉 {1} 号', 'PRIMARY', 'BATCH_SECONDS', 900, 10, 1.00, '$cal', 0, 0, '{{}}'::jsonb, '{{}}'::jsonb);" -f $rn, $rn))
    }
    for ($i = 1; $i -le 15; $i++) {
        $id = Uuid 'e9000000' (1040 + $i)
        $resIds["ASM-$i"] = $id
        $rn = 10 + $i
        $cap = 5 + ($i % 4)
        [void]$sb.AppendLine(("INSERT INTO resource (id, tenant_id, code, name, type, capacity_mode, capacity_value, batch_capacity, efficiency, calendar_id, setup_seconds, teardown_seconds, maintenance_plan, industry_attrs) VALUES ('$id', '$tenant', 'RES-ASSY-{0:d2}', '总装线 {1} 号', 'PRIMARY', 'UNIT_PER_HOUR', $cap, NULL, 1.00, '$factoryCal', 0, 0, '{{}}'::jsonb, '{{}}'::jsonb);" -f $rn, $rn))
    }
    for ($i = 1; $i -le 5; $i++) {
        $id = Uuid 'e9000000' (1060 + $i)
        $resIds["QA-$i"] = $id
        $rn = 10 + $i
        [void]$sb.AppendLine(("INSERT INTO resource (id, tenant_id, code, name, type, capacity_mode, capacity_value, batch_capacity, efficiency, calendar_id, setup_seconds, teardown_seconds, maintenance_plan, industry_attrs) VALUES ('$id', '$tenant', 'RES-QC-{0:d2}', '质检工位 {1} 号', 'SECONDARY', 'SECONDS_PER_UNIT', 0, NULL, 1.00, '$factoryCal', 0, 0, '{{}}'::jsonb, '{{}}'::jsonb);" -f $rn, $rn))
    }
    for ($i = 1; $i -le 5; $i++) {
        $id = Uuid 'e9000000' (1080 + $i)
        $resIds["HUMAN-$i"] = $id
        $rn = 10 + $i
        [void]$sb.AppendLine(("INSERT INTO resource (id, tenant_id, code, name, type, capacity_mode, capacity_value, batch_capacity, efficiency, calendar_id, setup_seconds, teardown_seconds, maintenance_plan, industry_attrs) VALUES ('$id', '$tenant', 'RES-OP-{0:d2}', '操作工 {1} 号', 'HUMAN', 'SECONDS_PER_UNIT', 0, NULL, 1.00, '$factoryCal', 0, 0, '{{}}'::jsonb, '{{}}'::jsonb);" -f $rn, $rn))
    }

    # 工序模板 8 个
    $tplCodes = @('', 'OP-CNC-11', 'OP-CNC-12', 'OP-CNC-13', 'OP-HEAT-11', 'OP-ASSY-11', 'OP-ASSY-12', 'OP-ASSY-13', 'OP-QC-11')
    $tplNames = @('', '数控粗加工', '数控精加工', '数控去毛刺', '热处理', '总装', '包装入库', '返修', '质量检验')
    for ($t = 1; $t -le 8; $t++) {
        $id = Uuid 'e9000000' (1200 + $t)
        $code = $tplCodes[$t]
        $name = $tplNames[$t]
        [void]$sb.AppendLine("INSERT INTO operation_template (id, tenant_id, code, name, primary_resource_id, primary_resource_group_id, secondary_resource_ids, input_material_ids, output_material_id, standard_seconds, setup_seconds, teardown_seconds, template_version, version) VALUES ('$id', '$tenant', '$code', '$name', NULL, NULL, '[]'::jsonb, '[]'::jsonb, NULL, 60, 30, 0, 1, 1);")
    }

    # 每个成品 1 条 ACTIVE 路线 + 6 节点 + 5 条 FS 边
    $opSeq = @(
        @{ Key = 'n10'; Tpl = 1; Sec = 120; Setup = 60;  Kind = 'CNC' },
        @{ Key = 'n20'; Tpl = 2; Sec = 180; Setup = 120; Kind = 'CNC' },
        @{ Key = 'n30'; Tpl = 3; Sec = 900; Setup = 300; Kind = 'HEAT' },
        @{ Key = 'n40'; Tpl = 4; Sec = 240; Setup = 45;  Kind = 'ASM' },
        @{ Key = 'n50'; Tpl = 5; Sec = 120; Setup = 0;   Kind = 'QA' },
        @{ Key = 'n60'; Tpl = 6; Sec = 90;  Setup = 30;  Kind = 'ASM' }
    )
    for ($p = 1; $p -le 10; $p++) {
        $routing = Uuid 'e9000000' (500 + $p)
        $mat = Uuid 'e9000000' (800 + $p)
        [void]$sb.AppendLine("INSERT INTO routing (id, tenant_id, product_id, routing_version, effective_date, expiry_date, status, is_panel, panel_size, nodes, edges, version) VALUES ('$routing', '$tenant', '$mat', 'V1.0', DATE '2026-01-01', NULL, 'ACTIVE', false, NULL, '[]'::jsonb, '[]'::jsonb, 1);")

        $nodeIds = @()
        for ($n = 0; $n -lt $opSeq.Count; $n++) {
            $op = $opSeq[$n]
            $nodeId = Uuid 'e9000000' (2000 + $p * 10 + $n)
            $nodeIds += $nodeId
            $tplId = Uuid 'e9000000' (1200 + $op.Tpl)
            $seq = ($n + 1) * 10
            if ($op.Kind -eq 'CNC') {
                $idx = (($p * 2 + $n) % 20) + 1
                $ov = '{"primaryResourceId":"' + $resIds["CNC-$idx"] + '"}'
            } elseif ($op.Kind -eq 'HEAT') {
                $idx = ($p % 5) + 1
                $ov = '{"primaryResourceId":"' + $resIds["HEAT-$idx"] + '"}'
            } elseif ($op.Kind -eq 'ASM') {
                $idx = (($p * 2 + $n) % 15) + 1
                $ov = '{"primaryResourceId":"' + $resIds["ASM-$idx"] + '"}'
            } else {
                $asmIdx = (($p * 2 + $n) % 15) + 1
                $qaIdx = ($p % 5) + 1
                $humanIdx = ($p % 5) + 1
                $ov = '{"primaryResourceId":"' + $resIds["ASM-$asmIdx"] + '","secondaryResourceIds":["' +
                    $resIds["QA-$qaIdx"] + '","' + $resIds["HUMAN-$humanIdx"] + '"]}'
            }
            $oc = $tplCodes[$op.Tpl]
            $on = $tplNames[$op.Tpl]
            [void]$sb.AppendLine("INSERT INTO routing_node (id, tenant_id, routing_id, node_key, operation_template_id, operation_code, operation_name, standard_seconds, setup_seconds, teardown_seconds, is_optional, overrides, position_x, position_y, seq_no, version) VALUES ('$nodeId', '$tenant', '$routing', '$($op.Key)', '$tplId', '$oc', '$on', $($op.Sec), $($op.Setup), 0, false, '$ov'::jsonb, $((($n + 1) * 120)), 100, $seq, 1);")
        }
        for ($e = 0; $e -lt 5; $e++) {
            $edgeId = Uuid 'e9000000' (2200 + $p * 10 + $e)
            $lag = if ($e -eq 1) { 30 } else { 0 }
            [void]$sb.AppendLine("INSERT INTO routing_edge (id, tenant_id, routing_id, from_node_id, to_node_id, depend_type, lag_minutes, version) VALUES ('$edgeId', '$tenant', '$routing', '$($nodeIds[$e])', '$($nodeIds[$e + 1])', 'FS', $lag, 1);")
        }
    }

    # 策略 + 8 条软规则（权重合计 100，激活软约束）
    [void]$sb.AppendLine("INSERT INTO strategy (id, tenant_id, name, direction, strategy_version, initial_rules, optimization_rules, effective_from, effective_to, apply_condition, version) VALUES ('$strategy', '$tenant', '均衡排产策略', 'FORWARD', 1, '{}'::jsonb, '{}'::jsonb, DATE '2026-01-01', NULL, '{}'::jsonb, 1);")
    $rules = @(
        @{ Code = 'MIN_TARDINESS'; Weight = 20 }, @{ Code = 'EDD'; Weight = 15 }, @{ Code = 'SPT'; Weight = 10 },
        @{ Code = 'SAME_PRODUCT_CONTINUOUS'; Weight = 10 }, @{ Code = 'MIN_SETUP_TIME'; Weight = 10 },
        @{ Code = 'BALANCE_LOAD'; Weight = 10 }, @{ Code = 'PRIORITY_RESPECT'; Weight = 15 }, @{ Code = 'MIN_MAKESPAN'; Weight = 10 }
    )
    for ($r = 0; $r -lt $rules.Count; $r++) {
        $rid = Uuid 'e9000000' (1300 + $r)
        $rule = $rules[$r]
        [void]$sb.AppendLine("INSERT INTO strategy_rule (id, tenant_id, strategy_id, rule_code, name, rule_phase, rule_type, weight, params, enabled, seq_no, version) VALUES ('$rid', '$tenant', '$strategy', '$($rule.Code)', '$($rule.Code)', 'OPTIMIZATION', 'SOFT', $($rule.Weight), '{}'::jsonb, true, $($r + 1), 1);")
    }

    # 订单：OrderCount 个 CONFIRMED，交期 today+2..+8，编号固定基准日 20261104 + 批量号段
    $priorities = @('URGENT', 'HIGH', 'NORMAL', 'LOW')
    for ($o = 1; $o -le $OrderCount; $o++) {
        $id = Uuid 'e1000000' $o
        $product = (($o - 1) % 10) + 1
        $mat = Uuid 'e9000000' (800 + $product)
        $routing = Uuid 'e9000000' (500 + $product)
        $qty = (($o - 1) % 20) + 1
        $due = $today.AddDays(2 + (($o - 1) % 7)).ToString('yyyy-MM-dd')
        $prio = $priorities[($o - 1) % 4]
        $ono = 1000 + $o
        [void]$sb.AppendLine("INSERT INTO orders (id, tenant_id, order_no, source, product_id, qty, due_date, priority, routing_id, status, freeze_status, remark, ext_attrs, version) VALUES ('$id', '$tenant', 'SO-20261104-{0:d4}', 'MANUAL', '$mat', $qty, DATE '$due', '$prio', '$routing', 'CONFIRMED', 'UNFROZEN', '$orderRemark', '{{}}'::jsonb, 1);" -f $ono)
    }
    [void]$sb.AppendLine('COMMIT;')

    Write-Step "写入 $OrderCount 订单数据集"
    [System.IO.File]::WriteAllText($tempSql, $sb.ToString(), $utf8NoBom)
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q -f $tempSql
    if ($LASTEXITCODE -ne 0) { throw "seed SQL failed: $LASTEXITCODE" }
    $elapsed = ((Get-Date) - $buildStart).TotalMilliseconds
}
finally {
    Remove-Item $tempSql -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------------------
# 规模实测
# ---------------------------------------------------------------------------
$summarySql = @"
SELECT 'orders', count(*) FROM orders WHERE tenant_id = '$tenant' AND remark = '$orderRemark'
UNION ALL SELECT 'materials', count(*) FROM material WHERE tenant_id = '$tenant' AND code >= 'MAT-FG-101' AND code <= 'MAT-FG-110'
UNION ALL SELECT 'resources', count(*) FROM resource WHERE tenant_id = '$tenant' AND (
    (code >= 'RES-CNC-11' AND code <= 'RES-CNC-30') OR
    (code >= 'RES-HEAT-11' AND code <= 'RES-HEAT-15') OR
    (code >= 'RES-ASSY-11' AND code <= 'RES-ASSY-25') OR
    (code >= 'RES-QC-11' AND code <= 'RES-QC-15') OR
    (code >= 'RES-OP-11' AND code <= 'RES-OP-15'))
UNION ALL SELECT 'calendar', count(*) FROM calendar WHERE tenant_id = '$tenant' AND code IN (
    'CAL-FAC-11','CAL-RES-HEAT-11','CAL-RES-HEAT-12','CAL-RES-HEAT-13','CAL-RES-HEAT-14','CAL-RES-HEAT-15')
UNION ALL SELECT 'operation_templates', count(*) FROM operation_template WHERE tenant_id = '$tenant' AND code IN (
    'OP-CNC-11','OP-CNC-12','OP-CNC-13','OP-HEAT-11','OP-ASSY-11','OP-ASSY-12','OP-ASSY-13','OP-QC-11')
UNION ALL SELECT 'routings', count(*) FROM routing WHERE tenant_id = '$tenant' AND product_id IN (
    SELECT id FROM material WHERE tenant_id = '$tenant' AND code >= 'MAT-FG-101' AND code <= 'MAT-FG-110')
UNION ALL SELECT 'routing_nodes', count(*) FROM routing_node WHERE tenant_id = '$tenant' AND routing_id IN (
    SELECT r.id FROM routing r JOIN material m ON m.id = r.product_id
    WHERE r.tenant_id = '$tenant' AND m.code >= 'MAT-FG-101' AND m.code <= 'MAT-FG-110')
UNION ALL SELECT 'routing_edges', count(*) FROM routing_edge WHERE tenant_id = '$tenant' AND routing_id IN (
    SELECT r.id FROM routing r JOIN material m ON m.id = r.product_id
    WHERE r.tenant_id = '$tenant' AND m.code >= 'MAT-FG-101' AND m.code <= 'MAT-FG-110')
UNION ALL SELECT 'expected_tasks', count(*) * 6 FROM orders WHERE tenant_id = '$tenant' AND remark = '$orderRemark'
ORDER BY 1;
"@
$summarySqlFile = Join-Path ([System.IO.Path]::GetTempPath()) ("aps-perf-summary-{0}.sql" -f ([guid]::NewGuid().ToString('N')))
try {
    [System.IO.File]::WriteAllText($summarySqlFile, $summarySql, $utf8NoBom)
    $summary = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A -F '|' -f $summarySqlFile
    if ($LASTEXITCODE -ne 0) { throw 'summary query failed' }
}
finally {
    Remove-Item $summarySqlFile -Force -ErrorAction SilentlyContinue
}

Write-Host ''
foreach ($line in @($summary)) { if ($line) { Write-Host "    $line" } }
Write-Host ''
Write-Ok ("Seed ready: {0} orders, sandbox {1}, seed elapsed {2:N0} ms" -f $OrderCount, $sandbox, $elapsed)
Write-Host "    Cleanup: .\scripts\seed-m2-perf.ps1 -Clean -DbName $DbName"
