package com.wayguard.core.engine

import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng

/** Straight-line drive: one fix per second at [speedKmh] along [bearing]. */
internal fun drive(from: LatLng, bearing: Double, speedKmh: Double, seconds: Int, startMs: Long = 0L): List<Fix> {
    val step = GeoUtils.kmhToMs(speedKmh)
    return (0..seconds).map { s ->
        Fix(GeoUtils.destinationPoint(from, step * s, bearing), speedKmh, bearing, startMs + s * 1000L)
    }
}

/** Runs fixes through a fresh heading filter + [engine], collecting all events. */
internal fun run(engine: AlertEngine, fixes: List<Fix>): List<Pair<Fix, AlertEvent>> {
    val heading = HeadingFilter()
    return fixes.flatMap { f -> engine.update(f, heading.update(f)).events.map { f to it } }
}
