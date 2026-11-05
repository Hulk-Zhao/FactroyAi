#Requires -Version 5.1
<#
.SYNOPSIS
    Seed (or clean) the idempotent M1 closed-loop demo dataset in the local PostgreSQL.

.DESCRIPTION
    The script talks to PostgreSQL directly with psql (not through the application API),
    because master-data writes need explicit tenant_id / version / deleted values and
    fixed UUIDs so the demo stays reproducible and cleanable. Every column used here is
    taken from db/migration/V1__init.sql.

    Dataset (canonical energy-storage product family):
      material      : MAT-FG-001 (finished module), MAT-SF-001 (semi-finished pack),
                      MAT-RM-003 / MAT-RM-004 (structural raw materials),
                      MAT-FG-002 (finished module without BOM, negative case)
      bom           : 2 levels (FG -> SF -> RAW), both ACTIVE, V2.0 / V3.0
      calendar      : CAL-FAC-7X24 (FACTORY, 00:00-24:00 every day)
      resource      : 6 resources covering UNIT_PER_HOUR / SECONDS_PER_UNIT / BATCH_SECONDS
      operation     : 4 templates + 1 ACTIVE routing (6 nodes, FS edges, 30 min lag edge,
                      auxiliary-resource overrides on node 40 and node 50)
      strategy      : 交期优先·正向排产策略 (used to isolate demo solve_task rows)
      orders        : 8 DRAFT orders + 1 DRAFT order without BOM (negative case)

    Idempotent: cleanup by fixed UUID lists, then re-insert. No code-prefix LIKE is used,
    so other sessions' data can never be removed by this script.

.PARAMETER Clean
    Only remove demo data, do not re-insert.

.PARAMETER SkipDbStart
    Do not call scripts/local-db-up.ps1 when the port is not listening.

.EXAMPLE
    .\scripts\seed-m1-demo.ps1
.EXAMPLE
    .\scripts\seed-m1-demo.ps1 -Clean
#>
[CmdletBinding()]
param(
    [string]$Toolchain = $(if ($env:APS_TOOLCHAIN) { $env:APS_TOOLCHAIN } else { 'D:\tools\_aps-toolchain' }),
    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { '127.0.0.1' }),
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 55432 }),
    [string]$DbName = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'aps_local' }),
    [string]$DbUser = $(if ($env:DB_USER) { $env:DB_USER } else { 'aps' }),
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'aps_dev_pwd' }),
    [string]$TenantId = '00000000-0000-0000-0000-000000000001',
    [switch]$Clean,
    [switch]$SkipDbStart
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$psql = Join-Path $Toolchain 'pgsql\bin\psql.exe'

function Write-Step {
    param([string]$Message)
    Write-Host "[..] $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

if (-not (Test-Path $psql)) {
    throw "psql not found: $psql (use -Toolchain to point at the portable PostgreSQL root)"
}

$listening = Test-NetConnection -ComputerName $DbHost -Port $DbPort -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $listening) {
    if ($SkipDbStart) {
        throw "PostgreSQL is not listening on $DbHost`:$DbPort (SkipDbStart is set)"
    }
    Write-Step "Port $DbHost`:$DbPort is closed, starting local PostgreSQL"
    & (Join-Path $PSScriptRoot 'local-db-up.ps1')
}

$env:PGPASSWORD = $DbPassword
$tableCount = (& $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A `
        -c "select count(*) from information_schema.tables where table_schema = 'public';").Trim()
if ([int]$tableCount -eq 0) {
    Write-Step "Database $DbName is empty, applying db/migration/V1__init.sql"
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 `
        -f (Join-Path $repoRoot 'db\migration\V1__init.sql')
    if ($LASTEXITCODE -ne 0) { throw "V1__init.sql failed with exit code $LASTEXITCODE" }
}

# ---------------------------------------------------------------------------
# Demo dataset definition (all UUIDs are fixed so cleanup is deterministic)
# ---------------------------------------------------------------------------
$tenant = $TenantId
$sandbox = '00000000-0000-0000-0000-000000000401'

$mFg = 'd0000000-0000-0000-0000-000000000101'
$mSf = 'd0000000-0000-0000-0000-000000000102'
$mR3 = 'd0000000-0000-0000-0000-000000000103'
$mR4 = 'd0000000-0000-0000-0000-000000000104'
$mNoBom = 'd0000000-0000-0000-0000-000000000105'

$cal = 'd0000000-0000-0000-0000-000000000201'

$rSmt1 = 'd0000000-0000-0000-0000-000000000301'
$rSmt2 = 'd0000000-0000-0000-0000-000000000302'
$rAsm = 'd0000000-0000-0000-0000-000000000303'
$rAge = 'd0000000-0000-0000-0000-000000000304'
$rQa = 'd0000000-0000-0000-0000-000000000305'
$rHuman = 'd0000000-0000-0000-0000-000000000306'

$tSmt = 'd0000000-0000-0000-0000-000000000401'
$tAsm = 'd0000000-0000-0000-0000-000000000402'
$tQa = 'd0000000-0000-0000-0000-000000000403'
$tAge = 'd0000000-0000-0000-0000-000000000404'

$routing = 'd0000000-0000-0000-0000-000000000501'
$n10 = 'd0000000-0000-0000-0000-000000000511'
$n20 = 'd0000000-0000-0000-0000-000000000512'
$n30 = 'd0000000-0000-0000-0000-000000000513'
$n40 = 'd0000000-0000-0000-0000-000000000514'
$n50 = 'd0000000-0000-0000-0000-000000000515'
$n60 = 'd0000000-0000-0000-0000-000000000516'
$e1 = 'd0000000-0000-0000-0000-000000000521'
$e2 = 'd0000000-0000-0000-0000-000000000522'
$e3 = 'd0000000-0000-0000-0000-000000000523'
$e4 = 'd0000000-0000-0000-0000-000000000524'
$e5 = 'd0000000-0000-0000-0000-000000000525'

$bomFg = 'd0000000-0000-0000-0000-000000000601'
$bomSf = 'd0000000-0000-0000-0000-000000000602'
$bi1 = 'd0000000-0000-0000-0000-000000000611'
$bi2 = 'd0000000-0000-0000-0000-000000000612'
$bi3 = 'd0000000-0000-0000-0000-000000000613'
$bi4 = 'd0000000-0000-0000-0000-000000000614'

$strategy = 'd0000000-0000-0000-0000-000000000801'

$demoOrderIds = @(
    'd0000000-0000-0000-0000-000000000701',
    'd0000000-0000-0000-0000-000000000702',
    'd0000000-0000-0000-0000-000000000703',
    'd0000000-0000-0000-0000-000000000704',
    'd0000000-0000-0000-0000-000000000705',
    'd0000000-0000-0000-0000-000000000706',
    'd0000000-0000-0000-0000-000000000707',
    'd0000000-0000-0000-0000-000000000708',
    'd0000000-0000-0000-0000-000000000709'
)
$orderIdList = ($demoOrderIds | ForEach-Object { "'$_'" }) -join ','

$cleanupSql = @"
BEGIN;
-- 1) constraint_justification rows that point at demo task ids
DELETE FROM constraint_justification cj
WHERE cj.tenant_id = '$tenant'
  AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(cj.implicated_facts) AS fact
      WHERE (fact ->> 'id') ~ '^[0-9]+$'
        AND (fact ->> 'id')::bigint IN (
            SELECT t.id FROM task t
            WHERE t.tenant_id = '$tenant'
              AND t.order_id IN ($orderIdList)
        )
  );
-- 2) demo tasks and orders (fixed UUID list, never a code prefix)
DELETE FROM task WHERE tenant_id = '$tenant' AND order_id IN ($orderIdList);
DELETE FROM orders WHERE tenant_id = '$tenant' AND id IN ($orderIdList);
-- 3) process graph (fixed routing / node / edge ids)
DELETE FROM routing_edge WHERE tenant_id = '$tenant' AND routing_id = '$routing';
DELETE FROM routing_node WHERE tenant_id = '$tenant' AND routing_id = '$routing';
DELETE FROM routing WHERE tenant_id = '$tenant' AND id = '$routing';
-- 4) BOM (fixed ids)
DELETE FROM bom_item WHERE tenant_id = '$tenant' AND bom_id IN ('$bomFg','$bomSf');
DELETE FROM bom WHERE tenant_id = '$tenant' AND id IN ('$bomFg','$bomSf');
-- 5) templates, resources, calendar, materials (fixed ids)
DELETE FROM operation_template WHERE tenant_id = '$tenant' AND id IN ('$tSmt','$tAsm','$tQa','$tAge');
DELETE FROM resource WHERE tenant_id = '$tenant' AND id IN ('$rSmt1','$rSmt2','$rAsm','$rAge','$rQa','$rHuman');
DELETE FROM calendar WHERE tenant_id = '$tenant' AND id = '$cal';
DELETE FROM material WHERE tenant_id = '$tenant' AND id IN ('$mFg','$mSf','$mR3','$mR4','$mNoBom');
-- 6) demo strategy and its solve tasks
DELETE FROM solve_task WHERE tenant_id = '$tenant' AND strategy_id = '$strategy';
DELETE FROM strategy WHERE id = '$strategy';
COMMIT;
"@

$seedSql = @"
BEGIN;
INSERT INTO material (id, tenant_id, code, name, type, unit, spec, ext_attrs)
VALUES
 ('$mFg',   '$tenant', 'MAT-FG-001', '储能电芯模组 48V/15Ah', 'FINISHED', '套', '48V 15Ah 16S1P', '{}'::jsonb),
 ('$mSf',   '$tenant', 'MAT-SF-001', '电池包半成品 16S1P',   'SEMI',     '个', '16S1P 电池包',   '{}'::jsonb),
 ('$mR3',   '$tenant', 'MAT-RM-003', '铝合金型材 6061-T6',   'RAW',      'KG',  '6061-T6',        '{}'::jsonb),
 ('$mR4',   '$tenant', 'MAT-RM-004', 'ABS 阻燃粒料',         'RAW',      'KG',  'FR-ABS',         '{}'::jsonb),
 ('$mNoBom','$tenant', 'MAT-FG-002', '储能电芯模组 48V/20Ah', 'FINISHED', '套', '48V 20Ah',       '{}'::jsonb);

INSERT INTO calendar (id, tenant_id, code, name, type, effective_from, shifts, holidays, calendar_version, version)
VALUES ('$cal', '$tenant', 'CAL-FAC-7X24', '全厂 7×24 日历', 'FACTORY', DATE '2026-01-01',
        '[{"startTime": "00:00", "endTime": "24:00", "daysOfWeek": [1,2,3,4,5,6,7]}]'::jsonb,
        '[]'::jsonb, 1, 1);

INSERT INTO resource (id, tenant_id, code, name, type, capacity_mode, capacity_value, batch_capacity,
                      efficiency, calendar_id, setup_seconds, teardown_seconds, maintenance_plan, industry_attrs)
VALUES
 ('$rSmt1',  '$tenant', 'RES-SMT-01', 'SMT 贴片机 1 号',    'PRIMARY',   'UNIT_PER_HOUR',    6,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rSmt2',  '$tenant', 'RES-SMT-02', 'SMT 贴片机 2 号',    'PRIMARY',   'UNIT_PER_HOUR',    4,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rAsm',   '$tenant', 'RES-ASSY-01','电池包总装线 1 号',  'PRIMARY',   'SECONDS_PER_UNIT', 0,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rAge',   '$tenant', 'RES-AGE-01', '储能老化柜 1 号',    'PRIMARY',   'BATCH_SECONDS',    600,  20,   1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rQa',    '$tenant', 'RES-QC-01',  '质检工位 1 号',      'SECONDARY', 'SECONDS_PER_UNIT', 0,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb),
 ('$rHuman', '$tenant', 'RES-OP-01',  '装配操作工 1 名',    'HUMAN',     'SECONDS_PER_UNIT', 0,    NULL, 1.00, '$cal', 0, 0, '{}'::jsonb, '{}'::jsonb);

INSERT INTO operation_template (id, tenant_id, code, name, primary_resource_id, primary_resource_group_id,
                                secondary_resource_ids, input_material_ids, output_material_id,
                                standard_seconds, setup_seconds, teardown_seconds, template_version, version)
VALUES
 ('$tSmt', '$tenant', 'OP-SMT-01',  'SMT 贴片',    '$rSmt1', NULL, '[]'::jsonb, '[]'::jsonb, NULL, 60,  30, 0, 1, 1),
 ('$tAge', '$tenant', 'OP-AGE-01',  '老化烘烤',    '$rAge',  NULL, '[]'::jsonb, '[]'::jsonb, NULL, 600, 30, 0, 1, 1),
 ('$tAsm', '$tenant', 'OP-ASSY-01', '电池包总装',  '$rAsm',  NULL, '[]'::jsonb, '[]'::jsonb, NULL, 30,  10, 0, 1, 1),
 ('$tQa',  '$tenant', 'OP-QC-01',   '出厂检验',    '$rAsm',  NULL, '[]'::jsonb, '[]'::jsonb, NULL, 20,  10, 0, 1, 1);

INSERT INTO routing (id, tenant_id, product_id, routing_version, effective_date, expiry_date, status,
                     is_panel, panel_size, nodes, edges, version)
VALUES ('$routing', '$tenant', '$mFg', 'V2.0', DATE '2026-01-01', NULL, 'ACTIVE', false, NULL,
        '[{"id":"n10","templateId":"$tSmt","seqNo":10,"overrides":{}},
          {"id":"n20","templateId":"$tSmt","seqNo":20,"overrides":{"primaryResourceId":"$rSmt2"}},
          {"id":"n30","templateId":"$tAge","seqNo":30,"overrides":{}},
          {"id":"n40","templateId":"$tAsm","seqNo":40,"overrides":{"secondaryResourceIds":["$rHuman"]}},
          {"id":"n50","templateId":"$tQa","seqNo":50,"overrides":{"secondaryResourceIds":["$rQa"]}},
          {"id":"n60","templateId":"$tAsm","seqNo":60,"overrides":{}}]'::jsonb,
        '[{"from":"n10","to":"n20","type":"FS","lagMinutes":0},
          {"from":"n20","to":"n30","type":"FS","lagMinutes":30},
          {"from":"n30","to":"n40","type":"FS","lagMinutes":0},
          {"from":"n40","to":"n50","type":"FS","lagMinutes":0},
          {"from":"n50","to":"n60","type":"FS","lagMinutes":0}]'::jsonb,
        1);

INSERT INTO routing_node (id, tenant_id, routing_id, node_key, operation_template_id, operation_code,
                          operation_name, standard_seconds, setup_seconds, teardown_seconds,
                          is_optional, overrides, position_x, position_y, seq_no, version)
VALUES
 ('$n10','$tenant','$routing','n10','$tSmt','OP-SMT-01', 'SMT 贴片',        NULL, NULL, NULL, false, '{}'::jsonb, 100, 100, 10, 1),
 ('$n20','$tenant','$routing','n20','$tSmt','OP-SMT-01', 'SMT 贴片',        NULL, NULL, NULL, false, '{"primaryResourceId":"$rSmt2"}'::jsonb, 220, 100, 20, 1),
 ('$n30','$tenant','$routing','n30','$tAge','OP-AGE-01', '老化烘烤',        NULL, NULL, NULL, false, '{}'::jsonb, 340, 100, 30, 1),
 ('$n40','$tenant','$routing','n40','$tAsm','OP-ASSY-01','电池包总装',      NULL, NULL, NULL, false, '{"secondaryResourceIds":["$rHuman"]}'::jsonb, 460, 100, 40, 1),
 ('$n50','$tenant','$routing','n50','$tQa', 'OP-QC-01',  '出厂检验',        NULL, NULL, NULL, false, '{"secondaryResourceIds":["$rQa"]}'::jsonb, 580, 100, 50, 1),
 ('$n60','$tenant','$routing','n60','$tAsm','OP-ASSY-01','包装入库',        NULL, NULL, NULL, false, '{}'::jsonb, 700, 100, 60, 1);

INSERT INTO routing_edge (id, tenant_id, routing_id, from_node_id, to_node_id, depend_type, lag_minutes, version)
VALUES
 ('$e1','$tenant','$routing','$n10','$n20','FS',0,1),
 ('$e2','$tenant','$routing','$n20','$n30','FS',30,1),
 ('$e3','$tenant','$routing','$n30','$n40','FS',0,1),
 ('$e4','$tenant','$routing','$n40','$n50','FS',0,1),
 ('$e5','$tenant','$routing','$n50','$n60','FS',0,1);

INSERT INTO bom (id, tenant_id, parent_material_id, bom_version, effective_date, expiry_date, status,
                 is_panel, panel_size, items, version)
VALUES
 ('$bomFg','$tenant','$mFg','V2.0',DATE '2026-01-01',NULL,'ACTIVE',false,NULL,
  '[{"childMaterialId":"$mSf","materialCode":"MAT-SF-001","qtyPerUnit":1,"scrapRate":0,"seqNo":1},
    {"childMaterialId":"$mR3","materialCode":"MAT-RM-003","qtyPerUnit":2,"scrapRate":0,"seqNo":2}]'::jsonb,1),
 ('$bomSf','$tenant','$mSf','V3.0',DATE '2026-01-01',NULL,'ACTIVE',false,NULL,
  '[{"childMaterialId":"$mR4","materialCode":"MAT-RM-004","qtyPerUnit":1,"scrapRate":0,"seqNo":1},
    {"childMaterialId":"$mR3","materialCode":"MAT-RM-003","qtyPerUnit":0.5,"scrapRate":0,"seqNo":2}]'::jsonb,1);

INSERT INTO bom_item (id, tenant_id, bom_id, child_material_id, qty_per_unit, scrap_rate, position,
                      substitutes, is_phantom, level, seq_no, version)
VALUES
 ('$bi1','$tenant','$bomFg','$mSf',1.000000,0,NULL,'[]'::jsonb,false,1,1,1),
 ('$bi2','$tenant','$bomFg','$mR3',2.000000,0,NULL,'[]'::jsonb,false,1,2,1),
 ('$bi3','$tenant','$bomSf','$mR4',1.000000,0,NULL,'[]'::jsonb,false,2,1,1),
 ('$bi4','$tenant','$bomSf','$mR3',0.500000,0,NULL,'[]'::jsonb,false,2,2,1);

INSERT INTO strategy (id, tenant_id, name, direction, strategy_version, initial_rules, optimization_rules,
                      effective_from, effective_to, apply_condition, version)
VALUES ('$strategy', '$tenant', '交期优先·正向排产策略', 'FORWARD', 1, '{}'::jsonb, '{}'::jsonb,
        DATE '2026-01-01', NULL, '{}'::jsonb, 1);

INSERT INTO orders (id, tenant_id, order_no, source, product_id, qty, due_date, priority, status,
                    freeze_status, remark, ext_attrs, version)
VALUES
 ('d0000000-0000-0000-0000-000000000701','$tenant','SO-20261104-0001','MANUAL','$mFg', 8, CURRENT_DATE + 3, 'HIGH',   'DRAFT','UNFROZEN','标准模组订单', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000702','$tenant','SO-20261104-0002','MANUAL','$mFg',10, CURRENT_DATE + 4, 'NORMAL', 'DRAFT','UNFROZEN','标准模组订单', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000703','$tenant','SO-20261104-0003','MANUAL','$mFg',12, CURRENT_DATE + 5, 'URGENT', 'DRAFT','UNFROZEN','加急模组订单', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000704','$tenant','SO-20261104-0004','MANUAL','$mFg', 6, CURRENT_DATE + 6, 'LOW',    'DRAFT','UNFROZEN','小批量模组订单', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000705','$tenant','SO-20261104-0005','MANUAL','$mFg',14, CURRENT_DATE + 7, 'HIGH',   'DRAFT','UNFROZEN','标准模组订单', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000706','$tenant','SO-20261104-0006','MANUAL','$mFg', 9, CURRENT_DATE + 8, 'NORMAL', 'DRAFT','UNFROZEN','标准模组订单', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000707','$tenant','SO-20261104-0007','MANUAL','$mFg',11, CURRENT_DATE + 9, 'HIGH',   'DRAFT','UNFROZEN','标准模组订单', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000708','$tenant','SO-20261104-0008','MANUAL','$mFg', 7, CURRENT_DATE +10, 'NORMAL', 'DRAFT','UNFROZEN','小批量模组订单', '{}'::jsonb,1),
 ('d0000000-0000-0000-0000-000000000709','$tenant','SO-20261104-0009','MANUAL','$mNoBom',5, CURRENT_DATE + 5, 'NORMAL','DRAFT','UNFROZEN','负例：无 ACTIVE BOM','{}'::jsonb,1);
COMMIT;
"@

$tempSql = Join-Path ([System.IO.Path]::GetTempPath()) ("aps-m1-seed-{0}.sql" -f ([guid]::NewGuid().ToString('N')))
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

try {
    Write-Step 'Removing previous demo data (idempotent cleanup)'
    [System.IO.File]::WriteAllText($tempSql, $cleanupSql, $utf8NoBom)
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q -f $tempSql
    if ($LASTEXITCODE -ne 0) { throw "cleanup SQL failed with exit code $LASTEXITCODE" }

    if ($Clean) {
        Write-Ok 'Demo data removed (Clean mode)'
        return
    }

    Write-Step 'Inserting M1 demo dataset'
    [System.IO.File]::WriteAllText($tempSql, $seedSql, $utf8NoBom)
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q -f $tempSql
    if ($LASTEXITCODE -ne 0) { throw "seed SQL failed with exit code $LASTEXITCODE" }
}
finally {
    Remove-Item $tempSql -Force -ErrorAction SilentlyContinue
}

$summary = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A -F '|' -c @"
SELECT 'materials', count(*) FROM material WHERE tenant_id = '$tenant' AND id IN ('$mFg','$mSf','$mR3','$mR4','$mNoBom')
UNION ALL SELECT 'boms', count(*) FROM bom WHERE tenant_id = '$tenant' AND id IN ('$bomFg','$bomSf')
UNION ALL SELECT 'resources', count(*) FROM resource WHERE tenant_id = '$tenant' AND id IN ('$rSmt1','$rSmt2','$rAsm','$rAge','$rQa','$rHuman')
UNION ALL SELECT 'templates', count(*) FROM operation_template WHERE tenant_id = '$tenant' AND id IN ('$tSmt','$tAsm','$tQa','$tAge')
UNION ALL SELECT 'routing_nodes', count(*) FROM routing_node WHERE tenant_id = '$tenant' AND routing_id = '$routing'
UNION ALL SELECT 'routing_edges', count(*) FROM routing_edge WHERE tenant_id = '$tenant' AND routing_id = '$routing'
UNION ALL SELECT 'orders', count(*) FROM orders WHERE tenant_id = '$tenant' AND id IN ($orderIdList)
UNION ALL SELECT 'orders_draft', count(*) FROM orders WHERE tenant_id = '$tenant' AND id IN ($orderIdList) AND status = 'DRAFT'
ORDER BY 1;
"@
if ($LASTEXITCODE -ne 0) { throw "summary query failed with exit code $LASTEXITCODE" }

Write-Host ''
foreach ($line in @($summary)) {
    if ($line) { Write-Host "    $line" }
}
Write-Host ''
Write-Ok "Seed ready: 8 valid DRAFT orders + 1 no-BOM order, sandbox $sandbox"
Write-Host "    Cleanup:  .\\scripts\\seed-m1-demo.ps1 -Clean"
