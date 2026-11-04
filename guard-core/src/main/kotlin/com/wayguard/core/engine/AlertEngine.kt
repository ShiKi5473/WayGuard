package com.wayguard.core.engine

import com.wayguard.core.index.GridIndex
import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.SpeedCamera
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng

/** User-tunable alert behaviour. Distances in metres, speeds in km/h. */
data class AlertConfig(
    /** First warning distance below 50 km/h. */
    val approachSlowM: Double = 300.0,
    /** First warning distance at 50–80 km/h. */
    val approachMidM: Double = 500.0,
    /** First warning distance at 80 km/h and above. */
    val approachFastM: Double = 800.0,
    /** Second ("near") warning distance. */
    val nearM: Double = 150.0,
    /** Below this the vehicle counts as stopped: no new warnings, no state changes. */
    val minSpeedKmh: Double = 5.0,
    /** The camera must be within ± this of the direction of travel to count as ahead. */
    val aheadToleranceDeg: Double = 35.0,
    /** A one-way camera matches when travel is within ± this of its heading. */
    val headingToleranceDeg: Double = 70.0,
    /** Overspeed = speed > limit + this. */
    val overspeedToleranceKmh: Int = 0,
    val enabledKinds: Set<CameraKind> = CameraKind.entries.toSet(),
) {
    fun approachDistance(speedKmh: Double): Double = when {
        speedKmh < 50 -> approachSlowM
        speedKmh < 80 -> approachMidM
        else -> approachFastM
    }
}

enum class AlertType {
    /** Entered the first warning distance of a relevant camera. */
    APPROACH,
    /** Entered the near warning distance. */
    NEAR,
    /** Started exceeding the limit of a camera being approached. */
    OVERSPEED,
    /** Drove past the camera. */
    PASSED,
}

data class AlertEvent(val type: AlertType, val camera: SpeedCamera, val distanceM: Double, val speedKmh: Double)

/** The nearest camera currently being approached. */
data class ActiveCamera(val camera: SpeedCamera, val distanceM: Double, val overspeed: Boolean)

data class EngineResult(val events: List<AlertEvent>, val active: ActiveCamera?)

/**
 * Decides when to warn about cameras. Per camera the stages are
 * approaching → near → done (passed, or turned away), then forgotten once far
 * enough away that a later pass warns again.
 *
 * A camera is relevant only when it lies ahead of the direction of travel and, for
 * one-way cameras, the travel direction matches the enforced one. Without a road
 * network, a parallel road very close by can still be mistaken for the camera's road.
 */
class AlertEngine(config: AlertConfig = AlertConfig()) {

    var config: AlertConfig = config
        set(value) {
            field = value
            tracks.clear()
        }

    private var index: GridIndex<SpeedCamera>? = null

    private enum class Stage { APPROACHING, NEAR, DONE }

    private class Track(
        val camera: SpeedCamera,
        var stage: Stage,
        var minDist: Double,
        var lastDist: Double,
        var misses: Int = 0,
        var overspeed: Boolean = false,
    )

    private val tracks = HashMap<String, Track>()

    /** Replaces the cameras (e.g. after a data update or a false-alarm mark). */
    fun setCameras(index: GridIndex<SpeedCamera>) {
        this.index = index
        tracks.clear()
    }

    fun reset() = tracks.clear()

    fun update(fix: Fix, heading: Double?): EngineResult {
        val idx = index ?: return EngineResult(emptyList(), null)
        val cfg = config
        val events = ArrayList<AlertEvent>()
        val approach = cfg.approachDistance(fix.speedKmh)
        val moving = fix.speedKmh >= cfg.minSpeedKmh
        val nearby = idx.near(fix.pos, maxOf(cfg.approachFastM, approach) + RELEASE_MARGIN_M)
        val seen = HashSet<String>(nearby.size)

        for (cam in nearby) {
            if (cam.kinds.none { it in cfg.enabledKinds }) continue
            seen += cam.id
            val d = GeoUtils.haversine(fix.pos, cam.pos)
            val t = tracks[cam.id]

            if (t == null) {
                if (!moving || heading == null || d > approach) continue
                if (!isAhead(fix.pos, cam, heading, d, cfg) || !headingMatches(cam, heading, cfg)) continue
                val near = d <= cfg.nearM
                val nt = Track(cam, if (near) Stage.NEAR else Stage.APPROACHING, d, d)
                tracks[cam.id] = nt
                events += AlertEvent(if (near) AlertType.NEAR else AlertType.APPROACH, cam, d, fix.speedKmh)
                checkOverspeed(nt, fix, d, cfg, events)
                continue
            }

            if (t.stage == Stage.DONE) {
                t.lastDist = d
                continue
            }
            if (!moving) {
                // Stopped (e.g. at a light): GPS drift must not count as passing or turning.
                t.overspeed = false
                continue
            }

            val ahead = heading != null && isAhead(fix.pos, cam, heading, d, cfg)
            val receding = d > t.minDist + PASS_HYSTERESIS_M
            if (receding && (!ahead || t.minDist <= cfg.nearM)) {
                t.stage = Stage.DONE
                t.overspeed = false
                events += AlertEvent(AlertType.PASSED, cam, d, fix.speedKmh)
                continue
            }
            if (!ahead && d > CLOSE_M) {
                // Turned off the road: stop warning without announcing a pass.
                if (++t.misses >= MAX_MISSES) {
                    t.stage = Stage.DONE
                    t.overspeed = false
                    continue
                }
            } else {
                t.misses = 0
            }
            t.minDist = minOf(t.minDist, d)
            t.lastDist = d
            if (t.stage == Stage.APPROACHING && d <= cfg.nearM) {
                t.stage = Stage.NEAR
                events += AlertEvent(AlertType.NEAR, cam, d, fix.speedKmh)
            }
            checkOverspeed(t, fix, d, cfg, events)
        }

        // Out of the search radius: forget, so the next pass warns again.
        tracks.keys.retainAll(seen)

        val active = tracks.values
            .filter { it.stage != Stage.DONE }
            .minByOrNull { it.lastDist }
            ?.let { ActiveCamera(it.camera, it.lastDist, it.overspeed) }
        return EngineResult(events, active)
    }

    private fun checkOverspeed(t: Track, fix: Fix, d: Double, cfg: AlertConfig, events: MutableList<AlertEvent>) {
        val limit = t.camera.limitKmh ?: return
        val over = fix.speedKmh > limit + cfg.overspeedToleranceKmh
        if (over && !t.overspeed) events += AlertEvent(AlertType.OVERSPEED, t.camera, d, fix.speedKmh)
        t.overspeed = over
    }

    private fun isAhead(pos: LatLng, cam: SpeedCamera, heading: Double, d: Double, cfg: AlertConfig): Boolean =
        d <= CLOSE_M || GeoUtils.angleDiff(GeoUtils.bearing(pos, cam.pos), heading) <= cfg.aheadToleranceDeg

    private fun headingMatches(cam: SpeedCamera, heading: Double, cfg: AlertConfig): Boolean =
        cam.headings.isEmpty() ||
            cam.headings.any { GeoUtils.angleDiff(it.toDouble(), heading) <= cfg.headingToleranceDeg }

    private companion object {
        /** Closer than this, bearing-to-camera is too noisy to judge "ahead". */
        const val CLOSE_M = 30.0
        /** Distance must grow this much past the closest point to count as passed. */
        const val PASS_HYSTERESIS_M = 20.0
        /** Consecutive "not ahead" fixes before treating the camera as turned away from. */
        const val MAX_MISSES = 3
        /** Extra search radius so tracks can finish before being forgotten. */
        const val RELEASE_MARGIN_M = 200.0
    }
}
