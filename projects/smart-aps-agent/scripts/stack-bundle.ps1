#Requires -Version 5.1
<#
.SYNOPSIS
    生成 APS 整栈离线部署包（镜像 tar + 制品 + 编排/脚本 + 清单）。

.DESCRIPTION
    等价 Bash 脚本：scripts/stack-bundle.sh
    产物默认输出到**仓库之外**的 ..\_aps-artifacts\dist-offline（约 548MB，含镜像 tar），
    避免大体量产物进入工作区/被误提交。仓库内只保留生成脚本与文档。
    可直接整体拷贝到无外网的机器：
      1) docker load -i images/aps-stack-images.tar
      2) 编辑 .env（从 .env.example 复制，替换密码/JWT）
      3) .\scripts\stack-up.ps1 -SkipPull
    目录结构刻意与仓库一致（deploy/、aps-admin/target/、aps-frontend/dist、scripts/），
    这样 compose 的相对挂载路径无需改动。

.PARAMETER OutputDir
    输出目录。缺省为仓库同级的 _aps-artifacts\dist-offline（不在 git 工作树内）；
    相对路径按仓库根目录解析，绝对路径直接使用。

.PARAMETER SkipImages
    只收集制品与脚本，不导出镜像 tar（体积小，适合已有镜像仓库的场景）。

.EXAMPLE
    .\scripts\stack-bundle.ps1
.EXAMPLE
    .\scripts\stack-bundle.ps1 -OutputDir D:\aps-offline -SkipImages
#>
[CmdletBinding()]
param(
    [string]$OutputDir = '',
    [switch]$SkipImages
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot

function Write-Step { param([string]$Message) Write-Host "[..] $Message" -ForegroundColor Cyan }
function Stop-WithHelp {
    param([string]$Message, [string[]]$Hints = @())
    Write-Host "[ERROR] $Message" -ForegroundColor Red
    foreach ($hint in $Hints) { Write-Host "  - $hint" -ForegroundColor Yellow }
    exit 1
}

function Get-EnvMap {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    foreach ($line in [System.IO.File]::ReadAllLines($Path, $utf8)) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { $map[$matches[1]] = $matches[2].Trim() }
    }
    return $map
}

try {
    if ([System.IO.Path]::IsPathRooted($OutputDir)) {
        $outDir = $OutputDir
    }
    elseif ($OutputDir) {
        $outDir = Join-Path $repoRoot $OutputDir
    }
    else {
        # 默认落到仓库同级目录，避免 500MB+ 产物进入 git 工作树
        $outDir = Join-Path (Split-Path -Parent $repoRoot) '_aps-artifacts\dist-offline'
    }
    $outDir = [System.IO.Path]::GetFullPath($outDir)

    $jarPath = Join-Path $repoRoot 'aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar'
    $distPath = Join-Path $repoRoot 'aps-frontend/dist'
    if (-not (Test-Path $jarPath)) { Stop-WithHelp "未找到后端制品：$jarPath" @('构建制品由 W9 统一交付，本脚本不执行 mvn package') }
    if (-not (Test-Path (Join-Path $distPath 'index.html'))) { Stop-WithHelp "未找到前端产物：$distPath/index.html" @('请先由前端负责人完成 npm run build') }

    Write-Step "创建离线目录 $outDir"
    foreach ($dir in @('deploy/backend', 'deploy/initdb', 'aps-admin/target', 'aps-frontend/dist', 'aps-frontend/nginx', 'scripts', 'images', 'docs/deploy')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $outDir $dir) | Out-Null
    }

    Write-Step '复制编排、脚本与制品'
    Copy-Item 'deploy/docker-compose.yml' (Join-Path $outDir 'deploy/docker-compose.yml') -Force
    Copy-Item 'deploy/backend/Dockerfile' (Join-Path $outDir 'deploy/backend/Dockerfile') -Force
    Copy-Item 'deploy/initdb/01-extensions.sql' (Join-Path $outDir 'deploy/initdb/01-extensions.sql') -Force
    Copy-Item '.env.example' (Join-Path $outDir '.env.example') -Force
    foreach ($s in @('stack-up.ps1', 'stack-down.ps1', 'stack-up.sh', 'stack-down.sh')) {
        Copy-Item (Join-Path 'scripts' $s) (Join-Path $outDir "scripts/$s") -Force
    }
    Copy-Item 'docs/deploy/docker-compose.md' (Join-Path $outDir 'docs/deploy/docker-compose.md') -Force
    Copy-Item $jarPath (Join-Path $outDir 'aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar') -Force
    Copy-Item 'aps-frontend/nginx/default.conf.template' (Join-Path $outDir 'aps-frontend/nginx/default.conf.template') -Force
    Copy-Item (Join-Path $distPath '*') (Join-Path $outDir 'aps-frontend/dist') -Recurse -Force

    $envMap = Get-EnvMap -Path (Join-Path $repoRoot '.env')
    $minioImage = if ($envMap.ContainsKey('MINIO_IMAGE') -and $envMap['MINIO_IMAGE']) { $envMap['MINIO_IMAGE'] } else { 'pgsty/minio:RELEASE.2026-08-04T00-00-00Z' }
    $backendImage = if ($envMap.ContainsKey('APS_BACKEND_IMAGE') -and $envMap['APS_BACKEND_IMAGE']) { $envMap['APS_BACKEND_IMAGE'] } else { 'aps-stack/aps-backend:local' }
    $frontendImage = if ($envMap.ContainsKey('APS_FRONTEND_IMAGE') -and $envMap['APS_FRONTEND_IMAGE']) { $envMap['APS_FRONTEND_IMAGE'] } else { 'nginxinc/nginx-unprivileged:1.31-alpine' }
    $images = @('postgres:15', 'redis:7', $minioImage, $backendImage, $frontendImage)

    $imageLines = @()
    if (-not $SkipImages) {
        Write-Step '导出镜像 tar（首次执行耗时较长）'
        foreach ($img in $images) {
            & docker image inspect $img *> $null
            if ($LASTEXITCODE -ne 0) {
                Stop-WithHelp "本机缺少镜像：$img" @(
                    '先执行 .\scripts\stack-up.ps1 完成拉取/构建，再生成离线包',
                    '或改在已有镜像的机器上导出'
                )
            }
        }
        $tarPath = Join-Path $outDir 'images/aps-stack-images.tar'
        & docker save -o $tarPath @images
        if ($LASTEXITCODE -ne 0) { Stop-WithHelp 'docker save 失败' @('确认 Docker 守护进程正常且磁盘空间充足') }
        $imageLines += "images/aps-stack-images.tar"
        foreach ($img in $images) { $imageLines += "  image: $img" }
    }
    else {
        Write-Step '已跳过镜像导出（-SkipImages）'
        foreach ($img in $images) { $imageLines += "  image: $img （需目标机器已有）" }
    }

    Write-Step '写入清单与校验值'
    $jarHash = (Get-FileHash -Path (Join-Path $outDir 'aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar') -Algorithm SHA256).Hash
    $jarSize = (Get-Item (Join-Path $outDir 'aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar')).Length
    $lines = @(
        'APS 整栈离线部署包',
        "生成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')",
        "主机: $env:COMPUTERNAME",
        '',
        "jar: aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar",
        "jar_size: $jarSize",
        "jar_sha256: $jarHash",
        "dist: aps-frontend/dist (index.html 生成时间 $((Get-Item (Join-Path $distPath 'index.html')).LastWriteTime))",
        '',
        '镜像:'
    ) + $imageLines + @(
        '',
        '安装步骤:',
        '  1) docker load -i images/aps-stack-images.tar',
        '  2) Copy-Item .env.example .env 并替换 POSTGRES_PASSWORD / REDIS_PASSWORD / MINIO_ROOT_PASSWORD / APS_SECURITY_MODE / APS_SECURITY_JWT_SECRET',
        '  3) .\scripts\stack-up.ps1 -SkipPull    （Linux: ./scripts/stack-up.sh --skip-pull）',
        '  4) curl http://127.0.0.1:8080/api/v1/health  （若 .env 改了 APS_BACKEND_PORT 请对应替换）',
        '',
        '说明: 部署链路不编译业务代码；后端挂载 jar，前端挂载 dist。'
    )
    $manifestPath = Join-Path $outDir 'MANIFEST.txt'
    [System.IO.File]::WriteAllLines($manifestPath, $lines, (New-Object System.Text.UTF8Encoding($false)))

    $totalBytes = (Get-ChildItem $outDir -Recurse -File | Measure-Object -Property Length -Sum).Sum
    Write-Host ''
    Write-Host "离线包已生成（仓库外）：$outDir" -ForegroundColor Green
    Write-Host ("  总大小 {0:N1} MB   jar SHA256={1}" -f ($totalBytes / 1MB), $jarHash)
    Write-Host "  清单：$manifestPath"
    Write-Host '  拷到目标机器后：docker load -i images/aps-stack-images.tar -> .\scripts\stack-up.ps1 -SkipPull'
}
finally {
    Pop-Location
}
