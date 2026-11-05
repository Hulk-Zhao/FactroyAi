#Requires -Version 5.1
<#
.SYNOPSIS
    启动无 Docker 的便携 PostgreSQL 15（本地验证专用）。

.DESCRIPTION
    不依赖 Docker / Podman：使用 D:\tools\_aps-toolchain\pgsql 下的 PostgreSQL 便携二进制，
    数据目录固定在 D:\tools\_aps-localdata\pg（工作区外），监听 127.0.0.1:55432。
    幂等：数据目录已存在则复用；服务已在运行则跳过启动；库已存在则跳过创建。

    环境变量（均可通过参数覆盖）：
      APS_PG_HOME     便携 PostgreSQL 根目录，默认 D:\tools\_aps-toolchain\pgsql
      APS_PG_DATA     数据目录，默认 D:\tools\_aps-localdata\pg
      APS_PG_PORT     端口，默认 55432
      APS_PG_USER     本地账号，默认 aps
      APS_PG_PASSWORD 本地口令，默认 aps_dev_pwd（与 application-dev.yml 的 DB_PASSWORD 默认一致）
      APS_PG_DATABASES 需要保证存在的库，默认 aps_local,aps_local_2

.EXAMPLE
    .\scripts\local-db-up.ps1
.EXAMPLE
    .\scripts\local-db-up.ps1 -Port 55433
#>
[CmdletBinding()]
param(
    [string]$PgHome = $(if ($env:APS_PG_HOME) { $env:APS_PG_HOME } else { 'D:\tools\_aps-toolchain\pgsql' }),
    [string]$DataDir = $(if ($env:APS_PG_DATA) { $env:APS_PG_DATA } else { 'D:\tools\_aps-localdata\pg' }),
    [int]$Port = $(if ($env:APS_PG_PORT) { [int]$env:APS_PG_PORT } else { 55432 }),
    [string]$User = $(if ($env:APS_PG_USER) { $env:APS_PG_USER } else { 'aps' }),
    [string]$Password = $(if ($env:APS_PG_PASSWORD) { $env:APS_PG_PASSWORD } else { 'aps_dev_pwd' }),
    [string[]]$Databases = $(if ($env:APS_PG_DATABASES) { $env:APS_PG_DATABASES -split ',' } else { @('aps_local', 'aps_local_2') })
)

$ErrorActionPreference = 'Stop'

function Write-Step {
    param([string]$Message)
    Write-Host "[..] $Message" -ForegroundColor Cyan
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Stop-WithHelp {
    param([string]$Message, [string[]]$Hints = @())
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    foreach ($hint in $Hints) { Write-Host "  - $hint" -ForegroundColor Yellow }
    exit 1
}

function Get-PortConflict {
    param([int]$Port)
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($conn) {
            $procName = (Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue).ProcessName
            if ($procName) { return "端口已被 PID=$($conn.OwningProcess) ($procName) 占用" }
            return "端口已被 PID=$($conn.OwningProcess) 占用"
        }
    }
    if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
        return '端口已有监听（未能获取占用进程）'
    }
    return $null
}

$binDir = Join-Path $PgHome 'bin'
$psql = Join-Path $binDir 'psql.exe'
$pgCtl = Join-Path $binDir 'pg_ctl.exe'
$initdb = Join-Path $binDir 'initdb.exe'
$createdb = Join-Path $binDir 'createdb.exe'
$pgIsReady = Join-Path $binDir 'pg_isready.exe'
$logFile = Join-Path (Split-Path -Parent $DataDir) 'pg.log'

if (-not (Test-Path $psql)) {
    Stop-WithHelp "未找到便携 PostgreSQL：$PgHome" @(
        '便携二进制不在工作区内，需先下载并解压到 APS_PG_HOME',
        '下载：https://get.enterprisedb.com/postgresql/postgresql-15.12-1-windows-x64-binaries.zip',
        '解压后目录应包含 bin\postgres.exe、bin\psql.exe、bin\pg_ctl.exe'
    )
}

# 1. 已有实例是否在运行（先判状态，避免误报端口冲突）
$isRunning = $false
& $pgIsReady -h 127.0.0.1 -p $Port -U $User *> $null
if ($LASTEXITCODE -eq 0) {
    $isRunning = $true
}
elseif ((Test-Path (Join-Path $DataDir 'postmaster.pid'))) {
    Write-Warn "检测到 postmaster.pid 但 $Port 未就绪，按未运行处理（残留文件可能需清理）"
}

if ($isRunning) {
    Write-Step "PostgreSQL 已在运行（数据目录 $DataDir）"
}
else {
    $conflict = Get-PortConflict -Port $Port
    if ($conflict) {
        Stop-WithHelp "端口 $Port 被占用，已中止（未改动任何数据）" @(
            $conflict,
            '用 -Port 指定其他端口，或停止占用该端口的进程',
            '可执行 .\scripts\local-db-down.ps1 停止本脚本启动的实例'
        )
    }

    if (-not (Test-Path (Join-Path $DataDir 'PG_VERSION'))) {
        Write-Step "初始化数据目录 $DataDir（账号 $User）"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $DataDir) | Out-Null
        $pwFile = Join-Path $env:TEMP "aps-pg-pw-$([guid]::NewGuid().ToString('N')).txt"
        try {
            Set-Content -Path $pwFile -Value $Password -Encoding ascii -NoNewline
            & $initdb -D $DataDir -U $User --pwfile=$pwFile -E UTF8 -A scram-sha-256 | Out-Host
            if ($LASTEXITCODE -ne 0) { Stop-WithHelp 'initdb 失败' @('检查数据目录权限与磁盘空间') }
        }
        finally {
            Remove-Item $pwFile -Force -ErrorAction SilentlyContinue
        }
    }

    Write-Step "启动 PostgreSQL（127.0.0.1:$Port）"
    & $pgCtl -D $DataDir -l $logFile -o "-p $Port -c listen_addresses=127.0.0.1" start | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Stop-WithHelp 'PostgreSQL 启动失败' @("查看日志：$logFile", '日志常见原因：端口占用、数据目录权限、残留 postmaster.pid')
    }

    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        & $pgIsReady -h 127.0.0.1 -p $Port -U $User *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { Stop-WithHelp "PostgreSQL 30 秒内未就绪" @("查看日志：$logFile") }
}

# 2. 保证目标库存在
$env:PGPASSWORD = $Password
foreach ($db in $Databases) {
    $db = $db.Trim()
    if (-not $db) { continue }
    & $psql -h 127.0.0.1 -p $Port -U $User -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$db'" *> $null
    if ($LASTEXITCODE -ne 0) { Stop-WithHelp "无法连接数据库（账号 $User / 端口 $Port）" @("检查 pg_hba 或口令：$logFile") }
    $exists = (& $psql -h 127.0.0.1 -p $Port -U $User -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$db'").Trim()
    if ($exists -ne '1') {
        Write-Step "创建数据库 $db"
        & $createdb -h 127.0.0.1 -p $Port -U $User $db | Out-Host
        if ($LASTEXITCODE -ne 0) { Stop-WithHelp "创建数据库 $db 失败" }
    }
    else {
        Write-Host "    $db 已存在" -ForegroundColor DarkGray
    }
}

Write-Host ''
Write-Host '本地 PostgreSQL 已就绪（无 Docker）' -ForegroundColor Green
Write-Host "  版本目录：$PgHome"
Write-Host "  数据目录：$DataDir"
Write-Host "  连接串：  jdbc:postgresql://127.0.0.1:$Port/$($Databases[0])"
Write-Host "  账号：    $User / $Password"
Write-Host "  日志：    $logFile"
Write-Host ''
Write-Host '停止：.\scripts\local-db-down.ps1（默认保留数据目录）'
