#Requires -Version 5.1
<#
.SYNOPSIS
    一键启动 APS 整栈（PostgreSQL 15 / Redis 7 / MinIO / aps-backend / aps-frontend）。

.DESCRIPTION
    等价 Bash 脚本：scripts/stack-up.sh
    使用 deploy/docker-compose.yml（与 dev 基础设施栈隔离，工程名固定 aps-stack）。
    M2-QA-03 起部署链路不再编译业务代码：后端挂载已构建的 fat jar，前端挂载 dist 静态产物。

    流程与分阶段计时：
      [1] 镜像拉取      -> postgres/redis/minio/前端运行镜像
      [2] 镜像构建      -> 后端运行时镜像（仅 JRE + curl，不跑 mvn）
      [3] 容器启动      -> 中间件 healthy + 后端容器创建
      [4] Flyway 迁移   -> 后端日志出现迁移完成/已是最新
      [5] 健康检查      -> GET /api/v1/health 首次 200
      [6] 前端启动      -> 前端容器 healthy
      额外：制品一致性（容器内 /app/app.jar 的 SHA256 必须等于仓库制品）
    结束时打印各阶段秒数与总耗时。

.PARAMETER SkipBuild
    使用已有镜像，不重新构建后端运行时镜像（镜像不存在时 compose 仍会自动构建）。

.PARAMETER SkipPull
    跳过中间件/前端镜像拉取（离线包已导入或镜像已缓存时使用）。

.PARAMETER NoWait
    只执行 up -d，不等待 Flyway/健康检查/前端就绪，也不建 MinIO 桶（快速调试用）。

.EXAMPLE
    .\scripts\stack-up.ps1
.EXAMPLE
    .\scripts\stack-up.ps1 -SkipPull
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipPull,
    [switch]$NoWait
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

$stageResults = New-Object System.Collections.ArrayList
$totalSw = [System.Diagnostics.Stopwatch]::StartNew()

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

function Add-StageResult {
    param([string]$Name, [double]$Seconds, [string]$Detail = '')
    [void]$stageResults.Add([pscustomobject]@{
            Stage  = $Name
            Seconds = [math]::Round($Seconds, 1)
            Detail = $Detail
        })
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
    # 显式按 UTF-8 + 任意换行符读取：Windows PowerShell 5.1 的 Get-Content
    # 在「无 BOM + LF」的 .env 上会按默认代码页解码并漏行，导致必填校验误报。
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    foreach ($line in [System.IO.File]::ReadAllLines($Path, $utf8)) {
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

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path $repoRoot $Path)
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

function Wait-ContainerHealthy {
    param([string]$ContainerName, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = (& docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $ContainerName 2>$null)
        if ($LASTEXITCODE -eq 0 -and $status) {
            $status = $status.Trim()
            if ($status -eq 'healthy' -or $status -eq 'running') { return $status }
            if ($status -eq 'unhealthy') { return 'unhealthy' }
        }
        Start-Sleep -Milliseconds 1500
    }
    return 'timeout'
}

function Wait-FlywayFinished {
    param([string[]]$ComposeArgs, [int]$TimeoutSeconds = 240)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $logText = (& docker @ComposeArgs logs --no-color --tail 500 aps-backend 2>&1 | Out-String)
        if ($logText -match 'Successfully applied|is up to date\. No migration necessary|now at version') {
            return @{ Ok = $true; Log = $logText }
        }
        if ($logText -match 'FlywayException|Migration .* failed|APPLICATION FAILED TO START') {
            return @{ Ok = $false; Log = $logText }
        }
        Start-Sleep -Seconds 2
    }
    return @{ Ok = $false; Log = (& docker @ComposeArgs logs --no-color --tail 200 aps-backend 2>&1 | Out-String) }
}

function Wait-HealthOk {
    param([int]$Port, [int]$TimeoutSeconds = 240)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = ''
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/v1/health" `
                -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                return @{ Ok = $true; Body = $response.Content }
            }
        }
        catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    return @{ Ok = $false; Body = $lastError }
}

function Get-JarLibraryCount {
    param([string]$JarPath)
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        try {
            return ($zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*.jar' }).Count
        }
        finally {
            $zip.Dispose()
        }
    }
    catch {
        return -1
    }
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
    $envCopied = $false
    if (-not (Test-Path $envPath)) {
        if (-not (Test-Path $envExamplePath)) {
            Stop-WithHelp '缺少 .env 与 .env.example，无法启动' @('请从版本库恢复 .env.example 后重试')
        }
        Copy-Item $envExamplePath $envPath
        $envCopied = $true
        Write-Warn '未找到 .env，已从 .env.example 复制；上线前务必替换所有默认密码、APS_SECURITY_MODE 与 APS_SECURITY_JWT_SECRET'
    }

    # ---- 必填变量校验：缺失/为空即失败，绝不静默取默认值 ----
    Write-Step '校验 .env 必填变量'
    $envMap = Get-EnvMap -Path $envPath
    $requiredKeys = @('POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'MINIO_ROOT_PASSWORD',
        'APS_SECURITY_MODE', 'APS_SECURITY_JWT_SECRET')
    $missingKeys = @()
    foreach ($key in $requiredKeys) {
        $fromFile = if ($envMap.ContainsKey($key)) { $envMap[$key].Trim() } else { '' }
        $fromProcess = [Environment]::GetEnvironmentVariable($key)
        if (-not $fromFile -and -not $fromProcess) { $missingKeys += $key }
    }
    if ($missingKeys.Count -gt 0) {
        Stop-WithHelp ("必填变量缺失或为空：{0}" -f ($missingKeys -join ', ')) @(
            "请编辑 $envPath，为上述变量填入非空值后重试",
            '模板可从 .env.example 复制；部署环节不会替你填充默认密码',
            '对应的 compose 变量声明带 :? 保护，缺失时 docker compose 也会直接报错'
        )
    }
    if ($envCopied) {
        Write-Warn '当前 .env 使用模板中的开发默认密码，仅可用于本机验证；正式环境必须逐项替换'
    }
    foreach ($key in $requiredKeys) {
        Write-Host ("    {0} 已设置" -f $key) -ForegroundColor DarkGray
    }

    # ---- 制品前置检查：缺失/瘦包直接失败，避免拿旧包或空包糊过去 ----
    Write-Step '校验部署制品（jar / dist）'
    $jarPath = Resolve-RepoPath $(if ($envMap.ContainsKey('APS_BACKEND_JAR') -and $envMap['APS_BACKEND_JAR']) { $envMap['APS_BACKEND_JAR'] } else { 'aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar' })
    $distPath = Resolve-RepoPath $(if ($envMap.ContainsKey('APS_FRONTEND_DIST') -and $envMap['APS_FRONTEND_DIST']) { $envMap['APS_FRONTEND_DIST'] } else { 'aps-frontend/dist' })
    if (-not (Test-Path $jarPath)) {
        Stop-WithHelp "未找到后端制品：$jarPath" @(
            '构建制品由 W9 统一交付，部署环节不执行 mvn package',
            '请先取回 aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar，或在 .env 用 APS_BACKEND_JAR 指向已有 jar'
        )
    }
    $jarItem = Get-Item $jarPath
    if ($jarItem.Length -lt 50MB) {
        Stop-WithHelp ("后端制品疑似瘦包：{0:N0} B（<50MB）" -f $jarItem.Length) @(
            '正常 fat jar 约 181MB 且 BOOT-INF/lib 内有 100+ 个依赖',
            '请重新获取 W9 交付的完整制品，不要启动瘦包'
        )
    }
    $jarHash = (Get-FileHash -Path $jarPath -Algorithm SHA256).Hash
    $libCount = Get-JarLibraryCount -JarPath $jarPath
    Write-Host ("    jar  {0}" -f $jarPath) -ForegroundColor DarkGray
    Write-Host ("         {0:N0} B  SHA256={1}  BOOT-INF/lib={2}" -f $jarItem.Length, $jarHash, $(if ($libCount -ge 0) { $libCount } else { 'n/a' })) -ForegroundColor DarkGray
    if ($libCount -ge 0 -and $libCount -eq 0) {
        Stop-WithHelp '后端制品 BOOT-INF/lib 为空，判定为瘦包，已中止启动' @('请从 W9 取回完整制品')
    }
    if (-not (Test-Path (Join-Path $distPath 'index.html'))) {
        Stop-WithHelp "未找到前端静态产物：$distPath/index.html" @(
            '请先在 aps-frontend 执行 npm run build（由前端负责人统一构建），或在 .env 用 APS_FRONTEND_DIST 指向已有 dist'
        )
    }
    Write-Host ("    dist {0}" -f $distPath) -ForegroundColor DarkGray

    # 显式 --project-name：根 .env 的 COMPOSE_PROJECT_NAME=aps-dev 会覆盖 compose 顶层 name，
    # 不指定会与 dev 栈共享 project label（down --remove-orphans 有误删风险）。
    $composeArgs = @(
        'compose', '--env-file', '.env',
        '--project-name', 'aps-stack',
        '-f', 'deploy/docker-compose.yml'
    )

    Write-Step '检查入口端口占用'
    $frontendPort = Get-EnvInt -Map $envMap -Key 'APS_FRONTEND_PORT' -Default 80
    $backendPort = Get-EnvInt -Map $envMap -Key 'APS_BACKEND_PORT' -Default 8080
    $conflicts = @()
    foreach ($spec in @(
            @{ Name = '前端入口'; Port = $frontendPort },
            @{ Name = '后端 API'; Port = $backendPort }
        )) {
        $owner = Get-PortConflict -Port $spec.Port
        if ($owner) {
            $conflicts += "$($spec.Name) $($spec.Port) -> $owner"
        }
        else {
            Write-Host "    $($spec.Name) $($spec.Port) 可用" -ForegroundColor DarkGray
        }
    }
    if ($conflicts.Count -gt 0) {
        Stop-WithHelp '入口端口冲突，已中止启动（未改动任何运行中的容器）' ($conflicts + @(
                '在 .env 中设置 APS_FRONTEND_PORT / APS_BACKEND_PORT 换用其他端口后重试',
                '或停止占用该端口的程序（Windows 上 80 端口常被 IIS / http.sys 占用）'
            ))
    }

    $stageSw = [System.Diagnostics.Stopwatch]::StartNew()
    if (-not $SkipPull) {
        Write-Step '[1] 拉取镜像（中间件 + 前端运行镜像）'
        if ((Invoke-DockerCmd -DockerArgs ($composeArgs + @('pull', 'postgres', 'redis', 'minio', 'aps-frontend'))) -ne 0) {
            Stop-WithHelp '镜像拉取失败' @(
                '国内网络请为 Docker Desktop 配置 clash 代理：Settings -> Resources -> Proxies -> Manual，HTTP/HTTPS 都填 http://127.0.0.1:7890',
                'MinIO 使用社区维护分支镜像；若内网 Harbor 有镜像，在 .env 设置 MINIO_IMAGE',
                '离线部署可使用 scripts/stack-bundle.ps1 打包好的镜像 tar（docker load 后加 -SkipPull）'
            )
        }
    }
    else {
        Write-Step '[1] 跳过镜像拉取（-SkipPull）'
    }
    $stageSw.Stop()
    Add-StageResult -Name '[1] 镜像拉取' -Seconds $stageSw.Elapsed.TotalSeconds -Detail $(if ($SkipPull) { '已跳过' } else { 'postgres/redis/minio/aps-frontend' })

    $stageSw = [System.Diagnostics.Stopwatch]::StartNew()
    if (-not $SkipBuild) {
        Write-Step '[2] 构建后端运行时镜像（不编译业务代码）'
        if ((Invoke-DockerCmd -DockerArgs ($composeArgs + @('build', 'aps-backend'))) -ne 0) {
            Stop-WithHelp '后端运行时镜像构建失败' @(
                '该镜像仅安装 JRE 21 + curl，不含业务代码；失败多为网络/apt 源问题',
                '检查 Docker Desktop 代理设置后重试',
                '查看构建日志：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml build aps-backend'
            )
        }
    }
    else {
        Write-Step '[2] 跳过镜像构建（-SkipBuild）'
    }
    $stageSw.Stop()
    Add-StageResult -Name '[2] 镜像构建' -Seconds $stageSw.Elapsed.TotalSeconds -Detail $(if ($SkipBuild) { '已跳过' } else { 'aps-backend 运行时镜像' })

    $stageSw = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Step '[3] 启动中间件与后端容器'
    if ((Invoke-DockerCmd -DockerArgs ($composeArgs + @('up', '-d', 'postgres', 'redis', 'minio'))) -ne 0) {
        Stop-WithHelp '中间件容器启动失败' @('查看状态：docker compose ... ps；日志：docker compose ... logs postgres redis minio')
    }
    foreach ($pair in @(@{ Name = 'aps-stack-postgres'; Label = 'postgres' },
            @{ Name = 'aps-stack-redis'; Label = 'redis' },
            @{ Name = 'aps-stack-minio'; Label = 'minio' })) {
        $status = Wait-ContainerHealthy -ContainerName $pair.Name -TimeoutSeconds 120
        if ($status -eq 'unhealthy' -or $status -eq 'timeout') {
            Stop-WithHelp ("{0} 未在 120s 内就绪（状态：{1}）" -f $pair.Label, $status) @(
                "查看日志：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml logs $($pair.Label)",
                '确认 .env 中 POSTGRES_PASSWORD / REDIS_PASSWORD / MINIO_ROOT_PASSWORD 非空且格式正确'
            )
        }
        Write-Host ("    {0} -> {1}" -f $pair.Label, $status) -ForegroundColor DarkGray
    }
    if ((Invoke-DockerCmd -DockerArgs ($composeArgs + @('up', '-d', 'aps-backend'))) -ne 0) {
        Stop-WithHelp '后端容器启动失败' @(
            '查看日志：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml logs aps-backend',
            '确认 APS_BACKEND_JAR 指向的 fat jar 存在且未损坏'
        )
    }
    $stageSw.Stop()
    Add-StageResult -Name '[3] 容器启动' -Seconds $stageSw.Elapsed.TotalSeconds -Detail '中间件 healthy + 后端容器创建'

    if (-not $NoWait) {
        $stageSw = [System.Diagnostics.Stopwatch]::StartNew()
        Write-Step '[4] 等待 Flyway 迁移完成'
        $flyway = Wait-FlywayFinished -ComposeArgs $composeArgs -TimeoutSeconds 240
        $stageSw.Stop()
        if (-not $flyway.Ok) {
            Write-Host $flyway.Log
            Stop-WithHelp 'Flyway 未在 240s 内完成或迁移失败' @(
                '用 docker compose ... logs aps-backend 查看完整启动日志',
                '确认数据库卷为空库且 db/migration 已随 fat jar 打入 BOOT-INF/classes/db/migration',
                '已应用的 V1–V6 迁移文件不得修改，校验和不匹配时只能 flyway repair'
            )
        }
        $flywayLine = ($flyway.Log -split "`n" | Where-Object {
                $_ -match 'Successfully applied|is up to date\. No migration necessary|now at version'
            } | Select-Object -Last 1).Trim()
        Write-Host ("    {0}" -f $flywayLine) -ForegroundColor DarkGray
        Add-StageResult -Name '[4] Flyway 完成' -Seconds $stageSw.Elapsed.TotalSeconds -Detail $flywayLine

        $stageSw = [System.Diagnostics.Stopwatch]::StartNew()
        Write-Step '[5] 等待 /api/v1/health 首次 200'
        $health = Wait-HealthOk -Port $backendPort -TimeoutSeconds 240
        $stageSw.Stop()
        if (-not $health.Ok) {
            Stop-WithHelp '健康检查未在 240s 内返回 200' @(
                "手工验证：curl.exe http://127.0.0.1:$backendPort/api/v1/health",
                '查看后端日志：docker compose ... logs aps-backend'
            )
        }
        Write-Host ("    HTTP 200 {0}" -f (($health.Body -replace '\s+', ' ').Trim())) -ForegroundColor DarkGray
        Add-StageResult -Name '[5] 健康检查 200' -Seconds $stageSw.Elapsed.TotalSeconds -Detail 'GET /api/v1/health'

        $stageSw = [System.Diagnostics.Stopwatch]::StartNew()
        Write-Step '[6] 启动前端容器'
        if ((Invoke-DockerCmd -DockerArgs ($composeArgs + @('up', '-d', 'aps-frontend'))) -ne 0) {
            Stop-WithHelp '前端容器启动失败' @('查看日志：docker compose ... logs aps-frontend')
        }
        $frontendStatus = Wait-ContainerHealthy -ContainerName 'aps-stack-frontend' -TimeoutSeconds 60
        $stageSw.Stop()
        if ($frontendStatus -eq 'unhealthy' -or $frontendStatus -eq 'timeout') {
            Stop-WithHelp ("前端容器未就绪（状态：{0}）" -f $frontendStatus) @(
                '确认 aps-frontend/dist/index.html 存在且可读',
                '查看日志：docker compose ... logs aps-frontend'
            )
        }
        Add-StageResult -Name '[6] 前端启动' -Seconds $stageSw.Elapsed.TotalSeconds -Detail 'aps-frontend healthy'

        # ---- 制品一致性：容器内实际运行的 jar 必须与仓库制品逐字节一致 ----
        Write-Step '校验容器内制品 SHA256'
        $containerHash = (@(& docker @composeArgs exec -T aps-backend sha256sum /app/app.jar 2>$null |
                ForEach-Object { ($_ -split '\s+')[0] }) + @(''))[0]
        if ($LASTEXITCODE -ne 0 -or -not $containerHash) {
            Write-Warn '无法读取容器内 jar 的 SHA256（exec 失败），请手工核对'
        }
        elseif ($containerHash.ToUpper() -ne $jarHash.ToUpper()) {
            Stop-WithHelp "制品不一致：容器内 $containerHash，仓库 $jarHash" @('容器可能挂载了旧包，请检查 APS_BACKEND_JAR')
        }
        else {
            Write-Host ("    SHA256 一致：{0}" -f $jarHash) -ForegroundColor DarkGray
        }

        Write-Step '初始化 MinIO 附件桶'
        $initArgs = $composeArgs + @('--profile', 'init', 'run', '--rm', '--no-deps', '-T', 'minio-init')
        if ((Invoke-DockerCmd -DockerArgs $initArgs) -ne 0) {
            Write-Warn 'MinIO 附件桶初始化失败（不影响其他服务运行）'
            Write-Warn '可手动重试：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml --profile init run --rm minio-init'
        }
    }
    else {
        Write-Step '-NoWait：启动全部服务但不等待健康检查'
        if ((Invoke-DockerCmd -DockerArgs ($composeArgs + @('up', '-d'))) -ne 0) {
            Stop-WithHelp '整栈启动失败' @('查看状态：docker compose ... ps')
        }
    }

    $totalSw.Stop()
    Write-Host ''
    Write-Host '分阶段耗时（实测）' -ForegroundColor Green
    foreach ($row in $stageResults) {
        Write-Host ("    {0,-16} {1,6:N1} s   {2}" -f $row.Stage, $row.Seconds, $row.Detail)
    }
    Write-Host ("    {0,-16} {1,6:N1} s" -f '总耗时', $totalSw.Elapsed.TotalSeconds) -ForegroundColor Green
    Write-Host ''

    Write-Step 'docker compose ps'
    & docker @composeArgs ps | Out-Host

    Write-Host ''
    Write-Host 'APS 整栈已启动' -ForegroundColor Green
    Write-Host "  前端入口   http://localhost:$frontendPort"
    Write-Host "  后端健康   http://localhost:$backendPort/api/v1/health"
    Write-Host '  中间件     postgres:5432 / redis:6379 / minio:9000（仅容器内网）'
    if ($NoWait) {
        Write-Host '  当前未等待健康检查，也未初始化 MinIO 桶；请查看 ps 状态后再做验收。' -ForegroundColor Yellow
    }
    Write-Host ''
    Write-Host '状态：docker compose --env-file .env --project-name aps-stack -f deploy/docker-compose.yml ps'
    Write-Host '停止：.\scripts\stack-down.ps1            （保留数据卷）'
    Write-Host '清数据：.\scripts\stack-down.ps1 -RemoveVolumes'
}
finally {
    Pop-Location
}
