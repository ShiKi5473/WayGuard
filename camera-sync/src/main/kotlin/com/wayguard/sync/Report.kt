package com.wayguard.sync

import com.wayguard.core.dataset.BuildResult
import com.wayguard.core.dataset.DatasetBuilder
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
            appendLine("- 依地名推算方向：${rep.placeDerived.size}（容許 ±${DatasetBuilder.PLACE_HEADING_TOLERANCE_DEG}°）")
            appendLine("- 未判讀方向（視為雙向提醒）：${rep.unknownDirections.values.sum()}")
            appendLine("- overrides：修正 ${rep.fixed}、排除 ${rep.excluded}、新增 ${rep.added}")
            appendLine("- 區間測速：已整理 ${ds.sections.size} 個區間；候選列 ${rep.sectionCandidates.size}（見 section-candidates.csv）")
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

            if (rep.placeTooClose.isNotEmpty()) {
                appendLine()
                appendLine("## 地名離測速點太近（未推算）")
                appendLine()
                appendLine("| id | 方向文字 | 地點 | 距離 |")
                appendLine("|---|---|---|---|")
                rep.placeTooClose.forEach {
                    appendLine("| ${it.cameraId} | ${cell(it.directionText)} | ${cell(it.place.ref.ifBlank { it.place.name })} | ${it.distanceM.toInt()} m |")
                }
            }

            if (rep.placeDerived.isNotEmpty()) {
                appendLine()
                appendLine("## 依地名推算的方向")
                appendLine()
                appendLine("| id | 方向文字 | 地點 | 推算方位 | 距離 |")
                appendLine("|---|---|---|---|---|")
                rep.placeDerived.forEach {
                    val km = String.format(java.util.Locale.ROOT, "%.1f", it.distanceM / 1000)
                    appendLine("| ${it.cameraId} | ${cell(it.directionText)} | ${cell(it.place.ref.ifBlank { it.place.name })} | ${it.heading}° | $km km |")
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

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"${s.replace("\"", "\"\"")}\"" else s
}
