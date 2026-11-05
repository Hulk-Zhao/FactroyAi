#Requires -Version 5.1
<#
.SYNOPSIS
    本地一键复跑 M1-QA-01 真库集成测试（无 Docker）。

.DESCRIPTION
    1. 端口未监听时调用 scripts/local-db-up.ps1 启动便携 PostgreSQL 15；
    2. 库为空时自动执行 db/migration/V1__init.sql 建表；
    3. 通过环境变量把连接参数传给测试（APS_TEST_DB_* + APS_TEST_DB_ENABLED=true）；
    4. 只构建 aps-service 及其依赖，不触碰 aps-admin，避免与启动验证抢 target。
    5. 安全边界：真库测试只允许在受控库（aps_test* / aps_m2_*）上运行，禁止指向 dev/生产库；
       SolveTaskRepositoryRealDbTest 在执行 Flyway clean 前用 SELECT current_database() 做库名白名单校验。

    证据输出：aps-service/target/qa-evidence/m1-qa-01-evidence.log

.EXAMPLE
    .\scripts\test-realdb.ps1
.EXAMPLE
    .\scripts\test-realdb.ps1 -DbPort 5432 -DbName aps_ci
#>
[CmdletBinding()]
param(
    [string]$Toolchain = $(if ($env:APS_TOOLCHAIN) { $env:APS_TOOLCHAIN } else { 'D:\tools\_aps-toolchain' }),
    [string]$DbHost = $(if ($env:APS_TEST_DB_HOST) { $env:APS_TEST_DB_HOST } else { '127.0.0.1' }),
    [int]$DbPort = $(if ($env:APS_TEST_DB_PORT) { [int]$env:APS_TEST_DB_PORT } else { 55432 }),
    [string]$DbName = $(if ($env:APS_TEST_DB_NAME) { $env:APS_TEST_DB_NAME } else { 'aps_local' }),
    [string]$DbUser = $(if ($env:APS_TEST_DB_USER) { $env:APS_TEST_DB_USER } else { 'aps' }),
    [string]$DbPassword = $(if ($env:APS_TEST_DB_PASSWORD) { $env:APS_TEST_DB_PASSWORD } else { 'aps_dev_pwd' }),
    [string]$TestPattern = '*RealDbIntegrationTest,ProductionPersistenceConfigRealDbTest'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$psql = Join-Path $Toolchain 'pgsql\bin\psql.exe'

function Write-Step {
    param([string]$Message)
    Write-Host "[..] $Message" -ForegroundColor Cyan
}

if (-not (Test-Path $psql)) {
    throw "未找到 psql：$psql，请通过 -Toolchain 指定便携 PostgreSQL 根目录"
}

$listening = Test-NetConnection -ComputerName $DbHost -Port $DbPort -InformationLevel Quiet
if (-not $listening) {
    Write-Step "端口 $DbHost`:$DbPort 未监听，启动本地 PostgreSQL"
    & (Join-Path $PSScriptRoot 'local-db-up.ps1')
}

$env:PGPASSWORD = $DbPassword
$tables = & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -t -A `
    -c "select count(*) from information_schema.tables where table_schema = 'public';"
if ([int]$tables -eq 0) {
    Write-Step "数据库 $DbName 为空，执行 V1__init.sql"
    & $psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 `
        -f (Join-Path $repoRoot 'db\migration\V1__init.sql')
}

$env:JAVA_HOME = Join-Path $Toolchain 'jdk-21.0.13+9'
$env:PATH = "$env:JAVA_HOME\bin;$(Join-Path $Toolchain 'apache-maven-3.9.16\bin');$env:PATH"
$env:APS_TEST_DB_ENABLED = 'true'
$env:APS_TEST_DB_HOST = $DbHost
$env:APS_TEST_DB_PORT = "$DbPort"
$env:APS_TEST_DB_NAME = $DbName
$env:APS_TEST_DB_USER = $DbUser
$env:APS_TEST_DB_PASSWORD = $DbPassword

Write-Step "执行真库集成测试（$TestPattern）"
mvn -B -ntp -pl aps-service -am test "-Dtest=$TestPattern" `
    '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcheckstyle.skip=true' '-Dpmd.skip=true'
if ($LASTEXITCODE -ne 0) {
    throw "真库集成测试失败，退出码 $LASTEXITCODE"
}

Write-Host "[OK] 证据文件：aps-service/target/qa-evidence/m1-qa-01-evidence.log" -ForegroundColor Green
