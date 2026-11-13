package com.wayguard

import com.wayguard.core.trip.TrackPoint
import com.wayguard.core.trip.TripDetail
import com.wayguard.core.trip.TripEvent
import com.wayguard.core.trip.TripEventType
import com.wayguard.core.trip.TripStats
import com.wayguard.trip.TripStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStoreTest {

    private fun tempStorage(maxBackupBytes: Long = TripStorage.DEFAULT_MAX_BACKUP_BYTES): Pair<File, TripStorage> {
        val root = Files.createTempDirectory("wayguard-trip-test").toFile()
        root.deleteOnExit()
        val backup = File(root, "backup")
        val overflow = File(root, "overflow")
        return root to TripStorage(backup, overflow, maxBackupBytes)
    }

    @Test
    fun recoversLeftoverJsonlOnStartupAndCompresses() {
        val (root, storage) = tempStorage()
        val t0 = 1_700_000_000_000L
        storage.appendLines(
            "trip-crash",
            listOf(
                """{"meta":{"id":"trip-crash","startedAtMs":$t0,"source":"MANUAL"}}""",
                """{"point":{"tMs":$t0,"lat":25.033,"lon":121.565,"speedKmh":40.0,"bearing":0.0}}""",
                """{"point":{"tMs":${t0 + 5000},"lat":25.034,"lon":121.565,"speedKmh":45.0,"bearing":0.0}}""",
                """{"point":{"tMs":${t0 + 9000},"lat":25.03""", // truncated last line
            ),
        )

        val recovered = storage.loadAndRecover(nowMs = t0 + 60_000L, retentionDays = 90)
        assertEquals(1, recovered.size)
        val s = recovered.single()
        assertEquals("trip-crash", s.id)
        assertTrue(s.abnormalEnd)
        assertTrue(s.backedUp)
        assertFalse(File(root, "backup/trip-crash.jsonl").exists())
        assertTrue(File(root, "backup/trip-crash.jsonl.gz").isFile)

        val loaded = storage.loadDetail(s)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.points.size)
    }

    @Test
    fun exportsAndImportsZipRoundTrip() {
        val t0 = 1_700_000_000_000L
        val (_, src) = tempStorage()
        val pts = listOf(
            TrackPoint(t0, 25.033, 121.565, 50.0, 0f),
            TrackPoint(t0 + 5_000L, 25.034, 121.565, 55.0, 0f),
        )
        val evs = listOf(
            TripEvent(t0 + 3_000L, TripEventType.OVERSPEED, 25.0335, 121.565, 65.0, limitKmh = 50),
        )
        val summary = TripStats.summarize("trip-zip-1", t0, t0 + 5_000L, pts, evs, destination = "新竹車站")
        src.finalizeTrip(TripDetail(summary, pts, evs), nowMs = t0 + 10_000L, retentionDays = 90)

        val zipBytes = ByteArrayOutputStream()
        val exported = src.exportZip(zipBytes)
        assertEquals(1, exported)

        val (_, dst) = tempStorage()
        val (importedList, importedCount) = dst.importZip(
            ByteArrayInputStream(zipBytes.toByteArray()),
            nowMs = t0 + 20_000L,
            retentionDays = 90,
        )
        assertEquals(1, importedCount)
        assertEquals(1, importedList.size)
        assertEquals("新竹車站", importedList[0].destination)
        val detail = dst.loadDetail(importedList[0])
        assertNotNull(detail)
        assertEquals(2, detail!!.points.size)
        assertEquals(1, detail.events.size)
    }

    @Test
    fun enforcesBackupQuotaByMovingOldestToOverflow() {
        // Set a small quota (~750 B) so 1 trip + index.json fits, while a 2nd trip forces overflow.
        val (root, storage) = tempStorage(maxBackupBytes = 750L)
        val t0 = 1_700_000_000_000L

        fun makeTrip(id: String, startOffsetMs: Long): TripDetail {
            val sMs = t0 + startOffsetMs
            val pts = (0..15).map { idx ->
                TrackPoint(sMs + idx * 2_000L, 25.03 + idx * 0.0005, 121.56 + idx * 0.0002, 50.0, 15f)
            }
            val summary = TripStats.summarize(id, sMs, sMs + 30_000L, pts, emptyList())
            return TripDetail(summary, pts, emptyList())
        }

        storage.finalizeTrip(makeTrip("trip-old", 0L), nowMs = t0 + 100_000L, retentionDays = 90)
        val afterSecond = storage.finalizeTrip(makeTrip("trip-new", 50_000L), nowMs = t0 + 100_000L, retentionDays = 90)

        assertEquals(2, afterSecond.size)
        val newest = afterSecond.first { it.id == "trip-new" }
        val oldest = afterSecond.first { it.id == "trip-old" }
        assertTrue(newest.backedUp)
        assertFalse(oldest.backedUp)
        assertTrue(File(root, "overflow/trip-old.jsonl.gz").isFile)
        assertFalse(File(root, "backup/trip-old.jsonl.gz").exists())
        // Overflowed trips remain readable!
        assertEquals(16, storage.loadDetail(oldest)!!.points.size)
    }
}

