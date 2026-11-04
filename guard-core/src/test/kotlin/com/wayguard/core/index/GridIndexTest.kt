package com.wayguard.core.index

import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class GridIndexTest {

    @Test
    fun nearMatchesBruteForce() {
        val rnd = Random(42)
        val points = List(3000) { LatLng(22.0 + rnd.nextDouble() * 3.0, 120.0 + rnd.nextDouble() * 2.0) }
        val index = GridIndex(points, { it })
        repeat(200) {
            val c = LatLng(22.0 + rnd.nextDouble() * 3.0, 120.0 + rnd.nextDouble() * 2.0)
            val r = 500.0 + rnd.nextDouble() * 5000.0
            val expected = points.filter { GeoUtils.haversine(c, it) <= r }.toSet()
            assertEquals(expected, index.near(c, r).toSet())
        }
    }

    @Test
    fun cellBoundariesAndNegativeCoordinatesWork() {
        val pts = listOf(LatLng(0.0, 0.0), LatLng(-0.0001, -0.0001), LatLng(0.0099, 0.0101))
        val index = GridIndex(pts, { it })
        assertEquals(2, index.near(LatLng(0.0, 0.0), 50.0).size)
        assertEquals(3, index.near(LatLng(0.005, 0.005), 2000.0).size)
    }
}
