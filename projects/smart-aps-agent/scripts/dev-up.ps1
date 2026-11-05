#Requires -Version 5.1
<#
.SYNOPSIS
    启动 APS 本地开发依赖栈（PostgreSQL 15 / Redis 7 / MinIO）。

.DESCRIPTION
    等价 Bash 脚本：scripts/dev-up.sh
    流程：检查 Docker CLI / 守护进程 / Compose v2 -> 准备 .env ->（可选）调试端口检查
          -> 拉取镜像 -> 启动并等待健康检查 -> 输出连接信息。

.PARAMETER ExposeDebug
    叠加 deploy/docker-compose.dev.expose.yml，把依赖端口仅发布到宿主机 127.0.0.1，
    便于 IDE 直连（PG 5432 / Redis 6379 / MinIO 9010、9011，可在 .env 调整）。

.PARAMETER SkipPull
    跳过镜像拉取，直接使用本地已有镜像（离线或镜像已缓存时使用）。

.PARAMETER NoWait
    不等待健康检查通过（只做 up -d，适合快速调试）。

.EXAMPLE
    .\scripts\dev-up.ps1
.EXAMPLE
    .\scripts\dev-up.ps1 -ExposeDebug
#>
[CmdletBinding()]
param(
    [switch]$ExposeDebug,
    [switch]$SkipPull,
    [switch]$NoWait
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

function Get-EnvMap {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    foreach ($line in Get-Content -Path $Path) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $map[$matches[1]] = $matches[2].Trim()
        }
    }
    return $map
}

function Get-EnvInt {
    param($Map, [string]$Key, [int]$Default)
    if ($Map.ContainsKey($Key) -and $Map[$Key]) {
        $parsed = 0
        if ([int]::TryParse($Map[$Key], [ref]$parsed)) { return $parsed }
    }
    return $Default
}

function Get-PortConflict {
    param([int]$Port)
    if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($conn) {
            $procName = (Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue).ProcessName
            if ($procName) {
                return "端口已被 PID=$($conn.OwningProcess) ($procName) 占用"
            }
            return "端口已被 PID=$($conn.OwningProcess) 占用"
        }
    }
    if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
        return '端口已有监听（未能获取占用进程）'
    }
    return $null
}

try {
    Write-Step '检查 Docker CLI'
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Stop-WithHelp '未找到 docker 命令' @(
            '安装 Docker Desktop（含 Compose v2）：https://docs.docker.com/desktop/install/windows-install/',
            '安装完成后重新打开终端再试'
        )
    }

    Write-Step '检查 Docker 守护进程'
    if ((Invoke-DockerCmd -DockerArgs @('info') -Quiet) -ne 0) {
        Stop-WithHelp 'Docker 守护进程未运行（docker info 失败）' @(
            '启动 Docker Desktop，等待鲸鱼图标变为绿色（或 WSL2 中 docker-desktop 处于 Running）',
            '若刚启动，通常需要 1~2 分钟；可稍后重试',
            '公司安全软件可能拦截 Docker 的 npipe，需加白名单'
        )
    }

    Write-Step '检查 Docker Compose v2'
    if ((Invoke-DockerCmd -DockerArgs @('compose', 'version') -Quiet) -ne 0) {
        Stop-WithHelp '未检测到 docker compose v2 插件' @(
            'Docker Desktop 自带 compose v2；Linux 请安装 docker-compose-plugin',
            '不要使用旧版 docker-compose（v1）执行本项目脚本'
        )
    }

    Write-Step '准备 .env'
    $envPath = Join-Path $repoRoot '.env'
    $envExamplePath = Join-Path $repoRoot '.env.example'
    if (-not (Test-Path $envPath)) {
        if (-not (Test-Path $envExamplePath)) {
            Stop-WithHelp '缺少 .env 与 .env.example，无法启动' @('请从版本库恢复 .env.example 后重试')
        }
        Copy-Item $envExamplePath $envPath
        Write-Warn '未找到 .env，已从 .env.example 复制；请按需修改数据库/Redis/MinIO 密码与 JWT 密钥'
    }

    $composeArgs = @('compose', '--env-file', '.env', '-f', 'deploy/docker-compose.dev.yml')

    if ($ExposeDebug) {
        $composeArgs += @('-f', 'deploy/docker-compose.dev.expose.yml')
        Write-Step '检查调试端口占用（仅绑定 127.0.0.1）'
        $envMap = Get-EnvMap -Path $envPath
        $portSpecs = @(
            @{ Name = 'PostgreSQL'; Key = 'POSTGRES_DEBUG_PORT'; Default = 5432 },
            @{ Name = 'Redis'; Key = 'REDIS_DEBUG_PORT'; Default = 6379 },
            @{ Name = 'MinIO API'; Key = 'MINIO_API_DEBUG_PORT'; Default = 9010 },
            @{ Name = 'MinIO Console'; Key = 'MINIO_CONSOLE_DEBUG_PORT'; Default = 9011 }
        )
        $conflicts = @()
        foreach ($spec in $portSpecs) {
            $port = Get-EnvInt -Map $envMap -Key $spec.Key -Default $spec.Default
            $owner = Get-PortConflict -Port $port
            if ($owner) {
                $conflicts += "$($spec.Name) $port -> $owner"
            }
            else {
                Write-Host "    $($spec.Name) $port 可用" -ForegroundColor DarkGray
            }
        }
        if ($conflicts.Count -gt 0) {
            Stop-WithHelp '调试端口冲突，已中止启动（未改动任何运行中的容器）' ($conflicts + @(
                    '修改 .env 中对应的 *_DEBUG_PORT 后重试',
                    '或先停止占用该端口的程序（如本机已有的 PostgreSQL/Redis）',
                    '不加 -ExposeDebug 时依赖栈不发布宿主端口，不会冲突'
                ))
        }
    }

    if (-not $SkipPull) {
        Write-Step '拉取镜像（首次执行耗时较长）'
        $pullArgs = $composeArgs + @('pull')
        if ((Invoke-DockerCmd -DockerArgs $pullArgs) -ne 0) {
            Stop-WithHelp '镜像拉取失败' @(
                '国内网络请为 Docker Desktop 配置 clash 代理：Settings -> Resources -> Proxies -> Manual，HTTP/HTTPS 都填 http://127.0.0.1:7890',
                'MinIO 使用社区维护分支镜像；若内网 Harbor 有镜像，在 .env 设置 MINIO_IMAGE',
                '镜像已缓存时，可加 -SkipPull 跳过拉取'
            )
        }
    }

    # 只对长期运行的服务做 --wait（minio-init 是一次性任务，退出会影响 wait 判定）
    $upArgs = $composeArgs + @('up', '-d', 'postgres', 'redis', 'minio')
    if (-not $NoWait) {
        $upArgs += @('--wait', '--wait-timeout', '180')
    }

    Write-Step '启动容器并等待健康检查'
    if ((Invoke-DockerCmd -DockerArgs $upArgs) -ne 0) {
        Stop-WithHelp '容器启动或健康检查未通过' @(
            '查看日志：docker compose --env-file .env -f deploy/docker-compose.dev.yml logs postgres redis minio',
            '查看状态：docker compose --env-file .env -f deploy/docker-compose.dev.yml ps',
            '镜像/网络问题：检查 Docker Desktop 代理（http://127.0.0.1:7890）或 MINIO_IMAGE',
            '端口冲突：去掉 -ExposeDebug 后重试'
        )
    }

    if (-not $NoWait) {
        Write-Step '初始化 MinIO 附件桶'
        $initArgs = $composeArgs + @('run', '--rm', '--no-deps', '-T', 'minio-init')
        if ((Invoke-DockerCmd -DockerArgs $initArgs) -ne 0) {
            Write-Warn 'MinIO 附件桶初始化失败（不影响 PostgreSQL/Redis/MinIO 运行）'
            Write-Warn '可手动重试：docker compose --env-file .env -f deploy/docker-compose.dev.yml run --rm minio-init'
        }
    }

    Write-Host ''
    Write-Host '本地依赖栈已就绪' -ForegroundColor Green
    Write-Host '  PostgreSQL  postgres:5432   db=aps  user=aps'
    Write-Host '  Redis       redis:6379      已开启 AOF'
    Write-Host '  MinIO       http://minio:9000 (S3 API) / http://minio:9001 (Console)'
    if ($ExposeDebug) {
        Write-Host '  IDE 直连（仅 127.0.0.1）：'
        Write-Host '    jdbc:postgresql://localhost:5432/aps'
        Write-Host '    redis localhost:6379'
        Write-Host '    http://localhost:9010 (MinIO API) / http://localhost:9011 (Console)'
    }
    else {
        Write-Host '  当前仅内网互通；IDE 需直连时重新执行：.\scripts\dev-up.ps1 -ExposeDebug'
    }
    Write-Host ''
    Write-Host '校验扩展：docker compose --env-file .env -f deploy/docker-compose.dev.yml exec postgres psql -U aps -d aps -c "\dx"'
    Write-Host '停止栈：  .\scripts\dev-down.ps1          （保留数据卷）'
    Write-Host '删除数据：.\scripts\dev-down.ps1 -RemoveVolumes'
}
finally {
    Pop-Location
}
