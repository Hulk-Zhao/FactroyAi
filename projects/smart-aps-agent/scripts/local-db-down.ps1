#Requires -Version 5.1
<#
.SYNOPSIS
    停止无 Docker 的便携 PostgreSQL 15。

.DESCRIPTION
    幂等：未运行则直接返回成功。默认保留数据目录以便下次复用；
    仅在显式 -RemoveData 时才删除数据目录（会二次确认，-Force 跳过确认）。

.EXAMPLE
    .\scripts\local-db-down.ps1
.EXAMPLE
    .\scripts\local-db-down.ps1 -RemoveData -Force
#>
[CmdletBinding()]
param(
    [string]$PgHome = $(if ($env:APS_PG_HOME) { $env:APS_PG_HOME } else { 'D:\tools\_aps-toolchain\pgsql' }),
    [string]$DataDir = $(if ($env:APS_PG_DATA) { $env:APS_PG_DATA } else { 'D:\tools\_aps-localdata\pg' }),
    [switch]$RemoveData,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

function Write-Step {
    param([string]$Message)
    Write-Host "[..] $Message" -ForegroundColor Cyan
}

function Stop-WithHelp {
    param([string]$Message, [string[]]$Hints = @())
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    foreach ($hint in $Hints) { Write-Host "  - $hint" -ForegroundColor Yellow }
    exit 1
}

$pgCtl = Join-Path (Join-Path $PgHome 'bin') 'pg_ctl.exe'
if (-not (Test-Path $pgCtl)) {
    Stop-WithHelp "未找到 pg_ctl：$pgCtl" @('检查 APS_PG_HOME 是否指向便携 PostgreSQL 根目录')
}

if (-not (Test-Path (Join-Path $DataDir 'PG_VERSION'))) {
    Write-Host '数据目录不存在，无需停止' -ForegroundColor Yellow
}
else {
    & $pgCtl -D $DataDir status *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-Step '停止 PostgreSQL（fast 模式）'
        & $pgCtl -D $DataDir -m fast stop | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Stop-WithHelp '停止失败' @("查看日志：$(Join-Path (Split-Path -Parent $DataDir) 'pg.log')")
        }
    }
    else {
        Write-Host 'PostgreSQL 未运行（或上次异常退出），无需停止' -ForegroundColor Yellow
        $pidFile = Join-Path $DataDir 'postmaster.pid'
        if (Test-Path $pidFile) {
            Write-Host "  注意：残留 $pidFile；如确认无进程占用，可手动删除后重新启动" -ForegroundColor Yellow
        }
    }
}

if ($RemoveData) {
    if (-not $Force) {
        $answer = Read-Host "确认删除数据目录 $DataDir ？输入 yes 继续"
        if ($answer -ne 'yes') {
            Write-Host '已取消删除，数据目录保留' -ForegroundColor Yellow
            return
        }
    }
    Write-Step "删除数据目录 $DataDir"
    Remove-Item -Recurse -Force $DataDir
    $logFile = Join-Path (Split-Path -Parent $DataDir) 'pg.log'
    if (Test-Path $logFile) { Remove-Item -Force $logFile }
    Write-Host '数据目录已删除' -ForegroundColor Green
}
else {
    Write-Host '数据目录已保留，可再次执行 .\scripts\local-db-up.ps1 复用' -ForegroundColor DarkGray
}
