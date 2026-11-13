package com.wayguard.core.dataset

import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.Section
import com.wayguard.core.model.SpeedCamera
import com.waymap.geo.GeoUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Builds average-speed sections from the government data alone. The data gives one
 * point per section row, but some sections are published as one row per direction,
 * each at its own start camera with its kilometre range:
 * `臺11線126.13K至129.61` and `臺11線129.61至126.13K`. Two such rows pair up when
 *
 * - both are 區間測速 on the same route in the same city, with the same limit,
 * - their ranges run in opposite directions and each starts where the other ends
 *   (within [KM_TOLERANCE]),
 * - the straight distance between them fits the kilometre length (a road is never
 *   shorter than the straight line, nor much longer on these sections),
 * - and each has exactly one such partner.
 *
 * Every pair gives two sections, start camera → the other one. Anything less certain
 * is left out: the cameras still warn as single points.
 */
object SectionPairing {

    /** A section row's route and kilometre range, as read from its address. */
    data class KmRange(val route: String, val fromKm: Double, val toKm: Double)

    private const val NUM = """(\d+(?:\.\d+)?)"""
    private const val UNIT = """(?:\s*[Kk](?:\s*\+\s*(\d+)\s*[mM])?|\s*公里)?"""
    private val RANGE = Regex("""$NUM$UNIT\s*(?:至|~|～|-|－|到)\s*$NUM$UNIT""")
    /** Leading town / district (`內門區台3線`) is not part of the route. */
    private val ADMIN_PREFIX = Regex("""^\S{1,4}?[鄉鎮市區]""")

    /** Allowed mismatch between one row's end and the other's start, km. */
    const val KM_TOLERANCE = 0.3
    /** Shortest section worth tracking, km. */
    const val MIN_LENGTH_KM = 0.5
    /** Straight distance / kilometre length must lie within this range. */
    const val MIN_CHORD_RATIO = 0.6
    const val MAX_CHORD_RATIO = 1.1
    /** A curated section starting this close (and in this direction) replaces a pair. */
    const val CURATED_COVER_M = 300.0
    const val CURATED_COVER_DEG = 70.0

    fun kmRange(address: String): KmRange? {
        val m = RANGE.find(address) ?: return null
        val text = m.value
        // Both numbers bare (`2-3`) could be anything; a kilometre range names its unit.
        if ('K' !in text && 'k' !in text && "公里" !in text) return null
        val from = km(m.groupValues[1], m.groupValues[2]) ?: return null
        val to = km(m.groupValues[3], m.groupValues[4]) ?: return null
        val route = address.substring(0, m.range.first)
            .replace('臺', '台')
            .replace(Regex("\\s+"), "")
            .replace(ADMIN_PREFIX, "")
        if (route.isEmpty()) return null
        return KmRange(route, from, to)
    }

    private fun km(value: String, metres: String): Double? {
        val v = value.toDoubleOrNull() ?: return null
        return if (metres.isEmpty()) v else v + (metres.toIntOrNull() ?: return null) / 1000.0
    }

    /** Sections from paired [cameras], skipping any start already covered by [curated]. */
    fun pair(cameras: List<SpeedCamera>, curated: List<Section>): List<Section> {
        val rows = cameras
            .filter { CameraKind.SECTION in it.kinds && it.limitKmh != null }
            .mapNotNull { c -> kmRange(c.address)?.takeIf { it.fromKm != it.toKm }?.let { c to it } }

        fun matches(a: Pair<SpeedCamera, KmRange>, b: Pair<SpeedCamera, KmRange>): Boolean {
            val (ca, ra) = a
            val (cb, rb) = b
            if (ca.id == cb.id || ca.city != cb.city || ra.route != rb.route || ca.limitKmh != cb.limitKmh) return false
            if (sign(ra.toKm - ra.fromKm) != -sign(rb.toKm - rb.fromKm)) return false
            if (abs(ra.toKm - rb.fromKm) > KM_TOLERANCE || abs(rb.toKm - ra.fromKm) > KM_TOLERANCE) return false
            val lengthKm = abs(rb.fromKm - ra.fromKm)
            if (lengthKm < MIN_LENGTH_KM) return false
            val chordKm = GeoUtils.haversine(ca.pos, cb.pos) / 1000
            return chordKm >= lengthKm * MIN_CHORD_RATIO && chordKm <= lengthKm * MAX_CHORD_RATIO
        }

        val partner = rows.associateWith { a -> rows.filter { b -> matches(a, b) } }
        val out = ArrayList<Section>()
        for ((a, candidates) in partner) {
            val b = candidates.singleOrNull() ?: continue
            if (partner[b]?.singleOrNull() != a) continue
            val (ca, ra) = a
            val (cb, rb) = b
            val heading = (GeoUtils.bearing(ca.pos, cb.pos).roundToInt() % 360 + 360) % 360
            val covered = curated.any {
                GeoUtils.haversine(it.start, ca.pos) <= CURATED_COVER_M &&
                    GeoUtils.angleDiff(it.heading.toDouble(), heading.toDouble()) <= CURATED_COVER_DEG
            }
            if (covered) continue
            out += Section(
                id = "auto-${ca.id}",
                name = "${ra.route} ${fmt(ra.fromKm)}K–${fmt(rb.fromKm)}K",
                startLat = ca.lat,
                startLon = ca.lon,
                endLat = cb.lat,
                endLon = cb.lon,
                // Start camera to start camera: what the two points actually span.
                lengthM = (abs(rb.fromKm - ra.fromKm) * 1000).roundToInt().toDouble(),
                limitKmh = ca.limitKmh!!,
                // Straight line to the other camera: a winding road may then never match,
                // which only means the section is not tracked.
                heading = heading,
            )
        }
        return out.sortedBy { it.id }
    }

    private fun fmt(km: Double): String =
        String.format(Locale.ROOT, "%.3f", km).trimEnd('0').trimEnd('.')
}
