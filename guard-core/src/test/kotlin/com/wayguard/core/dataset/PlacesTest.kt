package com.wayguard.core.dataset

import com.wayguard.core.parse.NpaCsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {

    private val nationwide = Place("市區", lat = 0.0, lon = 0.0, ref = "nationwide")
    private val yilan = Place("市區", city = "宜蘭縣", lat = 1.0, lon = 1.0, ref = "city")
    private val toucheng = Place("市區", aliases = listOf("頭城市區"), city = "宜蘭縣", district = "頭城鎮", lat = 2.0, lon = 2.0, ref = "district")
    private val taipei = Place("臺北市", city = null, lat = 25.04, lon = 121.56)

    @Test
    fun mostSpecificScopeWins() {
        val dir = PlaceDirectory(listOf(nationwide, yilan, toucheng))
        assertEquals("district", dir.find("市區", "宜蘭縣", "頭城鎮")?.ref)
        assertEquals("city", dir.find("市區", "宜蘭縣", "礁溪鄉")?.ref)
        assertEquals("nationwide", dir.find("市區", "花蓮縣", "花蓮市")?.ref)
        // Without a district entry, the city entry covers every district of the city.
        assertEquals("city", PlaceDirectory(listOf(nationwide, yilan)).find("市區", "宜蘭縣", "頭城鎮")?.ref)
    }

    @Test
    fun aliasesAnd台臺SpellingsMatch() {
        val dir = PlaceDirectory(listOf(toucheng, taipei))
        assertEquals("district", dir.find("頭城市區", "宜蘭縣", "頭城鎮")?.ref)
        // Nationwide entry, looked up with the other spelling of 臺.
        assertEquals(taipei, dir.find("台北市", "臺北市", "萬華區"))
    }

    @Test
    fun scopedEntriesDontLeakIntoOtherCities() {
        val dir = PlaceDirectory(listOf(toucheng))
        assertNull(dir.find("市區", "臺北市", "北投區"))
    }

    // ---- DatasetBuilder integration (sample row: 宜蘭縣頭城鎮, 往市區, at 24.86, 121.86) ----

    private val parsed = NpaCsvParser.parse(
        javaClass.getResourceAsStream("/npa-sample.csv")!!.readBytes().toString(Charsets.UTF_8)
    )

    private fun build(places: List<Place>) =
        DatasetBuilder.build(parsed.rows, parsed.rejected, emptyList(), Overrides(), "2026-11-04T00:00:00Z", "test", places)

    @Test
    fun knownDestinationGivesAWideToleranceHeading() {
        // 2.2 km due north of the camera.
        val north = Place("市區", city = "宜蘭縣", district = "頭城鎮", lat = 24.88, lon = 121.86)
        val r = build(listOf(north))
        val cam = r.dataset.cameras.single { it.city == "宜蘭縣" }
        assertEquals(listOf(0), cam.headings)
        assertEquals(DatasetBuilder.PLACE_HEADING_TOLERANCE_DEG, cam.headingToleranceDeg)
        assertEquals(1, r.report.placeDerived.size)
        assertEquals(2224.0, r.report.placeDerived.single().distanceM, 5.0)
        assertTrue(r.report.unknownDirections.isEmpty())
        assertTrue(r.report.unmatchedPlaces.isEmpty())
    }

    @Test
    fun unknownDestinationIsReportedWithItsScopeAndStaysBothWays() {
        val r = build(emptyList())
        val cam = r.dataset.cameras.single { it.city == "宜蘭縣" }
        assertTrue(cam.headings.isEmpty())
        assertNull(cam.headingToleranceDeg)
        assertEquals(setOf(PlaceRef("宜蘭縣", "頭城鎮", "市區")), r.report.unmatchedPlaces.keys)
        assertEquals(mapOf("往市區" to 1), r.report.unknownDirections)
    }

    @Test
    fun destinationNextToTheCameraIsNotUsed() {
        val adjacent = Place("市區", city = "宜蘭縣", district = "頭城鎮", lat = 24.8605, lon = 121.86)
        val r = build(listOf(adjacent))
        assertTrue(r.dataset.cameras.single { it.city == "宜蘭縣" }.headings.isEmpty())
        assertEquals(1, r.report.placeTooClose.size)
        assertEquals(mapOf("往市區" to 1), r.report.unknownDirections)
    }

    @Test
    fun overrideHeadingsWinOverPlaces() {
        val north = Place("市區", city = "宜蘭縣", district = "頭城鎮", lat = 24.88, lon = 121.86)
        val id = build(emptyList()).dataset.cameras.single { it.city == "宜蘭縣" }.id
        val r = DatasetBuilder.build(
            parsed.rows, parsed.rejected, emptyList(),
            Overrides(fix = mapOf(id to CameraFix(headings = listOf(180), headingToleranceDeg = 90))),
            "2026-11-04T00:00:00Z", "test", listOf(north),
        )
        val cam = r.dataset.cameras.single { it.id == id }
        assertEquals(listOf(180), cam.headings)
        assertEquals(90, cam.headingToleranceDeg)
        assertTrue(r.report.placeDerived.isEmpty())
    }
}
