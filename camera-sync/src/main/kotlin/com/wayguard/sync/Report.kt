package com.wayguard.sync

import com.wayguard.core.dataset.BuildResult
import com.wayguard.core.dataset.DatasetBuilder
import com.wayguard.core.dataset.PlaceDerivation
import com.wayguard.core.model.DatasetMeta

/** Human-readable outputs of a sync run (GitHub step summary + curation aids). */
internal object Report {

    fun markdown(r: BuildResult, previous: DatasetMeta?, problems: List<String>, unchanged: Boolean): String =
        buildString {
            val rep = r.report
            val ds = r.dataset
            val count = ds.cameras.size
            val rejectPct = if (rep.sourceRows == 0) 0.0 else rep.rejected.size * 100.0 / rep.sourceRows
            appendLine("# 測速照相點位更新報告")
            appendLine()
            appendLine("- 產生時間：${ds.generatedAt}")
            appendLine("- 版本：`${ds.version}`（${if (unchanged) "與上一版相同，未寫入" else "新版本"}）")
            appendLine("- 來源列數：${rep.sourceRows}")
            val delta = previous?.let { " （上一版 ${it.count}，變化 ${signed(count - it.count)}）" } ?: ""
            appendLine("- 有效測速點：$count$delta")
            appendLine("- 拒絕：${rep.rejected.size}（${"%.1f".format(rejectPct)}%）")
            appendLine("- 經緯度對調並修正：${rep.swappedLines.size}")
            appendLine("- 重複合併：${rep.duplicates}")
            appendLine("- 缺速限：${rep.missingLimit}")
            appendLine("- 依地名推算方向：採用 ${rep.placeDerived.size}、未採用 ${rep.placeSkipped.size}（其中待比對道路 ${rep.needsRoadCheck.size}）")
            appendLine("- 未判讀方向（視為雙向提醒）：${rep.unknownDirections.values.sum()}")
            val unmatched = if (rep.unmatchedOverrides.isEmpty()) "" else "（未命中 ${rep.unmatchedOverrides.size}）"
            appendLine("- overrides：修正 ${rep.fixed}、排除 ${rep.excluded}、新增 ${rep.added}$unmatched")
            appendLine("- 區間測速：共 ${ds.sections.size} 個區間（人工整理 ${ds.sections.size - rep.autoSections.size}、自動配對 ${rep.autoSections.size}）；候選列 ${rep.sectionCandidates.size}（見 section-candidates.csv）")
            appendLine()
            appendLine("## 安全檢查")
            appendLine()
            if (problems.isEmpty()) appendLine("- ✅ 通過")
            else problems.forEach { appendLine("- ❌ $it") }

            if (rep.unmatchedPlaces.isNotEmpty()) {
                appendLine()
                appendLine("## 未對照的地名")
                appendLine()
                appendLine("方向文字指向這些地名，但 `data/source/places.json` 尚未收錄，目前視為雙向提醒。")
                appendLine("可執行 `./gradlew :camera-sync:run --args=\"--suggest-places\"` 取得候選座標。")
                appendLine()
                appendLine("| 範圍 | 地名 | 測速點數 |")
                appendLine("|---|---|---|")
                rep.unmatchedPlaces.entries.sortedWith(compareBy({ it.key.city }, { it.key.name }, { it.key.district }))
                    .forEach { (ref, cams) -> appendLine("| ${ref.city}${ref.district} | ${cell(ref.name)} | ${cams.size} |") }
            }

            if (rep.placeSkipped.isNotEmpty()) {
                appendLine()
                appendLine("## 地名已收錄但未採用推算（維持雙向）")
                appendLine()
                appendLine("自動判斷不可靠的推算，不需人工處理；「尚未比對」的點位會在下次執行時查詢道路走向。")
                appendLine()
                appendLine("| id | 方向文字 | 地點 | 目的地方位／距離 | 測速點所在道路 | 原因 |")
                appendLine("|---|---|---|---|---|---|")
                rep.placeSkipped.forEach {
                    appendLine("| ${it.cameraId} | ${cell(it.directionText)} | ${cell(it.place.ref.ifBlank { it.place.name })} | " +
                        "${it.bearingToPlace}°／${km(it.distanceM)} | ${roadCell(it)} | ${it.skipReason?.text.orEmpty()}" +
                        "${it.roadDiffDeg?.let { d -> "（相差 $d°）" } ?: ""} |")
                }
            }

            if (rep.placeDerived.isNotEmpty()) {
                appendLine()
                appendLine("## 依地名推算的方向")
                appendLine()
                appendLine("採用測速點所在道路通往目的地的那一側走向，容許 ±${DatasetBuilder.PLACE_HEADING_TOLERANCE_DEG}°。")
                appendLine()
                appendLine("| id | 方向文字 | 地點 | 目的地方位／距離 | 測速點所在道路 | 採用方向 |")
                appendLine("|---|---|---|---|---|---|")
                rep.placeDerived.forEach {
                    appendLine("| ${it.cameraId} | ${cell(it.directionText)} | ${cell(it.place.ref.ifBlank { it.place.name })} | " +
                        "${it.bearingToPlace}°／${km(it.distanceM)} | ${roadCell(it)} | ${it.heading}°（相差 ${it.roadDiffDeg}°） |")
                }
            }

            if (rep.unknownDirections.isNotEmpty()) {
                appendLine()
                appendLine("## 未判讀方向")
                appendLine()
                appendLine("無法判讀的方向一律視為雙向提醒。若常見寫法反覆出現，請補進 `DirectionParser` 的規則；")
                appendLine("指向地名的請補進 `data/source/places.json`。")
                appendLine()
                appendLine("| 方向文字 | 筆數 |")
                appendLine("|---|---|")
                rep.unknownDirections.entries.sortedByDescending { it.value }
                    .forEach { (t, n) -> appendLine("| ${cell(t)} | $n |") }
            }

            if (rep.unmatchedOverrides.isNotEmpty()) {
                appendLine()
                appendLine("## 沒有命中的 overrides")
                rep.unmatchedOverrides.forEach { appendLine("- `$it`") }
            }
            if (rep.rejected.isNotEmpty()) {
                appendLine()
                appendLine("## 拒絕的列")
                appendLine()
                appendLine("有 id 的列可在 `data/source/overrides.json` 的 `fix` 中補正座標後納入。")
                appendLine()
                appendLine("| 行 | id | 原因 | 內容 |")
                appendLine("|---|---|---|---|")
                rep.rejected.forEach { appendLine("| ${it.line} | ${it.id ?: ""} | ${cell(it.reason)} | ${cell(it.raw)} |") }
            }
        }

    fun sectionCandidatesCsv(r: BuildResult): String = buildString {
        appendLine("id,city,region,address,lon,lat,direct,limit")
        r.report.sectionCandidates.forEach { (id, row) ->
            appendLine(listOf(id, row.city, row.region, row.address, row.lon, row.lat, row.direct, row.limit)
                .joinToString(",") { csv(it) })
        }
    }

    private fun signed(n: Int) = if (n > 0) "+$n" else n.toString()

    private fun km(m: Double) = "${String.format(java.util.Locale.ROOT, "%.1f", m / 1000)} km"

    private fun roadCell(d: PlaceDerivation): String {
        val r = d.road ?: return "—"
        val name = r.road.ifBlank { "（無名道路）" }
        val b = r.bearing ?: return "$name（找不到，${r.distanceM?.let { "$it m" } ?: "—"}）"
        return "${cell(name)} ${b}°／${(b + 180) % 360}°"
    }

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s
}
