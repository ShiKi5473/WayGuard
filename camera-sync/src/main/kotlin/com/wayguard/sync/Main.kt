package com.wayguard.sync

import com.wayguard.core.dataset.BuildResult
import com.wayguard.core.dataset.DatasetBuilder
import com.wayguard.core.dataset.Overrides
import com.wayguard.core.dataset.Place
import com.wayguard.core.dataset.RoadCheck
import com.wayguard.core.model.DatasetIo
import com.wayguard.core.model.DatasetJson
import com.wayguard.core.model.DatasetMeta
import com.wayguard.core.model.Section
import com.wayguard.core.parse.NpaCsvParser
import com.wayguard.core.validate.CountyBounds
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val prettyJson = Json(DatasetJson) { prettyPrint = true }

/** 警政署「測速執法設置點」(data.gov.tw dataset 7320) CSV download. */
const val NPA_URL =
    "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/4C69F625-0293-4C52-A719-176B8D24B351/download"

private const val USER_AGENT = "WayGuard-camera-sync/1.0 (+https://github.com/ShiKi5473/WayGuard)"

/**
 * Fetches the NPA speed-camera CSV, validates/normalises it and writes
 * `<out>/cameras.json` + `<out>/meta.json` when the content changed.
 *
 * Exit codes: 0 = written or unchanged, 1 = safety check failed (nothing written),
 * 2 = download/parse failure.
 */
fun main(args: Array<String>) {
    val a = try {
        SyncArgs.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(SyncArgs.USAGE)
        exitProcess(2)
    }
    exitProcess(sync(a))
}

data class SyncArgs(
    val source: String = NPA_URL,
    val sections: File = File("data/source/sections.json"),
    val overrides: File = File("data/source/overrides.json"),
    val places: File = File("data/source/places.json"),
    /** Cache of the road each place-derived camera stands on (machine-maintained). */
    val roadChecks: File = File("data/generated/road-checks.json"),
    /** Skip the road lookups (unchecked cameras then stay both ways). */
    val offline: Boolean = false,
    val out: File = File("data/published"),
    val report: File = File("build/camera-report.md"),
    val candidates: File = File("build/section-candidates.csv"),
    /** Fail when the new count is below this fraction of the previous one. */
    val minRatio: Double = 0.8,
    /** Fail when more than this fraction of source rows is rejected. */
    val maxRejectRatio: Double = 0.3,
    /** Fail when fewer than this fraction of accepted cameras match a known Taiwan city/county. */
    val minKnownCityRatio: Double = 0.8,
    /**
     * Instead of publishing, geocode destination names missing from places.json and
     * write review candidates to `build/place-suggestions.{md,json}`.
     */
    val suggestPlaces: Boolean = false,
) {
    companion object {
        const val USAGE = "usage: camera-sync [--source URL|FILE] [--sections FILE] [--overrides FILE] " +
            "[--places FILE] [--road-checks FILE] [--offline] [--out DIR] [--report FILE] [--candidates FILE] " +
            "[--min-ratio 0.8] [--max-reject 0.3] [--min-known-city 0.8] [--suggest-places]"

        fun parse(args: Array<String>): SyncArgs {
            var a = SyncArgs()
            var i = 0
            fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("缺少 ${args[i - 1]} 的值")
            while (i < args.size) {
                a = when (args[i]) {
                    "--source" -> a.copy(source = value())
                    "--sections" -> a.copy(sections = File(value()))
                    "--overrides" -> a.copy(overrides = File(value()))
                    "--places" -> a.copy(places = File(value()))
                    "--road-checks" -> a.copy(roadChecks = File(value()))
                    "--offline" -> a.copy(offline = true)
                    "--suggest-places" -> a.copy(suggestPlaces = true)
                    "--out" -> a.copy(out = File(value()))
                    "--report" -> a.copy(report = File(value()))
                    "--candidates" -> a.copy(candidates = File(value()))
                    "--min-ratio" -> a.copy(minRatio = value().toDouble())
                    "--max-reject" -> a.copy(maxRejectRatio = value().toDouble())
                    "--min-known-city" -> a.copy(minKnownCityRatio = value().toDouble())
                    else -> throw IllegalArgumentException("未知參數：${args[i]}")
                }
                i++
            }
            return a
        }
    }
}

fun sync(a: SyncArgs): Int {
    val csv = try {
        fetch(a.source)
    } catch (e: Exception) {
        System.err.println("下載失敗：${a.source}\n$e")
        return 2
    }
    val parsed = NpaCsvParser.parse(csv)
    val sections = readJson(a.sections, ListSerializer(Section.serializer())) ?: emptyList()
    val overrides = readJson(a.overrides, Overrides.serializer()) ?: Overrides()
    val places = readJson(a.places, ListSerializer(Place.serializer())) ?: emptyList()
    val checksSerializer = MapSerializer(String.serializer(), RoadCheck.serializer())
    val cachedChecks = readJson(a.roadChecks, checksSerializer) ?: emptyMap()
    val checks = LinkedHashMap(cachedChecks)
    val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
    fun build() = DatasetBuilder.build(
        parsed.rows, parsed.rejected, sections, overrides, now, sourceLabel(a.source), places, checks,
    )
    var result = build()
    val nominatim = Nominatim(USER_AGENT)

    if (a.suggestPlaces) {
        val outDir = a.report.absoluteFile.parentFile
        val n = PlaceSuggester(nominatim).run(result, outDir)
        println("已查詢 ${result.report.unmatchedPlaces.size} 個未對照地名，找到 $n 個候選：${File(outDir, "place-suggestions.md")}")
        return 0
    }

    // Verify destination-derived directions against the road each new camera stands on.
    // Unverified cameras stay both ways, so a failed lookup is safe and retried next run.
    val pending = result.report.needsRoadCheck
    if (pending.isNotEmpty() && !a.offline) {
        val found = RoadChecker(nominatim).check(pending.map { it.cameraId to it.pos }, now)
        println("比對道路走向：待比對 ${pending.size} 個點位，取得 ${found.size} 筆")
        if (found.isNotEmpty()) {
            checks += found
            result = build()
        }
    }
    // Keep only cameras that still use a destination, so the cache doesn't grow forever.
    val inUse = (result.report.placeDerived + result.report.placeSkipped).mapTo(HashSet()) { it.cameraId }
    val prunedChecks = checks.filterKeys { it in inUse }.toSortedMap()
    if (prunedChecks != cachedChecks.toSortedMap()) {
        a.roadChecks.parentFile?.mkdirs()
        a.roadChecks.writeText(prettyJson.encodeToString(checksSerializer, prunedChecks) + "\n")
    }

    val metaFile = File(a.out, "meta.json")
    val previous = metaFile.takeIf { it.isFile }?.let { runCatching { DatasetIo.decodeMeta(it.readText()) }.getOrNull() }
    val problems = safetyProblems(result, previous, a)
    val unchanged = previous?.version == result.dataset.version

    a.report.parentFile?.mkdirs()
    a.report.writeText(Report.markdown(result, previous, problems, unchanged))
    a.candidates.parentFile?.mkdirs()
    a.candidates.writeText(Report.sectionCandidatesCsv(result))

    if (problems.isNotEmpty()) {
        System.err.println("安全檢查未通過，未寫入資料：")
        problems.forEach { System.err.println(" - $it") }
        return 1
    }
    if (unchanged) {
        println("資料未變更（version ${result.dataset.version}，${result.dataset.cameras.size} 筆）")
        return 0
    }

    a.out.mkdirs()
    val bytes = DatasetIo.encode(result.dataset).toByteArray(Charsets.UTF_8)
    File(a.out, "cameras.json").writeBytes(bytes)
    val meta = DatasetMeta(
        version = result.dataset.version,
        generatedAt = result.dataset.generatedAt,
        count = result.dataset.cameras.size,
        sectionCount = result.dataset.sections.size,
        sha256 = DatasetBuilder.sha256Hex(bytes),
    )
    metaFile.writeText(DatasetIo.encodeMeta(meta))
    println("已更新：version ${meta.version}，${meta.count} 筆測速點、${meta.sectionCount} 個區間（上一版 ${previous?.count ?: "無"} 筆）")
    return 0
}

internal fun safetyProblems(r: BuildResult, previous: DatasetMeta?, a: SyncArgs): List<String> = buildList {
    val count = r.dataset.cameras.size
    if (count == 0) add("沒有任何有效的測速點")
    if (previous != null && count < previous.count * a.minRatio) {
        add("有效筆數 $count 少於上一版 ${previous.count} 的 ${(a.minRatio * 100).toInt()}%")
    }
    val rejectRatio = if (r.report.sourceRows == 0) 1.0 else r.report.rejected.size.toDouble() / r.report.sourceRows
    if (rejectRatio > a.maxRejectRatio) {
        add("拒絕比例 ${"%.1f".format(rejectRatio * 100)}% 超過 ${(a.maxRejectRatio * 100).toInt()}%")
    }
    if (count > 0) {
        val knownCityRatio = r.dataset.cameras.count { CountyBounds.isKnown(it.city) }.toDouble() / count
        if (knownCityRatio < a.minKnownCityRatio) {
            add("可辨識縣市比例 ${"%.1f".format(knownCityRatio * 100)}% 低於 ${(a.minKnownCityRatio * 100).toInt()}%")
        }
    }
}

/** Downloads [source] (http/https URL) or reads it as a local file; retries network errors. */
private fun fetch(source: String): String {
    if (!source.startsWith("http://") && !source.startsWith("https://")) {
        return File(source).readBytes().toString(Charsets.UTF_8)
    }
    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    val request = HttpRequest.newBuilder(URI.create(source))
        .header("User-Agent", USER_AGENT)
        .timeout(Duration.ofSeconds(120))
        .GET()
        .build()
    var last: Exception? = null
    repeat(3) { attempt ->
        try {
            val resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (resp.statusCode() == 200) return resp.body().toString(Charsets.UTF_8)
            last = IllegalStateException("HTTP ${resp.statusCode()}")
        } catch (e: Exception) {
            last = e
        }
        Thread.sleep(5_000L * (attempt + 1))
    }
    throw last!!
}

private fun <T> readJson(file: File, serializer: KSerializer<T>): T? =
    file.takeIf { it.isFile }?.readText()?.let { DatasetJson.decodeFromString(serializer, it) }

private fun sourceLabel(source: String): String =
    if (source == NPA_URL) "內政部警政署「測速執法設置點」（data.gov.tw/dataset/7320）" else source
