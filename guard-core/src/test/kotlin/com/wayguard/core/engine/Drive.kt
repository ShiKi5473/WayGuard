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

/**
 * Turn along a circular arc of [radiusM], one fix per second with the GPS bearing on
 * the tangent: [turnDeg] > 0 turns right (clockwise), < 0 left. Ends exactly
 * [turnDeg] from [startBearing].
 */
internal fun arc(from: LatLng, startBearing: Double, turnDeg: Double, radiusM: Double, speedKmh: Double, startMs: Long = 0L): List<Fix> {
    val length = Math.toRadians(kotlin.math.abs(turnDeg)) * radiusM
    val n = maxOf(1, kotlin.math.round(length / GeoUtils.kmhToMs(speedKmh)).toInt())
    val side = if (turnDeg > 0) 90.0 else -90.0
    val center = GeoUtils.destinationPoint(from, radiusM, startBearing + side)
    return (0..n).map { s ->
        val bearing = ((startBearing + turnDeg * s / n) % 360.0 + 360.0) % 360.0
        Fix(GeoUtils.destinationPoint(center, radiusM, bearing + side + 180.0), speedKmh, bearing, startMs + s * 1000L)
    }
}

/** Standing still at [pos] for [seconds] (no GPS bearing), e.g. waiting mid-turn. */
internal fun stop(pos: LatLng, seconds: Int, startMs: Long): List<Fix> =
    (0 until seconds).map { s -> Fix(pos, 0.0, null, startMs + s * 1000L) }

/** Runs fixes through a fresh heading filter + [engine], collecting all events. */
internal fun run(engine: AlertEngine, fixes: List<Fix>): List<Pair<Fix, AlertEvent>> {
    val heading = HeadingFilter()
    return fixes.flatMap { f -> engine.update(f, heading.update(f)).events.map { f to it } }
}
