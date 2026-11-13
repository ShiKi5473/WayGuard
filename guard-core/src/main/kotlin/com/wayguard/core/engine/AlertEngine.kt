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
    /**
     * A one-way camera matches when travel is within ± this of its heading (unless the
     * camera carries its own [SpeedCamera.headingToleranceDeg]).
     */
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
        var camera: SpeedCamera,
        val memberIds: MutableSet<String>,
        var stage: Stage,
        var minDist: Double,
        var lastDist: Double,
        var misses: Int = 0,
        var offCourse: Boolean = false,
        var overspeed: Boolean = false,
        var lastOverspeedMs: Long = Long.MIN_VALUE,
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
            .filter { cam -> cam.kinds.any { it in cfg.enabledKinds } }
            .sortedBy { GeoUtils.haversine(fix.pos, it.pos) }
        val seen = HashSet<String>(nearby.size)
        val processedTracks = HashSet<Track>()
        val newCandidates = ArrayList<Pair<SpeedCamera, Double>>()

        for (cam in nearby) {
            seen += cam.id
            val d = GeoUtils.haversine(fix.pos, cam.pos)
            val t = tracks[cam.id]

            if (t == null) {
                if (moving && heading != null && d <= approach &&
                    isAhead(fix.pos, cam, heading, d, cfg) && headingMatches(cam, heading, cfg)
                ) {
                    newCandidates += cam to d
                }
                continue
            }

            if (!processedTracks.add(t)) continue
            val camDist = GeoUtils.haversine(fix.pos, t.camera.pos)
            if (t.stage == Stage.DONE) {
                t.lastDist = camDist
                continue
            }
            if (!moving) {
                // Stopped (e.g. at a light): GPS drift must not count as passing or turning.
                t.overspeed = false
                continue
            }

            val ahead = heading != null && isAhead(fix.pos, t.camera, heading, camDist, cfg)
            val receding = camDist > t.minDist + PASS_HYSTERESIS_M
            if (receding && t.minDist <= PASS_DIST_M) {
                t.stage = Stage.DONE
                t.overspeed = false
                events += AlertEvent(AlertType.PASSED, t.camera, camDist, fix.speedKmh)
                continue
            }
            if (t.offCourse) {
                val dirOk = ahead && headingMatches(t.camera, heading, cfg)
                if (dirOk && camDist < t.lastDist) {
                    t.offCourse = false
                    t.misses = 0
                } else {
                    t.lastDist = camDist
                    continue
                }
            }
            if (!ahead && camDist > CLOSE_M) {
                // Temporarily off-course (e.g. turning before the camera or on a hairpin):
                // pause warnings without marking DONE so returning to the approach resumes tracking.
                if (++t.misses >= MAX_MISSES) {
                    t.offCourse = true
                    t.overspeed = false
                    t.lastDist = camDist
                    continue
                }
            } else {
                t.misses = 0
            }
            t.minDist = minOf(t.minDist, camDist)
            t.lastDist = camDist
            if (t.stage == Stage.APPROACHING && camDist <= cfg.nearM) {
                t.stage = Stage.NEAR
                events += AlertEvent(AlertType.NEAR, t.camera, camDist, fix.speedKmh)
            }
            checkOverspeed(t, fix, camDist, cfg, events)
        }

        // Merge new nearby overlapping cameras within CLUSTER_M into an existing or new track.
        val unmatched = ArrayList<Pair<SpeedCamera, Double>>()
        for ((cam, d) in newCandidates) {
            val host = tracks.values.firstOrNull { tr ->
                tr.stage != Stage.DONE &&
                    GeoUtils.haversine(tr.camera.pos, cam.pos) <= CLUSTER_M &&
                    headingsOverlap(tr.camera, cam, cfg)
            }
            if (host != null) {
                mergeInto(host, cam)
            } else {
                unmatched += cam to d
            }
        }
        val clusterUsed = BooleanArray(unmatched.size)
        for (i in unmatched.indices) {
            if (clusterUsed[i]) continue
            val (lead, d) = unmatched[i]
            if (lead.id in tracks) continue
            val members = linkedSetOf(lead.id)
            var mergedCam = lead
            for (j in i + 1 until unmatched.size) {
                if (clusterUsed[j]) continue
                val other = unmatched[j].first
                if (GeoUtils.haversine(mergedCam.pos, other.pos) <= CLUSTER_M && headingsOverlap(mergedCam, other, cfg)) {
                    clusterUsed[j] = true
                    members += other.id
                    mergedCam = mergeCamera(mergedCam, other)
                }
            }
            for (other in nearby) {
                if (other.id in tracks || other.id in members) continue
                if (GeoUtils.haversine(mergedCam.pos, other.pos) <= CLUSTER_M && headingsOverlap(mergedCam, other, cfg)) {
                    members += other.id
                    mergedCam = mergeCamera(mergedCam, other)
                }
            }
            val near = d <= cfg.nearM
            val nt = Track(mergedCam, members, if (near) Stage.NEAR else Stage.APPROACHING, d, d)
            members.forEach { tracks[it] = nt }
            events += AlertEvent(if (near) AlertType.NEAR else AlertType.APPROACH, mergedCam, d, fix.speedKmh)
            checkOverspeed(nt, fix, d, cfg, events)
        }

        // Out of the search radius: forget, so the next pass warns again.
        tracks.keys.retainAll(seen)

        val active = tracks.values
            .distinct()
            .filter { it.stage != Stage.DONE && !it.offCourse }
            .minByOrNull { it.lastDist }
            ?.let { ActiveCamera(it.camera, it.lastDist, it.overspeed) }
        return EngineResult(events, active)
    }

    private fun mergeInto(t: Track, other: SpeedCamera) {
        t.memberIds += other.id
        tracks[other.id] = t
        t.camera = mergeCamera(t.camera, other)
    }

    private fun mergeCamera(base: SpeedCamera, other: SpeedCamera): SpeedCamera = base.copy(
        limitKmh = listOfNotNull(base.limitKmh, other.limitKmh).minOrNull(),
        kinds = base.kinds + other.kinds,
    )

    private fun headingsOverlap(a: SpeedCamera, b: SpeedCamera, cfg: AlertConfig): Boolean {
        if (a.headings.isEmpty() || b.headings.isEmpty()) return true
        val tol = maxOf(
            a.headingToleranceDeg?.toDouble() ?: cfg.headingToleranceDeg,
            b.headingToleranceDeg?.toDouble() ?: cfg.headingToleranceDeg,
        )
        return a.headings.any { ha -> b.headings.any { hb -> GeoUtils.angleDiff(ha.toDouble(), hb.toDouble()) <= tol } }
    }

    private fun checkOverspeed(t: Track, fix: Fix, d: Double, cfg: AlertConfig, events: MutableList<AlertEvent>) {
        val limit = t.camera.limitKmh ?: return
        val threshold = limit + cfg.overspeedToleranceKmh
        if (t.overspeed) {
            if (fix.speedKmh <= threshold - OVERSPEED_HYSTERESIS_KMH) {
                t.overspeed = false
            }
            return
        }
        if (fix.speedKmh > threshold) {
            t.overspeed = true
            if (t.lastOverspeedMs == Long.MIN_VALUE || fix.timeMs - t.lastOverspeedMs >= OVERSPEED_COOLDOWN_MS) {
                t.lastOverspeedMs = fix.timeMs
                events += AlertEvent(AlertType.OVERSPEED, t.camera, d, fix.speedKmh)
            }
        }
    }

    private fun isAhead(pos: LatLng, cam: SpeedCamera, heading: Double, d: Double, cfg: AlertConfig): Boolean =
        d <= CLOSE_M || GeoUtils.angleDiff(GeoUtils.bearing(pos, cam.pos), heading) <= cfg.aheadToleranceDeg

    private fun headingMatches(cam: SpeedCamera, heading: Double, cfg: AlertConfig): Boolean {
        if (cam.headings.isEmpty()) return true
        val tolerance = cam.headingToleranceDeg?.toDouble() ?: cfg.headingToleranceDeg
        return cam.headings.any { GeoUtils.angleDiff(it.toDouble(), heading) <= tolerance }
    }

    private companion object {
        /** Closer than this, bearing-to-camera is too noisy to judge "ahead". */
        const val CLOSE_M = 30.0
        /** Closest approach required before receding counts as having passed the camera. */
        const val PASS_DIST_M = 50.0
        /** Distance must grow this much past the closest point to count as passed. */
        const val PASS_HYSTERESIS_M = 20.0
        /** Consecutive "not ahead" fixes before pausing warnings until back on approach. */
        const val MAX_MISSES = 3
        /** Extra search radius so tracks can finish before being forgotten. */
        const val RELEASE_MARGIN_M = 200.0
        /** Merge nearby same-direction cameras within this distance into one alert. */
        const val CLUSTER_M = 50.0
        /** Speed must drop this much below limit + tolerance before clearing overspeed state. */
        const val OVERSPEED_HYSTERESIS_KMH = 3.0
        /** Minimum interval between overspeed announcements for the same camera track. */
        const val OVERSPEED_COOLDOWN_MS = 10_000L
    }
}
