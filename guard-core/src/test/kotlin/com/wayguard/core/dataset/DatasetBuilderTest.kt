package com.wayguard.core.dataset

import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.DatasetIo
import com.wayguard.core.model.Section
import com.wayguard.core.parse.NpaCsvParser
import com.wayguard.core.parse.NpaRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun reportsUnmatchedOverridesAndInvalidatesMovedRoadCheckAndTakesLowestDuplicateLimit() {
        val r = DatasetBuilder.build(
            rows = listOf(
                NpaRow(2, "桃園市", "蘆竹區", "中正路10號", "局", "分", "121.29", "25.04", "往市區", "60"),
                NpaRow(3, "桃園市", "蘆竹區", "中正路12號", "局", "分", "121.290001", "25.040001", "往北", "70"),
                NpaRow(4, "桃園市", "蘆竹區", "中正路14號", "局", "分", "121.290001", "25.040001", "往北", "50"),
            ),
            csvRejected = emptyList(),
            sections = emptyList(),
            overrides = Overrides(
                exclude = listOf("npa-gone"),
                fix = mapOf("npa-missing" to CameraFix(lat = 25.0, note = "test")),
            ),
            generatedAt = "2026-11-10T00:00:00Z",
            source = "test",
            places = listOf(Place("市區", city = "桃園市", district = "蘆竹區", lat = 25.05, lon = 121.30)),
            roadChecks = mapOf(
                DatasetBuilder.stableId(NpaRow(2, "桃園市", "蘆竹區", "中正路10號", "局", "分", "121.29", "25.04", "往市區", "60")) to
                    RoadCheck(bearing = 0, road = "中正路", distanceM = 5, checkedAt = "t", lat = 25.045, lon = 121.29),
            ),
        )
        assertEquals(listOf("fix: npa-missing", "exclude: npa-gone"), r.report.unmatchedOverrides)
        assertEquals(SkipReason.NOT_CHECKED, r.report.placeSkipped.single().skipReason)
        val merged = r.dataset.cameras.single { it.headings == listOf(0) }
        assertEquals(50, merged.limitKmh)
    }

    @Test
    fun autoAppliesHighConfidenceMismatchOnlyAndRespectsOverrides() {
        val autoRow = NpaRow(2, "桃園市", "中壢區", "中山路與中正路口", "局", "分", "121.22", "24.95", "往北", "50")
        val ovrRow = NpaRow(3, "桃園市", "中壢區", "中華路與民權路口", "局", "分", "121.23", "24.95", "往北", "50")
        val suspectRow = NpaRow(4, "桃園市", "中壢區", "中正路100號", "局", "分", "121.22", "24.961", "往北", "50")
        val autoId = DatasetBuilder.stableId(autoRow)
        val ovrId = DatasetBuilder.stableId(ovrRow)
        val suspectId = DatasetBuilder.stableId(suspectRow)

        val checks = mapOf(
            autoId to com.wayguard.core.validate.LocationCheck(
                verdict = com.wayguard.core.validate.VerdictLevel.MISMATCH,
                method = "intersection",
                distanceM = 1100,
                suggestedLat = 24.960,
                suggestedLon = 121.220,
                suggestedSource = "intersection",
                lat = 24.95,
                lon = 121.22,
            ),
            ovrId to com.wayguard.core.validate.LocationCheck(
                verdict = com.wayguard.core.validate.VerdictLevel.MISMATCH,
                method = "intersection",
                distanceM = 900,
                suggestedLat = 24.965,
                suggestedLon = 121.235,
                suggestedSource = "intersection",
                lat = 24.95,
                lon = 121.23,
            ),
            suspectId to com.wayguard.core.validate.LocationCheck(
                verdict = com.wayguard.core.validate.VerdictLevel.SUSPECT,
                method = "road",
                distanceM = 100,
                lat = 24.961,
                lon = 121.22,
            ),
        )
        val overrides = Overrides(
            fix = mapOf(ovrId to CameraFix(lat = 24.9611, lon = 121.2311, note = "manual")),
        )

        val pre = DatasetBuilder.build(
            rows = listOf(autoRow, ovrRow, suspectRow),
            csvRejected = emptyList(),
            sections = emptyList(),
            overrides = overrides,
            generatedAt = "2026-11-16T00:00:00Z",
            source = "test",
            locationChecks = checks,
            applyAutoFixes = false,
        )
        assertEquals(24.95, pre.dataset.cameras.single { it.id == autoId }.lat, 1e-6)

        val applied = DatasetBuilder.build(
            rows = listOf(autoRow, ovrRow, suspectRow),
            csvRejected = emptyList(),
            sections = emptyList(),
            overrides = overrides,
            generatedAt = "2026-11-16T00:00:00Z",
            source = "test",
            locationChecks = checks,
            applyAutoFixes = true,
        )
        val autoCam = applied.dataset.cameras.single { it.id == autoId }
        assertEquals(24.960, autoCam.lat, 1e-6)
        assertEquals(121.220, autoCam.lon, 1e-6)
        assertEquals(null, autoCam.loc)
        assertEquals("intersection", autoCam.posSource)

        val ovrCam = applied.dataset.cameras.single { it.id == ovrId }
        assertEquals(24.9611, ovrCam.lat, 1e-6)
        assertEquals("override", ovrCam.posSource)

        val susCam = applied.dataset.cameras.single { it.id == suspectId }
        assertEquals(24.961, susCam.lat, 1e-6)
        assertEquals("suspect", susCam.loc)
        assertEquals(null, susCam.posSource)

        assertEquals(listOf(autoId), applied.report.autoFixedLocations.map { it.first.id })
        assertEquals(listOf(suspectId), applied.report.locationIssues.map { it.first.id })
    }

    @Test
    fun mergeKnownRoadsReusesExtraSetWhenCovered() {
        val extra = setOf("中正路", "中山路")
        val coveredChecks = mapOf(
            "c1" to RoadCheck(bearing = 0, road = "中正路二段", distanceM = 5, checkedAt = "t"),
            "c2" to RoadCheck(bearing = 90, road = "", distanceM = 5, checkedAt = "t"),
        )
        assertSame(extra, DatasetBuilder.mergeKnownRoads(emptyMap(), extra))
        assertSame(extra, DatasetBuilder.mergeKnownRoads(coveredChecks, extra))

        val withNewRoad = coveredChecks + ("c3" to RoadCheck(bearing = 180, road = "和平西路一段", distanceM = 5, checkedAt = "t"))
        assertEquals(setOf("和平西路", "中正路", "中山路"), DatasetBuilder.mergeKnownRoads(withNewRoad, extra))
        assertEquals(setOf("中正路", "和平西路"), DatasetBuilder.mergeKnownRoads(withNewRoad, emptySet()))
    }
}
