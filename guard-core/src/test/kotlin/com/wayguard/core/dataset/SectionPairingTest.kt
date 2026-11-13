package com.wayguard.core.dataset

import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.Section
import com.wayguard.core.model.SpeedCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionPairingTest {

    private fun cam(id: String, lat: Double, lon: Double, address: String, limit: Int = 60, city: String = "臺東縣") =
        SpeedCamera(id, lat, lon, limit, kinds = setOf(CameraKind.SPEED, CameraKind.SECTION), city = city, address = address)

    // The two 小馬 rows as published, one per direction, each at its own start camera.
    private val north = cam("a", 23.007769, 121.323524, "臺11線126.13K至129.61(小馬)")
    private val south = cam("b", 22.980171, 121.30966, "臺11線129.61至126.13K(小馬)")

    @Test
    fun readsKilometreRanges() {
        assertEquals(SectionPairing.KmRange("台11線", 126.13, 129.61), SectionPairing.kmRange("臺11線126.13K至129.61(小馬)"))
        assertEquals(SectionPairing.KmRange("金門大橋", 5.0, 0.5), SectionPairing.kmRange("金門大橋5K至0.5K(西向)"))
        assertEquals(SectionPairing.KmRange("台3線", 389.5, 392.5), SectionPairing.kmRange("內門區台3線389.5K至392.5K"))
        val withMetres = SectionPairing.kmRange("台9線137K+295m至141K+458m(谷風隧道)")!!
        assertEquals(137.295, withMetres.fromKm, 1e-9)
        assertEquals(141.458, withMetres.toKm, 1e-9)
        assertNull(SectionPairing.kmRange("萬壽路一段(東萬壽路309巷-萬壽路一段1275巷)"))
    }

    @Test
    fun oppositeRowsPairIntoTwoSections() {
        val sections = SectionPairing.pair(listOf(north, south), emptyList())
        assertEquals(listOf("auto-a", "auto-b"), sections.map { it.id })
        val ab = sections[0]
        assertEquals(north.lat, ab.startLat, 0.0)
        assertEquals(south.lat, ab.endLat, 0.0)
        assertEquals(3480.0, ab.lengthM, 0.0)
        assertEquals(60, ab.limitKmh)
        // a lies north of b: travelling a → b heads roughly south.
        assertTrue(ab.heading in 180..240)
        assertEquals("台11線 126.13K–129.61K", ab.name)
    }

    @Test
    fun mismatchedRowsDoNotPair() {
        // Different limit.
        assertTrue(SectionPairing.pair(listOf(north, south.copy(limitKmh = 70)), emptyList()).isEmpty())
        // Same direction.
        assertTrue(SectionPairing.pair(listOf(north, south.copy(address = "臺11線129.61K至133K")), emptyList()).isEmpty())
        // Points much closer than the kilometre length.
        assertTrue(SectionPairing.pair(listOf(north, south.copy(lat = 23.0, lon = 121.32)), emptyList()).isEmpty())
        // Ambiguous: two possible partners.
        val twin = south.copy(id = "c", lat = south.lat + 0.001)
        assertTrue(SectionPairing.pair(listOf(north, south, twin), emptyList()).isEmpty())
    }

    @Test
    fun curatedSectionAtTheSameStartWins() {
        val curated = Section("osm", "人工", north.lat + 0.0005, north.lon, south.lat, south.lon, 3500.0, 60, heading = 205)
        assertEquals(listOf("auto-b"), SectionPairing.pair(listOf(north, south), listOf(curated)).map { it.id })
    }
}
