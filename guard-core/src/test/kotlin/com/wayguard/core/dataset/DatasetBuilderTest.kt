package com.wayguard.core.dataset

import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.DatasetIo
import com.wayguard.core.model.Section
import com.wayguard.core.parse.NpaCsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatasetBuilderTest {

    private val parsed = NpaCsvParser.parse(
        javaClass.getResourceAsStream("/npa-sample.csv")!!.readBytes().toString(Charsets.UTF_8)
    )

    private fun build(overrides: Overrides = Overrides(), at: String = "2026-11-03T00:00:00Z", sections: List<Section> = emptyList()) =
        DatasetBuilder.build(parsed.rows, parsed.rejected, sections, overrides, at, "test")

    @Test
    fun buildsValidatedDatasetAndReport() {
        val r = build()
        val cams = r.dataset.cameras
        // 11 rows − 1 bad latitude − 1 same-spot duplicate + 1 extra from the per-direction split.
        assertEquals(10, cams.size)
        assertEquals(12, r.report.sourceRows)
        assertEquals(2, r.report.rejected.size)
        assertEquals(1, r.report.duplicates)
        assertEquals(mapOf("往市區" to 1), r.report.unknownDirections)
        assertEquals(1, r.report.sectionCandidates.size)
        assertEquals(0, r.report.missingLimit)
    }

    @Test
    fun outOfCountyLatitudeIsRejectedWithItsId() {
        val bad = build().report.rejected.single { it.id != null }
        assertTrue(bad.raw.contains("26.645304"))
        assertTrue(bad.reason.contains("苗栗縣"))
    }

    @Test
    fun swappedCoordinatesAreCorrected() {
        val r = build()
        assertEquals(listOf(8), r.report.swappedLines)
        val c = r.dataset.cameras.single { it.city == "臺中市" }
        assertEquals(24.169, c.lat, 1e-9)
        assertEquals(120.642, c.lon, 1e-9)
        assertEquals(listOf(270), c.headings)
    }

    @Test
    fun perDirectionLimitsBecomeTwoCameras() {
        val split = build().dataset.cameras.filter { it.city == "屏東縣" }
        assertEquals(2, split.size)
        assertEquals(mapOf(180 to 60, 0 to 70), split.associate { it.headings.single() to it.limitKmh })
        assertTrue(split.all { it.id.contains("-h") })
    }

    @Test
    fun regionIsReducedToTheDistrict() {
        val kinmen = build().dataset.cameras.filter { it.city == "金門縣" }.map { it.district }.toSet()
        assertEquals(setOf("金湖鎮", "金城鎮"), kinmen)
    }

    @Test
    fun sectionNoteBecomesAKind() {
        val c = build().dataset.cameras.single { it.city == "臺東縣" }
        assertEquals(setOf(CameraKind.SPEED, CameraKind.SECTION), c.kinds)
        assertEquals(listOf(0), c.headings)
    }

    @Test
    fun idsAndVersionAreStableAcrossRuns() {
        val a = build(at = "2026-11-03T00:00:00Z").dataset
        val b = build(at = "2026-11-10T00:00:00Z").dataset
        assertEquals(a.cameras.map { it.id }, b.cameras.map { it.id })
        assertEquals(a.version, b.version)
        assertNotEquals(a.generatedAt, b.generatedAt)
    }

    @Test
    fun overridesFixExcludeAndAdd() {
        val badId = build().report.rejected.single { it.id != null }.id!!
        val excludeId = build().dataset.cameras.single { it.city == "宜蘭縣" }.id
        val extra = build().dataset.cameras.first().copy(id = "manual-1", city = "測試", kinds = setOf(CameraKind.MOBILE))
        val r = build(
            Overrides(
                exclude = listOf(excludeId),
                fix = mapOf(badId to CameraFix(lat = 24.645304, note = "緯度筆誤")),
                add = listOf(extra),
            )
        )
        val cams = r.dataset.cameras
        assertTrue(cams.any { it.id == badId && it.lat == 24.645304 && it.lon == 120.83167 })
        assertNull(cams.firstOrNull { it.id == excludeId })
        assertTrue(cams.any { it.id == "manual-1" })
        assertEquals(1, r.report.fixed)
        assertEquals(1, r.report.excluded)
        assertEquals(1, r.report.added)
        assertNotEquals(build().dataset.version, r.dataset.version)
    }

    @Test
    fun datasetFileRoundTrips() {
        val section = Section("s1", "測試區間", 22.80, 121.10, 22.82, 121.10, 2200.0, 70, heading = 0)
        val ds = build(sections = listOf(section)).dataset
        val text = DatasetIo.encode(ds)
        assertEquals(ds, DatasetIo.decode(text))
        // One camera per line keeps git diffs readable.
        assertEquals(ds.cameras.size, text.lines().count { it.startsWith("{\"id\":\"npa-") })
    }
}
