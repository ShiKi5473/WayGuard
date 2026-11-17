<#
.SYNOPSIS
  在台灣本機執行 camera-sync 位置交叉驗證（含 TGOS 線上門牌查詢）。

.DESCRIPTION
  GitHub Actions 的境外 IP 會被 TGOS 以 HTTP 403 阻擋，因此門牌驗證改在本機執行，
  再把 data/generated/location-checks.json（只含驗證結論與距離，不含 TGOS 原始座標）commit 回 repo，
  之後 CI 每週增量檢查會沿用這些結果。

  流程與 .github/workflows/update-cameras.yml 相同：
    1. 下載 Geofabrik 台灣 OSM 圖資（已存在且未過期則略過）
    2. 以 Docker（官方 debian 映像 + apt 的 osmium-tool）篩選並匯出 GeoJSONSeq
    3. ./gradlew :camera-sync:run --args="--osm-extract ..."

  前置需求：Docker Desktop 已啟動；環境變數 TGOS_APP_ID、TGOS_API_KEY 已設定（不會寫入任何檔案）。

.EXAMPLE
  $env:TGOS_APP_ID = '...'; $env:TGOS_API_KEY = '...'
  powershell -ExecutionPolicy Bypass -File scripts\local-verify.ps1

.EXAMPLE
  # 強制全量驗證、重新下載圖資
  powershell -ExecutionPolicy Bypass -File scripts\local-verify.ps1 -VerifyAll -RefreshOsm
#>
param(
    [switch]$VerifyAll,
    [switch]$RefreshOsm,
    [int]$MaxOsmAgeDays = 7
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$osmDir = Join-Path $repo 'build\osm'
$pbf = Join-Path $osmDir 'taiwan-latest.osm.pbf'
$extract = Join-Path $osmDir 'taiwan-extract.geojsonseq'

if ([string]::IsNullOrWhiteSpace($env:TGOS_APP_ID) -or [string]::IsNullOrWhiteSpace($env:TGOS_API_KEY)) {
    throw '請先設定環境變數 TGOS_APP_ID 與 TGOS_API_KEY（例如 $env:TGOS_APP_ID = "..."）。'
}

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $jdk = Join-Path $HOME '.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
    if (Test-Path (Join-Path $jdk 'bin\java.exe')) { $env:JAVA_HOME = $jdk }
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

Push-Location $repo
try {
    Write-Host "執行 camera-sync：$runArgs"
    & .\gradlew.bat --no-daemon :camera-sync:run "--args=$runArgs"
    if ($LASTEXITCODE -ne 0) { throw "camera-sync 執行失敗（exit $LASTEXITCODE）" }

    Write-Host ''
    Write-Host '=== 報告中的 TGOS 統計 ==='
    Select-String -Path 'build\camera-report.md' -Pattern 'TGOS' | ForEach-Object { $_.Line }
    Write-Host ''
    Write-Host '=== 資料異動 ==='
    & git status --short -- data/published data/generated
}
finally {
    Pop-Location
}
