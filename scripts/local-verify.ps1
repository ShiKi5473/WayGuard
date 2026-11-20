<#
.SYNOPSIS
  在台灣本機執行 camera-sync 位置交叉驗證（含 TGOS 線上／批次門牌查詢）。

.DESCRIPTION
  GitHub Actions 的境外 IP 會被 TGOS 以 HTTP 403 阻擋，因此門牌驗證改在本機執行，
  再把 data/generated/location-checks.json（只含驗證結論與距離，不含 TGOS 原始座標；僅「位置不符」且門牌號對上者，
  另存投影到 OSM 道路上的修正座標）commit 回 repo，
  之後 CI 每週增量檢查會沿用這些結果。

  流程與 .github/workflows/update-cameras.yml 相同：
    1. 下載 Geofabrik 台灣 OSM 圖資（已存在且未過期則略過）
    2. 以 Docker（官方 debian 映像 + apt 的 osmium-tool）篩選並匯出 GeoJSONSeq
    3. ./gradlew :camera-sync:run --args="--osm-extract ..."

  前置需求：Docker Desktop 已啟動（若需重新擷取 OSM）；預設需設定環境變數 TGOS_APP_ID、TGOS_API_KEY
  （不會寫入任何檔案），或使用 -TgosResult 匯入 TGOS 批次結果檔、-SkipTgos 略過線上 TGOS 查詢。

.EXAMPLE
  $env:TGOS_APP_ID = '...'; $env:TGOS_API_KEY = '...'
  powershell -ExecutionPolicy Bypass -File scripts\local-verify.ps1

.EXAMPLE
  # 以 TGOS 批次上傳結果進行驗證（不需金鑰），或略過 TGOS 僅跑本機 OSM 驗證
  powershell -ExecutionPolicy Bypass -File scripts\local-verify.ps1 -TgosResult build\tgos-result.csv
  powershell -ExecutionPolicy Bypass -File scripts\local-verify.ps1 -SkipTgos

.EXAMPLE
  # 強制全量驗證、重新下載圖資
  powershell -ExecutionPolicy Bypass -File scripts\local-verify.ps1 -VerifyAll -RefreshOsm
#>
param(
    [switch]$VerifyAll,
    [switch]$RefreshOsm,
    [int]$MaxOsmAgeDays = 7,
    [string]$TgosResult = '',
    [switch]$SkipTgos
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$osmDir = Join-Path $repo 'build\osm'
$pbf = Join-Path $osmDir 'taiwan-latest.osm.pbf'
$extract = Join-Path $osmDir 'taiwan-extract.geojsonseq'

$hasTgosResult = -not [string]::IsNullOrWhiteSpace($TgosResult)
if (-not $SkipTgos -and -not $hasTgosResult) {
    if ([string]::IsNullOrWhiteSpace($env:TGOS_APP_ID) -or [string]::IsNullOrWhiteSpace($env:TGOS_API_KEY)) {
        throw '請先設定環境變數 TGOS_APP_ID 與 TGOS_API_KEY（例如 $env:TGOS_APP_ID = "..."），或傳入 -TgosResult / -SkipTgos。'
    }
}

function Get-JavaMajorVersion {
    param([string]$JdkHome)
    if ([string]::IsNullOrWhiteSpace($JdkHome)) { return $null }
    $javaExe = Join-Path $JdkHome 'bin\java.exe'
    if (-not (Test-Path $javaExe)) { return $null }

    $releaseFile = Join-Path $JdkHome 'release'
    if (Test-Path $releaseFile) {
        $match = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="(\d+)(?:\.(\d+))?' | Select-Object -First 1
        if ($match) {
            $first = [int]$match.Matches[0].Groups[1].Value
            if ($first -eq 1 -and $match.Matches[0].Groups[2].Success) {
                return [int]$match.Matches[0].Groups[2].Value
            }
            return $first
        }
    }

    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $verOut = (& $javaExe -version 2>&1 | Out-String)
    }
    finally {
        $ErrorActionPreference = $prevPref
    }
    if ($verOut -match 'version "(\d+)(?:\.(\d+))?') {
        $first = [int]$Matches[1]
        if ($first -eq 1 -and $Matches[2]) { return [int]$Matches[2] }
        return $first
    }
    return $null
}

$currentJavaMajor = Get-JavaMajorVersion $env:JAVA_HOME
if (-not $currentJavaMajor -or $currentJavaMajor -lt 17 -or $currentJavaMajor -gt 21) {
    $candidates = @()
    $preferred = Join-Path $HOME '.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
    if (Test-Path $preferred) { $candidates += $preferred }
    $jdksRoot = Join-Path $HOME '.gradle\jdks'
    if (Test-Path $jdksRoot) {
        Get-ChildItem -Path $jdksRoot -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_.FullName -ne $preferred) { $candidates += $_.FullName }
        }
    }
    $selected = $null
    $selectedMajor = 0
    foreach ($cand in $candidates) {
        $maj = Get-JavaMajorVersion $cand
        if ($maj -and $maj -ge 17 -and $maj -le 21) {
            if ($maj -eq 21) {
                $selected = $cand
                $selectedMajor = $maj
                break
            }
            if ($maj -gt $selectedMajor) {
                $selected = $cand
                $selectedMajor = $maj
            }
        }
    }
    if ($selected) {
        if ($currentJavaMajor) {
            Write-Host "偵測到 JAVA_HOME ($env:JAVA_HOME) 為 JDK $currentJavaMajor，改用相容的 JDK $selectedMajor：$selected"
        }
        $env:JAVA_HOME = $selected
    } elseif ($currentJavaMajor -and $currentJavaMajor -gt 21) {
        throw "目前的 JAVA_HOME ($env:JAVA_HOME) 為 JDK $currentJavaMajor（Gradle 8.14.5 不支援），且在 $jdksRoot 找不到 JDK 17–21。"
    }
}
Write-Host "JAVA_HOME = $env:JAVA_HOME"

New-Item -ItemType Directory -Force $osmDir | Out-Null

$needDownload = $RefreshOsm -or -not (Test-Path $pbf) -or
    ((Get-Item $pbf).LastWriteTime -lt (Get-Date).AddDays(-$MaxOsmAgeDays))
if ($needDownload) {
    Write-Host '下載 Geofabrik 台灣 OSM 圖資…'
    & curl.exe -fL --retry 3 -o $pbf 'https://download.geofabrik.de/asia/taiwan-latest.osm.pbf'
    if ($LASTEXITCODE -ne 0) { throw "下載 OSM 圖資失敗（curl exit $LASTEXITCODE）" }
}

$needExtract = $needDownload -or -not (Test-Path $extract) -or
    ((Get-Item $extract).LastWriteTime -lt (Get-Item $pbf).LastWriteTime)
if ($needExtract) {
    & docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker 未啟動：請先開啟 Docker Desktop 再重試。' }
    Write-Host '以 osmium 篩選並匯出 GeoJSONSeq（首次需下載 debian 映像與套件）…'
    $osmiumCmd = 'apt-get update -qq && apt-get install -y -qq osmium-tool >/dev/null && ' +
        'osmium tags-filter -O -o /data/taiwan-filtered.osm.pbf /data/taiwan-latest.osm.pbf ' +
        'w/highway r/route=road r/boundary=administrative n/highway=milestone && ' +
        'osmium export -O -f geojsonseq -o /data/taiwan-extract.geojsonseq /data/taiwan-filtered.osm.pbf'
    & docker run --rm -v "${osmDir}:/data" debian:bookworm-slim bash -c $osmiumCmd
    if ($LASTEXITCODE -ne 0) { throw "osmium 執行失敗（docker exit $LASTEXITCODE）" }
}

$extractArg = 'build/osm/taiwan-extract.geojsonseq'
$runArgs = "--osm-extract $extractArg"
if ($VerifyAll) { $runArgs += ' --verify-all' }
if ($hasTgosResult) {
    $tgosPath = $TgosResult
    if (-not (Test-Path $tgosPath)) {
        $repoRelative = Join-Path $repo $TgosResult
        if (Test-Path $repoRelative) { $tgosPath = $repoRelative }
    }
    if (-not (Test-Path $tgosPath)) {
        throw "找不到 TGOS 批次結果檔：$TgosResult"
    }
    $tgosPath = (Resolve-Path $tgosPath).Path
    $quotedTgos = if ($tgosPath -match '\s') { "'$tgosPath'" } else { $tgosPath }
    $runArgs += " --tgos-result $quotedTgos"
}

$prevTgosAppId = $env:TGOS_APP_ID
$prevTgosApiKey = $env:TGOS_API_KEY
if ($SkipTgos) {
    Remove-Item Env:TGOS_APP_ID -ErrorAction SilentlyContinue
    Remove-Item Env:TGOS_API_KEY -ErrorAction SilentlyContinue
}

Push-Location $repo
try {
    Write-Host "執行 camera-sync：$runArgs"
    & .\gradlew.bat --no-daemon :camera-sync:run "--args=$runArgs"
    if ($LASTEXITCODE -ne 0) { throw "camera-sync 執行失敗（exit $LASTEXITCODE）" }

    Write-Host ''
    Write-Host '=== 報告中的 TGOS 統計 ==='
    Select-String -Path 'build\camera-report.md' -Encoding UTF8 -Pattern 'TGOS' | ForEach-Object { $_.Line }
    Write-Host ''
    Write-Host '=== 資料異動 ==='
    & git status --short -- data/published data/generated
}
finally {
    if ($SkipTgos) {
        if ($null -ne $prevTgosAppId) { $env:TGOS_APP_ID = $prevTgosAppId }
        if ($null -ne $prevTgosApiKey) { $env:TGOS_API_KEY = $prevTgosApiKey }
    }
    Pop-Location
}
