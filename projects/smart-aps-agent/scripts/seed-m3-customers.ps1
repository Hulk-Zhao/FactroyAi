#Requires -Version 5.1
<#
.SYNOPSIS
    Seed (or clean) the M3-QA-04 customer master data used by the C38 import/export demo.

.DESCRIPTION
    Reuses the seed-m1-demo.ps1 conventions: psql direct access, fixed UUIDs for cleanup,
    tenant-scoped statements, and no LIKE-prefix deletion (other sessions' rows are never touched).

    Dataset: 8 customers, codes CUS-001..CUS-008 (001-099 is the shared demo block;
    101+ stays reserved for per-script / mock data).
    Columns covered = C38 template columns:
      code / name / priority (VIP|NORMAL|LOW) / lead_time_days / payment_terms / blacklisted

    Priority mix: 2 VIP (CUS-001, CUS-005), 1 blacklisted (CUS-008, negative demo),
    the rest NORMAL/LOW so the list looks natural in a demo.

    Idempotent: upsert on (tenant_id, code) where deleted = false; re-running keeps the
    row count at 8 and only bumps version/updated_at (inserted=0, updated=8).

    Does NOT touch seed-m1-demo.ps1 (8 orders / 48 tasks baseline) or seed-m2-perf.ps1.

.PARAMETER Clean
    Only remove the customer rows owned by this script, do not re-insert.

.PARAMETER SkipDbStart
    Do not call scripts/local-db-up.ps1 when the database port is not listening.

.EXAMPLE
    .\scripts\seed-m3-customers.ps1 -DbName aps_local
.EXAMPLE
    .\scripts\seed-m3-customers.ps1 -DbName aps_local -Clean
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

# ---------------------------------------------------------------------------
# Fixed customer ids (deterministic cleanup; the upsert key is still tenant+code)
# ---------------------------------------------------------------------------
$tenant = $TenantId
$customerIds = @(
    'd0000000-0000-0000-0000-000000000901',
    'd0000000-0000-0000-0000-000000000902',
    'd0000000-0000-0000-0000-000000000903',
    'd0000000-0000-0000-0000-000000000904',
    'd0000000-0000-0000-0000-000000000905',
    'd0000000-0000-0000-0000-000000000906',
    'd0000000-0000-0000-0000-000000000907',
    'd0000000-0000-0000-0000-000000000908'
)
$idList = ($customerIds | ForEach-Object { "'$_'" }) -join ','

$tenantExists = (& $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A `
        -c "select count(*) from tenant where id = '$tenant';").Trim()
if ($LASTEXITCODE -ne 0) { throw "tenant lookup failed with exit code $LASTEXITCODE" }
if ([int]$tenantExists -eq 0) {
    throw "Tenant $tenant not found in $DbName (V1 seeds only the default tenant)"
}

$cleanupSql = @"
BEGIN;
DELETE FROM customer WHERE tenant_id = '$tenant' AND id IN ($idList);
COMMIT;
"@

$seedSql = @"
BEGIN;
INSERT INTO customer (id, tenant_id, code, name, priority, lead_time_days, payment_terms, blacklisted)
VALUES
 ('d0000000-0000-0000-0000-000000000901','$tenant','CUS-001','华北储能客户 A','VIP',   3, '月结30天',      false),
 ('d0000000-0000-0000-0000-000000000902','$tenant','CUS-002','华东渠道客户 B','NORMAL', 5, '月结45天',      false),
 ('d0000000-0000-0000-0000-000000000903','$tenant','CUS-003','华南系统集成客户 C','NORMAL', 7, '货到付款',      false),
 ('d0000000-0000-0000-0000-000000000904','$tenant','CUS-004','西南分布式客户 D','LOW',   10, '月结60天',      false),
 ('d0000000-0000-0000-0000-000000000905','$tenant','CUS-005','海外出口客户 E','VIP',     14, 'T/T 30% 预付',  false),
 ('d0000000-0000-0000-0000-000000000906','$tenant','CUS-006','西北电网侧客户 F','NORMAL', 21, '月结90天',      false),
 ('d0000000-0000-0000-0000-000000000907','$tenant','CUS-007','华中工商业储能客户 G','LOW', 15, '月结60天',      false),
 ('d0000000-0000-0000-0000-000000000908','$tenant','CUS-008','高风险账期客户 H','LOW',    30, '月结120天',     true)
ON CONFLICT (tenant_id, code) WHERE deleted = false DO UPDATE SET
    name = EXCLUDED.name,
    priority = EXCLUDED.priority,
    lead_time_days = EXCLUDED.lead_time_days,
    payment_terms = EXCLUDED.payment_terms,
    blacklisted = EXCLUDED.blacklisted,
    updated_at = now(),
    version = customer.version + 1;
COMMIT;
"@

$tempSql = Join-Path ([System.IO.Path]::GetTempPath()) ("aps-m3-cust-{0}.sql" -f ([guid]::NewGuid().ToString('N')))
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

try {
    if ($Clean) {
        Write-Step 'Removing previous M3 customer seed rows (idempotent cleanup by fixed ids)'
        [System.IO.File]::WriteAllText($tempSql, $cleanupSql, $utf8NoBom)
        & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q -f $tempSql
        if ($LASTEXITCODE -ne 0) { throw "cleanup SQL failed with exit code $LASTEXITCODE" }
        Write-Ok 'M3 customer seed rows removed (Clean mode)'
        return
    }

    Write-Step 'Upserting M3 customer dataset (CUS-001..CUS-008)'
    [System.IO.File]::WriteAllText($tempSql, $seedSql, $utf8NoBom)
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q -f $tempSql
    if ($LASTEXITCODE -ne 0) { throw "seed SQL failed with exit code $LASTEXITCODE" }
}
finally {
    Remove-Item $tempSql -Force -ErrorAction SilentlyContinue
}

$summary = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A -F '|' -c @"
SELECT 'customers', count(*), min(version), max(version)
FROM customer WHERE tenant_id = '$tenant' AND id IN ($idList)
UNION ALL SELECT 'vip', count(*), NULL, NULL FROM customer WHERE tenant_id = '$tenant' AND id IN ($idList) AND priority = 'VIP'
UNION ALL SELECT 'blacklisted', count(*), NULL, NULL FROM customer WHERE tenant_id = '$tenant' AND id IN ($idList) AND blacklisted
UNION ALL SELECT 'lead_time_days', min(lead_time_days), max(lead_time_days), NULL FROM customer WHERE tenant_id = '$tenant' AND id IN ($idList)
ORDER BY 1;
"@
if ($LASTEXITCODE -ne 0) { throw "summary query failed with exit code $LASTEXITCODE" }

Write-Host ''
foreach ($line in @($summary)) {
    if ($line) { Write-Host "    $line" }
}
Write-Host ''
Write-Ok "M3 customers ready for tenant $tenant (codes CUS-001..CUS-008)"
Write-Host "    Cleanup: .\scripts\seed-m3-customers.ps1 -DbName $DbName -Clean"
