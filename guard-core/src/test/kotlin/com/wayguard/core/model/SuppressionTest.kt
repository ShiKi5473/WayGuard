package com.wayguard.core.model

import com.waymap.geo.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class SuppressionTest {

    private val cam = SpeedCamera("npa-a", 25.0, 121.5, 60, listOf(0))

    @Test
    fun matchesById() {
        val (active, suppressed) = Suppression.partition(listOf(cam), listOf(SuppressedMark.of(cam)))
        assertEquals(emptyList<SpeedCamera>(), active)
        assertEquals(listOf(cam), suppressed)
    }

    @Test
    fun rekeyedCameraAtTheSameSpotStaysSuppressed() {
        val moved = GeoUtils.destinationPoint(cam.pos, 10.0, 90.0)
        val rekeyed = cam.copy(id = "npa-b", lat = moved.lat, lon = moved.lon)
        val (active, _) = Suppression.partition(listOf(rekeyed), listOf(SuppressedMark.of(cam)))
        assertEquals(emptyList<SpeedCamera>(), active)
    }

    @Test
    fun otherDirectionOrFartherAwayIsNotSuppressed() {
        val opposite = cam.copy(id = "npa-c", headings = listOf(180))
        val far = GeoUtils.destinationPoint(cam.pos, 50.0, 90.0)
        val other = cam.copy(id = "npa-d", lat = far.lat, lon = far.lon)
        val (active, suppressed) = Suppression.partition(listOf(opposite, other), listOf(SuppressedMark.of(cam)))
        assertEquals(listOf(opposite, other), active)
        assertEquals(emptyList<SpeedCamera>(), suppressed)
    }
}
