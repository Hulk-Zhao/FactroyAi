# =============================================================================
# 衡策 APS · 本地复现 CI 构建（PowerShell 5.1+ / PowerShell 7）
#   与 .github/workflows/ci.yml、.gitlab-ci.yml 保持同一套命令。
#   示例：
#     .\scripts\ci-build.ps1                                # 后端打包 + 前端构建
#     .\scripts\ci-build.ps1 -RunTests -RunStaticAnalysis   # 含单测/覆盖率与静态检查
#     .\scripts\ci-build.ps1 -Target frontend -UseProxy     # 走本机 127.0.0.1:7890 代理
# =============================================================================
[CmdletBinding()]
param(
    [ValidateSet('all', 'backend', 'frontend')]
    [string]$Target = 'all',

    [switch]$RunTests,

    [switch]$RunStaticAnalysis,

    [switch]$UseProxy,

    [string]$ProxyUrl = 'http://127.0.0.1:7890'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Assert-Command {
    param([string]$Name, [string]$Hint)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少命令 '$Name'。$Hint"
    }
}

function Set-ProxyEnv {
    param([string]$Url)

    $uri = [System.Uri]$Url
    if (-not $uri.IsAbsoluteUri) {
        throw "代理地址不合法：$Url"
    }
    $env:HTTP_PROXY = $Url
    $env:HTTPS_PROXY = $Url
    $env:NO_PROXY = 'localhost,127.0.0.1'
    $env:npm_config_proxy = $Url
    $env:npm_config_https_proxy = $Url
    $proxyOpts = "-Dhttp.proxyHost=$($uri.Host) -Dhttp.proxyPort=$($uri.Port) " +
        "-Dhttps.proxyHost=$($uri.Host) -Dhttps.proxyPort=$($uri.Port)"
    $env:MAVEN_OPTS = ("$env:MAVEN_OPTS $proxyOpts").Trim()
    Write-Host "已启用代理：$Url（HTTP_PROXY / npm / MAVEN_OPTS）" -ForegroundColor Yellow
}

function Invoke-Step {
    param([string]$Title, [scriptblock]$Action)
    Write-Host "==> $Title" -ForegroundColor Cyan
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "步骤失败：$Title（退出码 $LASTEXITCODE）"
    }
}

if ($UseProxy) {
    Set-ProxyEnv -Url $ProxyUrl
}

$mvnCommon = @('-B', '-ntp', '-DskipTests', '-Dcheckstyle.skip=true', '-Dpmd.skip=true')

if ($Target -in @('all', 'backend')) {
    Assert-Command -Name 'mvn' -Hint '请安装 Maven 3.9+，并确保 JAVA_HOME 指向 JDK 21。'

    Invoke-Step '后端打包（skipTests）' {
        mvn @mvnCommon package
    }

    if ($RunTests) {
        Invoke-Step '后端单元测试 + JaCoCo 覆盖率' {
            mvn -B -ntp -Dcheckstyle.skip=true -Dpmd.skip=true `
                org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test `
                org.jacoco:jacoco-maven-plugin:0.8.12:report
        }
    }

    if ($RunStaticAnalysis) {
        Invoke-Step '后端静态检查（Checkstyle / PMD，Skipped tests）' {
            mvn -B -ntp -DskipTests verify
        }
    }
}

if ($Target -in @('all', 'frontend')) {
    Assert-Command -Name 'npm' -Hint '请安装 Node.js（20 或 22 LTS）与 npm。'

    Push-Location (Join-Path $repoRoot 'aps-frontend')
    try {
        Invoke-Step '前端依赖安装（npm ci）' {
            npm ci
        }

        if ($RunStaticAnalysis) {
            $pkg = Get-Content 'package.json' -Raw | ConvertFrom-Json
            $scriptNames = $pkg.scripts.PSObject.Properties.Name
            if ($scriptNames -contains 'lint') {
                Invoke-Step 'ESLint' { npm run lint }
            }
            else {
                Write-Warning 'package.json 尚无 lint 脚本（P0-02 待落地），跳过 ESLint。'
            }
            if ($scriptNames -contains 'format:check') {
                Invoke-Step 'Prettier 格式检查' { npm run format:check }
            }
            else {
                Write-Warning 'package.json 尚无 format:check 脚本（P0-02 待落地），跳过 Prettier。'
            }
        }

        Invoke-Step '前端类型检查 + 构建' {
            npm run build
        }
    }
    finally {
        Pop-Location
    }
}

# ----------------------------- 产物汇总 --------------------------------------
Write-Host ''
Write-Host '构建产物：' -ForegroundColor Green

$jar = Get-ChildItem (Join-Path $repoRoot 'aps-admin/target') -Filter 'aps-admin-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($jar) {
    Write-Host ("  jar  : {0} ({1:N1} MB)" -f $jar.FullName, ($jar.Length / 1MB))
}
else {
    Write-Warning '  未找到 aps-admin/target/aps-admin-*.jar'
}

$distIndex = Join-Path $repoRoot 'aps-frontend/dist/index.html'
if (Test-Path $distIndex) {
    $distSize = (Get-ChildItem (Join-Path $repoRoot 'aps-frontend/dist') -Recurse -File |
        Measure-Object -Property Length -Sum).Sum
    Write-Host ("  dist : {0} ({1:N1} MB)" -f (Join-Path $repoRoot 'aps-frontend/dist'), ($distSize / 1MB))
}
else {
    Write-Warning '  未找到 aps-frontend/dist/index.html'
}

Write-Host '完成。' -ForegroundColor Green
