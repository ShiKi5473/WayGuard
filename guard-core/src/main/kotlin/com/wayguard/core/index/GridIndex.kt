package com.wayguard.core.index

import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import kotlin.math.cos
import kotlin.math.floor

/**
 * Fixed-size lat/lon grid for "what is within R metres of here" queries. With the
 * default 0.01° cells (~1.1 km) a 1 km query touches at most a few cells, so a
 * per-second lookup over thousands of cameras costs microseconds.
 */
class GridIndex<T>(
    items: Collection<T>,
    private val pos: (T) -> LatLng,
    private val cellDeg: Double = 0.01,
) {
    private val cells: Map<Long, List<T>> = items.groupBy { key(cellOf(pos(it).lat), cellOf(pos(it).lon)) }

    val size: Int = items.size

    /** Items within [radiusM] of [center] (exact great-circle distance). */
    fun near(center: LatLng, radiusM: Double): List<T> {
        val dLat = radiusM / METERS_PER_DEG_LAT
        val dLon = radiusM / (METERS_PER_DEG_LAT * cos(Math.toRadians(center.lat)).coerceAtLeast(0.01))
        val out = ArrayList<T>()
        for (la in cellOf(center.lat - dLat)..cellOf(center.lat + dLat)) {
            for (lo in cellOf(center.lon - dLon)..cellOf(center.lon + dLon)) {
                cells[key(la, lo)]?.forEach { item ->
                    if (GeoUtils.haversine(center, pos(item)) <= radiusM) out += item
                }
            }
        }
        return out
    }

    private fun cellOf(deg: Double): Int = floor(deg / cellDeg).toInt()

    private fun key(la: Int, lo: Int): Long = (la.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)

    private companion object {
        const val METERS_PER_DEG_LAT = 111_320.0
    }
}
