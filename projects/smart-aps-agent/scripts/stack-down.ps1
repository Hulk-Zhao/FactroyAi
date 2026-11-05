#Requires -Version 5.1
<#
.SYNOPSIS
    停止 APS 整栈（deploy/docker-compose.yml）。

.DESCRIPTION
    等价 Bash 脚本：scripts/stack-down.sh
    默认保留数据卷（pgdata / redisdata / miniodata），仅停止并移除容器与网络。
    工程名固定 aps-stack，与 dev 栈隔离。

.PARAMETER RemoveVolumes
    同时删除数据卷，下一次启动会重新初始化数据库（业务数据丢失）。

.PARAMETER Force
    与 -RemoveVolumes 搭配时跳过交互确认（CI / 脚本自动化用）。

.EXAMPLE
    .\scripts\stack-down.ps1
.EXAMPLE
    .\scripts\stack-down.ps1 -RemoveVolumes
#>
[CmdletBinding()]
param(
    [switch]$RemoveVolumes,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

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
    foreach ($hint in $Hints) {
        Write-Host "  - $hint" -ForegroundColor Yellow
    }
    exit 1
}

function Invoke-DockerCmd {
    param([string[]]$DockerArgs, [switch]$Quiet)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Quiet) {
            & docker @DockerArgs *> $null
        }
        else {
            & docker @DockerArgs | Out-Host
        }
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
}

try {
    Write-Step '检查 Docker CLI 与守护进程'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-WithHelp '未找到 docker 命令' @('安装 Docker Desktop：https://docs.docker.com/desktop/install/windows-install/')
    }
    if ((Invoke-DockerCmd -DockerArgs @('info') -Quiet) -ne 0) {
        Stop-WithHelp 'Docker 守护进程未运行' @(
            '启动 Docker Desktop 后重试',
            '若容器已不存在，可直接用 docker volume rm 清理残留卷'
        )
    }
    if ((Invoke-DockerCmd -DockerArgs @('compose', 'version') -Quiet) -ne 0) {
        Stop-WithHelp '未检测到 docker compose v2 插件' @('Docker Desktop 自带；Linux 安装 docker-compose-plugin')
    }

    if ($RemoveVolumes -and -not $Force) {
        Write-Warn '即将删除 APS 整栈的全部数据卷（数据库/Redis/MinIO 数据都会丢失）'
        $answer = Read-Host '确认请输入 yes'
        if ($answer -ne 'yes') {
            Write-Host '已取消，未做任何操作。'
            exit 0
        }
    }

    # 显式 --project-name，避免根 .env 的 COMPOSE_PROJECT_NAME 把本栈归入 dev 工程
    $composeArgs = @(
        'compose', '--env-file', '.env',
        '--project-name', 'aps-stack',
        '-f', 'deploy/docker-compose.yml'
    )
    $downArgs = $composeArgs + @('down', '--remove-orphans')
    if ($RemoveVolumes) {
        $downArgs += '-v'
    }

    Write-Step '停止并移除容器/网络'
    if ((Invoke-DockerCmd -DockerArgs $downArgs) -ne 0) {
        Stop-WithHelp 'docker compose down 执行失败' @(
            '确认 .env 存在且未被占用：Get-Content .env',
            '查看残留容器：docker ps -a --filter name=aps-stack'
        )
    }

    if ($RemoveVolumes) {
        Write-Host '容器与数据卷已删除，下次 stack-up 会重新初始化数据库与 MinIO 桶。' -ForegroundColor Green
    }
    else {
        Write-Host '容器已停止，数据卷保留；下次 stack-up 会复用现有数据。' -ForegroundColor Green
    }
}
finally {
    Pop-Location
}
