package com.wayguard.sync

import com.wayguard.core.dataset.BuildResult
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
            appendLine("- 未判讀方向（視為雙向提醒）：${rep.unknownDirections.values.sum()}")
            appendLine("- overrides：修正 ${rep.fixed}、排除 ${rep.excluded}、新增 ${rep.added}")
            appendLine("- 區間測速：已整理 ${ds.sections.size} 個區間；候選列 ${rep.sectionCandidates.size}（見 section-candidates.csv）")
            appendLine()
            appendLine("## 安全檢查")
            appendLine()
            if (problems.isEmpty()) appendLine("- ✅ 通過")
            else problems.forEach { appendLine("- ❌ $it") }

            if (rep.unknownDirections.isNotEmpty()) {
                appendLine()
                appendLine("## 未判讀方向")
                appendLine()
                appendLine("無法判讀的方向一律視為雙向提醒。若常見寫法反覆出現，請補進 `DirectionParser` 的規則。")
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
