package com.wayguard.trip

import com.wayguard.core.model.DatasetJson
import com.wayguard.core.trip.TripDetail
import com.wayguard.core.trip.TripMetaHeader
import com.wayguard.core.trip.TripRecordLine
import com.wayguard.core.trip.TripStats
import com.wayguard.core.trip.TripSummary
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.builtins.ListSerializer

/**
 * Pure-JVM file storage for trips:
 * - Active drive writes `<id>.jsonl` in [backupDir] every ~10 s for crash resilience.
 * - Finished trips compress to `<id>.jsonl.gz` alongside `index.json`.
 * - Enforces Android Auto Backup limit by overflowing oldest `.jsonl.gz` files beyond
 *   [maxBackupBytes] (default 15 MB) into [overflowDir] (`backedUp = false`).
 */
class TripStorage(
    private val backupDir: File,
    private val overflowDir: File,
    private val maxBackupBytes: Long = DEFAULT_MAX_BACKUP_BYTES,
) {
    private val indexFile get() = File(backupDir, "index.json")
    private val indexSerializer = ListSerializer(TripSummary.serializer())

    @Synchronized
    fun loadAndRecover(nowMs: Long = System.currentTimeMillis(), retentionDays: Int = 90): List<TripSummary> {
        backupDir.mkdirs()
        overflowDir.mkdirs()

        val map = LinkedHashMap<String, TripSummary>()
        readIndex().forEach { s -> map[s.id] = s }

        // Recover any leftover unfinalized .jsonl files (e.g. from process kill / OOM).
        backupDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty().forEach { rawFile ->
            val id = rawFile.name.removeSuffix(".jsonl")
            val lines = runCatching { rawFile.readLines(Charsets.UTF_8) }.getOrNull().orEmpty()
            val detail = TripStats.decodeJsonl(id, lines, assumeIncompleteFile = true, backedUp = true)
            if (detail != null) {
                writeGzipLines(File(backupDir, "$id.jsonl.gz"), lines)
                map[detail.summary.id] = detail.summary
            }
            rawFile.delete()
        }

        // Remove summaries whose backing .jsonl.gz is gone from both backup & overflow.
        map.entries.removeAll { (id, _) ->
            !File(backupDir, "$id.jsonl.gz").isFile && !File(overflowDir, "$id.jsonl.gz").isFile
        }

        val pruned = applyRetentionAndQuota(map.values.toList(), nowMs, retentionDays)
        writeIndex(pruned)
        return pruned
    }

    @Synchronized
    fun appendLines(tripId: String, lines: List<String>) {
        if (lines.isEmpty() || !SAFE_ID.matches(tripId)) return
        backupDir.mkdirs()
        val f = File(backupDir, "$tripId.jsonl")
        val payload = lines.joinToString("\n", postfix = "\n")
        f.appendText(payload, Charsets.UTF_8)
    }

    @Synchronized
    fun discardActiveFile(tripId: String) {
        if (!SAFE_ID.matches(tripId)) return
        File(backupDir, "$tripId.jsonl").delete()
    }

    @Synchronized
    fun finalizeTrip(
        detail: TripDetail,
        nowMs: Long = System.currentTimeMillis(),
        retentionDays: Int = 90,
    ): List<TripSummary> {
        backupDir.mkdirs()
        overflowDir.mkdirs()
        val id = detail.summary.id
        require(SAFE_ID.matches(id)) { "Invalid trip id: $id" }

        val activeFile = File(backupDir, "$id.jsonl")
        val gzFile = File(backupDir, "$id.jsonl.gz")
        val lines = serializeDetailToJsonlLines(detail)
        writeGzipLines(gzFile, lines)
        activeFile.delete()

        val current = readIndex().filterNot { it.id == id } + detail.summary
        val updated = applyRetentionAndQuota(current, nowMs, retentionDays)
        writeIndex(updated)
        return updated
    }

    @Synchronized
    fun loadDetail(summary: TripSummary): TripDetail? {
        val gzBackup = File(backupDir, "${summary.id}.jsonl.gz")
        val gzOverflow = File(overflowDir, "${summary.id}.jsonl.gz")
        val targetGz = when {
            gzBackup.isFile -> gzBackup
            gzOverflow.isFile -> gzOverflow
            else -> null
        }
        val lines = when {
            targetGz != null -> readGzipLines(targetGz)
            File(backupDir, "${summary.id}.jsonl").isFile ->
                File(backupDir, "${summary.id}.jsonl").readLines(Charsets.UTF_8)
            else -> return null
        }
        val decoded = TripStats.decodeJsonl(
            fallbackId = summary.id,
            lines = lines,
            assumeIncompleteFile = false,
            backedUp = gzBackup.isFile,
        ) ?: return null
        return TripDetail(summary, decoded.points, decoded.events)
    }

    @Synchronized
    fun deleteTrip(id: String): List<TripSummary> {
        File(backupDir, "$id.jsonl").delete()
        File(backupDir, "$id.jsonl.gz").delete()
        File(overflowDir, "$id.jsonl.gz").delete()
        val next = readIndex().filterNot { it.id == id }.sortedByDescending { it.startedAtMs }
        writeIndex(next)
        return next
    }

    @Synchronized
    fun exportZip(out: OutputStream): Int {
        val summaries = readIndex()
        var count = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zos ->
            if (indexFile.isFile) {
                zos.putNextEntry(ZipEntry("index.json"))
                indexFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            for (s in summaries) {
                val gz = findGzFile(s.id) ?: continue
                zos.putNextEntry(ZipEntry("trips/${s.id}.jsonl.gz"))
                gz.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                count++
            }
        }
        return count
    }

    /**
     * Imports trips from a WayGuard backup `.zip`, keeping any newer/existing local records and
     * returning `(updatedSummaries, importedCount)`.
     */
    @Synchronized
    fun importZip(
        input: InputStream,
        nowMs: Long = System.currentTimeMillis(),
        retentionDays: Int = 90,
    ): Pair<List<TripSummary>, Int> {
        backupDir.mkdirs()
        overflowDir.mkdirs()
        val existing = readIndex().associateByTo(LinkedHashMap()) { it.id }
        val zipSummaries = HashMap<String, TripSummary>()
        var imported = 0

        ZipInputStream(BufferedInputStream(input)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                when {
                    name == "index.json" -> {
                        val bytes = zis.readBytes()
                        runCatching { DatasetJson.decodeFromString(indexSerializer, bytes.decodeToString()) }
                            .getOrNull()
                            ?.forEach { zipSummaries[it.id] = it }
                    }
                    name.endsWith(".jsonl.gz") -> {
                        val base = name.substringAfterLast('/')
                        val id = base.removeSuffix(".jsonl.gz")
                        if (SAFE_ID.matches(id) && findGzFile(id) == null) {
                            val target = File(backupDir, "$id.jsonl.gz")
                            target.outputStream().use { out -> zis.copyTo(out) }
                            imported++
                            if (id !in existing && id !in zipSummaries) {
                                val lines = runCatching { readGzipLines(target) }.getOrNull().orEmpty()
                                TripStats.decodeJsonl(id, lines, assumeIncompleteFile = false, backedUp = true)
                                    ?.let { zipSummaries[id] = it.summary }
                            }
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        for ((id, s) in zipSummaries) {
            if (id !in existing && findGzFile(id) != null) {
                existing[id] = s.copy(backedUp = File(backupDir, "$id.jsonl.gz").isFile)
            }
        }
        val updated = applyRetentionAndQuota(existing.values.toList(), nowMs, retentionDays)
        writeIndex(updated)
        return updated to imported
    }

    private fun applyRetentionAndQuota(
        list: List<TripSummary>,
        nowMs: Long,
        retentionDays: Int,
    ): List<TripSummary> {
        val cutoffMs = if (retentionDays > 0) nowMs - retentionDays * 86_400_000L else Long.MIN_VALUE
        val kept = ArrayList<TripSummary>(list.size)
        for (s in list.sortedByDescending { it.startedAtMs }) {
            if (s.endedAtMs < cutoffMs) {
                File(backupDir, "${s.id}.jsonl").delete()
                File(backupDir, "${s.id}.jsonl.gz").delete()
                File(overflowDir, "${s.id}.jsonl.gz").delete()
            } else {
                val inBackup = File(backupDir, "${s.id}.jsonl.gz").isFile
                kept += s.copy(backedUp = inBackup)
            }
        }

        // Enforce maxBackupBytes (15 MB) by moving oldest .jsonl.gz files from backupDir -> overflowDir.
        var backupBytes = backupDir.listFiles { f -> f.isFile }?.sumOf { it.length() } ?: 0L
        if (backupBytes > maxBackupBytes) {
            val mutable = kept.toMutableList()
            for (i in mutable.indices.reversed()) {
                if (backupBytes <= maxBackupBytes) break
                val s = mutable[i]
                val src = File(backupDir, "${s.id}.jsonl.gz")
                if (!src.isFile) continue
                val size = src.length()
                val dst = File(overflowDir, "${s.id}.jsonl.gz")
                if (moveFile(src, dst)) {
                    backupBytes -= size
                    mutable[i] = s.copy(backedUp = false)
                }
            }
            return mutable
        }
        return kept
    }

    private fun findGzFile(id: String): File? {
        val b = File(backupDir, "$id.jsonl.gz")
        if (b.isFile) return b
        val o = File(overflowDir, "$id.jsonl.gz")
        if (o.isFile) return o
        return null
    }

    private fun readIndex(): List<TripSummary> {
        val f = indexFile
        if (!f.isFile) return emptyList()
        return runCatching {
            DatasetJson.decodeFromString(indexSerializer, f.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(summaries: List<TripSummary>) {
        backupDir.mkdirs()
        val tmp = File(backupDir, "index.json.tmp")
        tmp.writeText(DatasetJson.encodeToString(indexSerializer, summaries), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    companion object {
        const val DEFAULT_MAX_BACKUP_BYTES = 15L * 1024L * 1024L
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")

        private fun serializeDetailToJsonlLines(detail: TripDetail): List<String> = buildList {
            val s = detail.summary
            val metaLine = TripRecordLine(
                meta = TripMetaHeader(
                    id = s.id,
                    startedAtMs = s.startedAtMs,
                    source = s.source,
                    destination = s.destination,
                )
            )
            add(DatasetJson.encodeToString(TripRecordLine.serializer(), metaLine))
            for (pt in detail.points) {
                add(DatasetJson.encodeToString(TripRecordLine.serializer(), TripRecordLine(point = pt)))
            }
            for (ev in detail.events) {
                add(DatasetJson.encodeToString(TripRecordLine.serializer(), TripRecordLine(event = ev)))
            }
        }

        private fun writeGzipLines(target: File, lines: List<String>) {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            GZIPOutputStream(BufferedOutputStream(tmp.outputStream())).bufferedWriter(Charsets.UTF_8).use { w ->
                for (l in lines) {
                    w.write(l)
                    w.newLine()
                }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }

        private fun readGzipLines(gz: File): List<String> =
            GZIPInputStream(BufferedInputStream(gz.inputStream())).bufferedReader(Charsets.UTF_8).readLines()

        private fun moveFile(src: File, dst: File): Boolean {
            if (src.renameTo(dst)) return true
            return runCatching {
                src.copyTo(dst, overwrite = true)
                src.delete()
                true
            }.getOrDefault(false)
        }
    }
}

