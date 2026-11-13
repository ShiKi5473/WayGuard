# WayGuard

Android 測速照相守衛（Kotlin + Jetpack Compose + MapLibre GL）。地圖使用 [WayMap](https://github.com/ShiKi5473/WayMap) 函式庫；測速點位來自內政部警政署開放資料，每週由 GitHub Actions 自動更新。

## 功能

- **測速守衛**：前景服務每秒讀取 GPS。依車速決定提醒距離（50 km/h 以下 300 m、50–80 km/h 500 m、80 km/h 以上 800 m，可在設定調整），在近距（150 m）再提醒一次。只有位於前方、且符合拍攝方向的點位才會提醒。
- **語音與通知**：中文（台灣）TTS，播報時暫時降低音樂音量；常駐通知顯示車速與前方點位。
- **超速警示**：在提醒範圍內超過該點速限（可設容許值）時語音警告，之後每 3 秒嗶一聲，畫面轉為紅色。
- **區間測速**：通過起點後即時顯示平均速度，以及「剩餘路段建議車速」；平均超過速限時警示。
- **懸浮視窗**：可疊在導航 App 上，顯示車速、速限和距離，可拖曳，點一下回到 App。
- **自訂點位／誤報標記**：長按地圖可新增流動測速或自訂提醒點；點一下點位可標記為誤報／已拆除。這些資料只存在本機。
- **App 版本更新**：在設定頁可點擊「檢查更新」比對 GitHub Releases 最新版本、從 GitHub 下載 APK 並於驗證 SHA-256 與簽章後更新 App；亦可開啟「自動更新」（支援僅在 Wi-Fi 下背景下載）。

> 提醒僅供參考，實際設置以現場為準；請遵守速限、注意路況，行駛中請勿操作手機。

## 模組

| 模組 | 職責 |
|---|---|
| `guard-core/` | 純 Kotlin/JVM：資料模型、警政署 CSV 解析（`NpaCsvParser`）、方向判讀（`DirectionParser`）、縣市範圍驗證（`CountyBounds`）、資料集建構（`DatasetBuilder`）、網格索引（`GridIndex`）、提醒引擎（`AlertEngine`）、區間追蹤（`SectionTracker`）、語音文字（`AlertText`） |
| `camera-sync/` | JVM CLI：下載 → 解析 → 驗證 → 輸出 `data/published/`，並產生報告。由 GitHub Actions 每週執行 |
| `app/` | Android App：`guard/`（前景服務、TTS、懸浮窗）、`data/`（資料載入與合併）、`sync/`（WorkManager 更新）、`settings/`、`ui/` |
| `data/source/` | 人工維護：`overrides.json`（修正、排除、新增點位）、`sections.json`（區間起訖點）、`places.json`（方向文字中的地名座標） |
| `data/published/` | CI 產出：`cameras.json`、`meta.json`。App 也會把這兩個檔案打包成內建快照 |
| `data/generated/` | CI 自動維護：`road-checks.json`（道路走向）、`location-checks.json`（位置交叉驗證快取），均來自 OpenStreetMap |

## 資料更新流程

1. `.github/workflows/update-cameras.yml` 每週一 03:00（台灣時間）執行 `./gradlew :camera-sync:run`，也可以手動觸發。
2. camera-sync 會先正規化方向文字（指向地名的，依 `places.json` 推算方向），再修正對調的經緯度、拒絕座標不在縣市範圍內的列、合併重複點位、套用 `overrides.json`。版本號由內容的 hash 決定。
3. **安全閥**：有效筆數比上一版少 20% 以上，或被拒絕的列超過 30% 時，以 exit 1 結束，不寫入任何資料。
4. 內容有變才會 commit `data/published/` 和 `data/generated/`。報告寫在 Actions 的 Summary，`section-candidates.csv` 則存成 artifact。
5. App 每 7 天（可設定只在 Wi-Fi 下）讀取 `meta.json` 比對版本；有新版才下載 `cameras.json`，驗證 SHA-256 後原子寫入。超過 14 天沒檢查時，開啟 App 就會立刻檢查一次。

> GitHub 會停用公開 repo 中 60 天沒有活動的排程 workflow。如果資料長時間沒變（沒有 bot commit），需要到 Actions 頁面重新啟用。

### 人工維護

- **修正座標**：報告的「拒絕的列」會列出 id。在 `overrides.json` 的 `fix` 中補上正確座標即可納入，例如 `"npa-eaa1e9685b": {"lat": 24.645304, "note": "緯度筆誤"}`。
- **排除點位**：把 id 加進 `exclude`。
- **區間測速**：全國沒有統一的起訖點資料。請參考 `section-candidates.csv` 和各縣市公告，人工整理後寫入 `sections.json`。每個行駛方向各寫一筆：

```json
[
  {
    "id": "tw9-suhua-n",
    "name": "台9線蘇花改（北上）",
    "startLat": 24.0, "startLon": 121.6,
    "endLat": 24.1, "endLon": 121.7,
    "lengthM": 5000, "limitKmh": 70, "heading": 0
  }
]
```

- **地名方向**：「往大溪方向」「中山路上往桃園車站方向」這類寫法，會到 `data/source/places.json` 查目的地座標，再**自動判斷是否可靠**：不可靠就維持雙向提醒，不需要人工補 `overrides.json`。只有以下條件全部成立才採用：
  1. 目的地離測速點至少 150 m；若目的地是路名（`"road": true`），最近點不超過 1 km（太遠代表可能找錯路段）。
  2. 已從 OpenStreetMap 查到測速點所在的道路（`data/generated/road-checks.json`，CI 自動查詢並快取），而且不是巷弄。
  3. 查到的道路就是方向文字或地址上的那條路（「X路上往…」或地址只有一條路名）。地址是「A與B口」這種路口、又沒說在哪條路上的，無法確定，不採用。
  4. 那條道路的走向，和「測速點 → 目的地」的方位相差不超過 60°（彎路、路口抓錯路會在這裡被排除）。

  採用時，方向取「該道路通往目的地的那一側」，容許 ±100°。新出現的點位在還沒比對道路之前，一律維持雙向；查詢失敗也一樣，下次執行會再查。`overrides.json` 的 `fix.headings` 優先於以上規則。
  - 每筆地名可以限定範圍：有 `district` 只套用該區，只有 `city` 套用整個縣市，兩者都沒有則套用全國。「市區」這類相對說法和路名請限定到區；`aliases` 可放其他寫法（「台」「臺」視為同一字）。
  - 報告的「未對照的地名」會列出尚未收錄的地名。執行 `./gradlew :camera-sync:run --args="--suggest-places"` 會用 OpenStreetMap Nominatim 查詢候選座標，寫入 `build/place-suggestions.md`。**人工確認地點正確**後，再把 `build/place-suggestions.json` 中的項目複製進 `places.json`；各點位是否採用，之後由上面的規則自動決定。
  - 報告的「地名已收錄但未採用推算」會列出每個點位的原因，僅供參考，不需要處理。
  - 目前不收錄的地名（維持雙向）：往下山（不是地名）、往大埔（不確定指哪裡）、往長庚（長庚大學與林口長庚醫院方向相反）、往國道二號方向（附近有兩個交流道）、北投竹子湖「往市區」（山路，目的地不明確）。
  - 另有 4 筆山路點位，是先前人工決定的，以 `overrides.json` 的 `"headings": []` 維持雙向：台七線羅浮、台七乙線 8.8K、龍潭三坑下坡、大溪復興路二段「往復興」。
- **未判讀方向**：仍無法判斷方向的寫法一律視為雙向提醒，並列在報告中。若同一種寫法反覆出現，可以補進 `DirectionParser` 的規則。

## 建置

需要 Android SDK（compileSdk 36）與 **JDK 17–21**（Gradle 8.14 無法在 JDK 25 上執行）。

```bash
./gradlew :guard-core:test                 # 核心邏輯單元測試
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :camera-sync:run                 # 本機抓一次資料（輸出 data/published/、build/camera-report.md）
```

WayMap 預設從 JitPack 取得（`gradle.properties` 的 `wayMapVersion`）。要搭配本機的 `../WayMap` 原始碼同步開發時：

```bash
./gradlew -PuseLocalWayMap=true :app:assembleDebug
```

- minSdk 31 / targetSdk 34 / compileSdk 36。
- 不需要背景定位權限：守衛服務一律由前景的 Activity 啟動。

### 發佈 App Release（GitHub Actions）

`.github/workflows/release.yml` 會在推送 `v*` tag（或手動執行 `workflow_dispatch`）時自動建置簽署的 Release APK 與 `.sha256` 校驗檔並發佈到 GitHub Releases。需先在 GitHub Repository Secrets 設定以下 4 個簽章參數（確保歷次更新使用相同簽章金鑰）：

- `ANDROID_KEYSTORE_BASE64`（`.jks` / `.keystore` 檔的 Base64 內容）
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## 測試行車（不必真的上路）

可以搭配 [ChangeGpsAnyway](https://github.com/ShiKi5473/ChangeGpsAnyway) 的路線模式模擬行車，它推送的定位會帶速度與方位：

1. 從 `cameras.json` 挑一個單向點位，畫一條經過它的路線，設定時速 60，接著在 WayGuard 開始守衛。預期約在 500 m 和 150 m 處各聽到一次提醒。
2. 時速改成高於速限：預期會有超速語音、嗶聲，畫面轉紅。
3. 反向行駛同一條路線：單向點位不應提醒。

## 資料來源與授權

- 測速點位：內政部警政署「測速執法設置點」（[data.gov.tw/dataset/7320](https://data.gov.tw/dataset/7320)），依政府資料開放授權條款第 1 版使用。
- 地圖：OpenFreeMap / © OpenStreetMap contributors。
- `data/source/places.json` 的地名座標：© OpenStreetMap contributors（ODbL），經 Nominatim 查詢後人工確認。
- `data/generated/location-checks.json` 的位置交叉驗證與 `data/source/overrides.json` 中參考 OpenStreetMap 反推的建議修正座標：© OpenStreetMap contributors（ODbL）；行政區複查結果來自內政部國土測繪中心。
