package com.wayguard.sync

import com.wayguard.core.dataset.CameraFix
import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.SpeedCamera
import com.wayguard.core.parse.AddressKind
import com.wayguard.core.parse.AddressParser
import com.wayguard.core.parse.NpaRow
import com.wayguard.core.parse.RejectedRow
import com.wayguard.core.validate.CountyBounds
import com.wayguard.core.validate.LocationCheck
import com.wayguard.core.validate.LocationVerdict
import com.wayguard.core.validate.VerdictLevel
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Three-layer location cross-verification for speed cameras:
 * 1. Data-internal conflict check (< 10 m apart with different road names / routes).
 * 2. Local OSM road/intersection/route distance + local OSM administrative boundaries.
 * 3. Secondary NLSC API check on local suspect/mismatch points and suggested coordinates.
 */
internal class LocationChecker(
    private val osm: OsmExtract?,
    private val nlsc: NlscClient? = null,
    private val offline: Boolean = false,
) {
    data class RunResult(
        val checks: Map<String, LocationCheck>,
        val checkedCount: Int,
        val changed: Boolean,
    )

    fun checkAll(
        cameras: List<SpeedCamera>,
        rejected: List<RejectedRow>,
        rowsById: Map<String, NpaRow>,
        existing: Map<String, LocationCheck>,
        now: String,
        verifyAll: Boolean = false,
        knownRoads: Set<String> = emptySet(),
    ): RunResult {
        val governmentCameras = cameras.filter {
            CameraKind.MOBILE !in it.kinds && CameraKind.CUSTOM !in it.kinds
        }
        val internal = LocationVerdict.findInternalConflicts(governmentCameras, knownRoads, now)
        val out = LinkedHashMap<String, LocationCheck>()
        var checkedCount = 0

        for (cam in governmentCameras) {
            val prev = existing[cam.id]?.takeIf { it.matchesPosition(cam.lat, cam.lon) }
            val extCheck = if (osm != null && !osm.isEmpty && (verifyAll || prev == null || prev.method == "internal")) {
                checkedCount++
                verifyWithOsm(cam, osm, knownRoads, now)
            } else {
                prev
            }
            val intCheck = internal[cam.id]
            val merged = merge(extCheck, intCheck)
            if (merged != null) {
                out[cam.id] = merged
            }
        }

        // Also compute suggested coordinates for rejected out-of-county rows.
        for (rej in rejected) {
            val id = rej.id ?: continue
            val row = rowsById[id] ?: continue
            val prev = existing[id]
            if (osm != null && !osm.isEmpty && (verifyAll || prev == null)) {
                suggestRejected(id, row, osm, now)?.let { out[id] = it }
            } else if (prev != null) {
                out[id] = prev
            }
        }

        val sortedOut = out.toSortedMap()
        val sortedExisting = existing.toSortedMap()
        val changed = sortedOut.keys != sortedExisting.keys || sortedOut.any { (k, v) ->
            val old = sortedExisting[k]
            old == null || !v.sameConclusion(old)
        }
        return RunResult(sortedOut, checkedCount, changed)
    }

    private fun verifyWithOsm(
        cam: SpeedCamera,
        osm: OsmExtract,
        knownRoads: Set<String>,
        now: String,
    ): LocationCheck {
        val parsed = AddressParser.parse(cam.address, cam.city, cam.directionText, knownRoads)
        val reasons = ArrayList<String>()

        var method = "unverifiable"
        var rawDistM: Double? = null
        var suggested: LatLng? = null

        val roadVerdict: VerdictLevel = when (parsed.kind) {
            AddressKind.INTERSECTION -> {
                method = "intersection"
                val rA = parsed.roads[0]
                val rB = parsed.roads[1]
                val dA = osm.distanceToRoadInCity(cam.pos, rA, cam.city)
                val dB = osm.distanceToRoadInCity(cam.pos, rB, cam.city)
                when {
                    dA != null && dB != null -> {
                        val maxD = max(dA, dB)
                        rawDistM = maxD
                        val v = LocationVerdict.forIntersection(maxD)
                        if (v != VerdictLevel.OK) {
                            reasons += "距路口道路最遠 ${maxD.roundToInt()} m（$rA ${dA.roundToInt()} m、$rB ${dB.roundToInt()} m）"
                        }
                        v
                    }
                    dA != null || dB != null -> {
                        val foundName = if (dA != null) rA else rB
                        val foundD = dA ?: dB!!
                        rawDistM = foundD
                        when {
                            foundD > LocationVerdict.INTERSECTION_SUSPECT_M -> {
                                reasons += "距 $foundName ${foundD.roundToInt()} m"
                                VerdictLevel.MISMATCH
                            }
                            foundD > LocationVerdict.ROAD_SUSPECT_M -> {
                                reasons += "距 $foundName ${foundD.roundToInt()} m"
                                VerdictLevel.SUSPECT
                            }
                            else -> VerdictLevel.UNVERIFIABLE
                        }
                    }
                    else -> VerdictLevel.UNVERIFIABLE
                }
            }
            AddressKind.HOUSE_NUMBER, AddressKind.ROAD_ONLY -> {
                method = "road"
                val roadName = parsed.roads.first()
                val d = osm.distanceToRoadInCity(cam.pos, roadName, cam.city)
                    ?: parsed.routeRef?.let { osm.distanceToRoute(cam.pos, it, isFreeway = false) }
                if (d == null) {
                    VerdictLevel.UNVERIFIABLE
                } else {
                    rawDistM = d
                    val v = LocationVerdict.forRoad(d)
                    if (v != VerdictLevel.OK) reasons += "距 $roadName ${d.roundToInt()} m"
                    v
                }
            }
            AddressKind.FREEWAY_KM, AddressKind.PROVINCIAL_KM -> {
                method = "route"
                val ref = parsed.routeRef
                val d = if (ref != null) osm.distanceToRoute(cam.pos, ref, parsed.isFreeway) else null
                if (d == null) {
                    VerdictLevel.UNVERIFIABLE
                } else {
                    rawDistM = d
                    val v = LocationVerdict.forRoad(d)
                    val label = if (parsed.isFreeway) "國道${ref}號" else "${ref}線"
                    if (v != VerdictLevel.OK) reasons += "距 $label ${d.roundToInt()} m"
                    v
                }
            }
            AddressKind.OTHER -> VerdictLevel.UNVERIFIABLE
        }

        // Layer 2b: Admin district check (local OSM boundary first; NLSC secondary check on flags).
        var adminVerdict = VerdictLevel.UNVERIFIABLE
        var nlscPending = false
        if (!AddressParser.isRouteCity(cam.city) && CountyBounds.isKnown(cam.city)) {
            val hit = osm.adminAt(cam.pos)
            adminVerdict = LocationVerdict.forAdmin(
                expectedCity = cam.city,
                expectedDistrict = cam.district,
                actualCity = hit.city,
                actualDistrict = hit.district,
                borderDistanceM = hit.borderDistanceM,
            )
            if (adminVerdict == VerdictLevel.SUSPECT || adminVerdict == VerdictLevel.MISMATCH) {
                var actualLabel = listOfNotNull(hit.city, hit.district).joinToString("")
                if (!offline && nlsc != null) {
                    val nlscHit = nlsc.query(cam.lat, cam.lon)
                    if (nlscHit != null) {
                        adminVerdict = LocationVerdict.forAdmin(
                            expectedCity = cam.city,
                            expectedDistrict = cam.district,
                            actualCity = nlscHit.city,
                            actualDistrict = nlscHit.district,
                            borderDistanceM = null,
                        )
                        actualLabel = "${nlscHit.city}${nlscHit.district}（國土測繪複查）"
                    } else {
                        nlscPending = true
                    }
                } else {
                    nlscPending = true
                }
                if (adminVerdict != VerdictLevel.OK) {
                    val expLabel = "${CountyBounds.normalizeCity(cam.city)}${CountyBounds.normalizeCity(cam.district)}"
                    val pendingNote = if (nlscPending) "（待複查）" else ""
                    reasons += "座標落於 $actualLabel，非 $expLabel$pendingNote"
                    if (roadVerdict == VerdictLevel.OK || roadVerdict == VerdictLevel.UNVERIFIABLE) {
                        method = "admin"
                    }
                }
            }
        }

        val combined = LocationVerdict.combine(roadVerdict, adminVerdict)

        // Reverse-locate unambiguous intersection candidate only when flagged.
        if (parsed.kind == AddressKind.INTERSECTION &&
            (combined == VerdictLevel.SUSPECT || combined == VerdictLevel.MISMATCH) &&
            parsed.roads.size >= 2
        ) {
            val candidates = osm.findIntersectionCandidates(parsed.roads[0], parsed.roads[1], cam.city, cam.district)
            if (candidates.size == 1) {
                val cand = candidates.single()
                val nlscAgrees = if (!offline && nlsc != null && CountyBounds.isKnown(cam.city)) {
                    val nh = nlsc.query(cand.lat, cand.lon)
                    nh == null || LocationVerdict.forAdmin(cam.city, cam.district, nh.city, nh.district) != VerdictLevel.MISMATCH
                } else {
                    true
                }
                if (nlscAgrees) {
                    suggested = cand
                    val jumpM = GeoUtils.haversine(cam.pos, cand)
                    rawDistM = jumpM
                    reasons += "推算路口相距 ${jumpM.roundToInt()} m"
                }
            }
        }

        return LocationCheck(
            verdict = combined,
            method = method,
            distanceM = rawDistM?.let(LocationVerdict::roundDistance10m),
            suggestedLat = suggested?.let { round6(it.lat) },
            suggestedLon = suggested?.let { round6(it.lon) },
            reason = reasons.joinToString("；"),
            checkedAt = now,
            lat = cam.lat,
            lon = cam.lon,
        )
    }

    private fun suggestRejected(
        id: String,
        row: NpaRow,
        osm: OsmExtract,
        now: String,
    ): LocationCheck? {
        val city = CountyBounds.normalizeCity(row.city)
        val parsed = AddressParser.parse(row.address, city, row.direct)
        if (parsed.kind != AddressKind.INTERSECTION || parsed.roads.size < 2) return null
        val district = row.region.trim().removePrefix("福建省").removePrefix(city).removePrefix(city.replace('臺', '台'))
        val candidates = osm.findIntersectionCandidates(parsed.roads[0], parsed.roads[1], city, district)
        if (candidates.size != 1) return null
        val cand = candidates.single()
        if (!offline && nlsc != null && CountyBounds.isKnown(city)) {
            val nh = nlsc.query(cand.lat, cand.lon)
            if (nh != null && LocationVerdict.forAdmin(city, district, nh.city, nh.district) == VerdictLevel.MISMATCH) {
                return null
            }
        }
        return LocationCheck(
            verdict = VerdictLevel.MISMATCH,
            method = "intersection",
            suggestedLat = round6(cand.lat),
            suggestedLon = round6(cand.lon),
            reason = "被拒絕列反推路口座標（${parsed.roads[0]} × ${parsed.roads[1]}）",
            checkedAt = now,
        )
    }

    private fun merge(external: LocationCheck?, internal: LocationCheck?): LocationCheck? {
        if (external == null) return internal
        if (internal == null) return external
        val combined = LocationVerdict.combine(external.verdict, internal.verdict)
        return when {
            internal.verdict.severity > external.verdict.severity ->
                internal.copy(
                    verdict = combined,
                    suggestedLat = internal.suggestedLat ?: external.suggestedLat,
                    suggestedLon = internal.suggestedLon ?: external.suggestedLon,
                )
            external.verdict == VerdictLevel.SUSPECT && internal.verdict == VerdictLevel.SUSPECT ->
                external.copy(
                    verdict = combined,
                    reason = listOf(external.reason, internal.reason).filter { it.isNotBlank() }.joinToString("；"),
                )
            else -> external.copy(verdict = combined)
        }
    }

    companion object {
        /** Builds an `overrides.json`-compatible `fix` map for MISMATCH and rejected points that have a unique suggestion. */
        fun buildFixSuggestions(
            issues: List<Pair<SpeedCamera, LocationCheck>>,
            rejectedSuggestions: Map<String, LocationCheck>,
        ): Map<String, CameraFix> {
            val out = LinkedHashMap<String, CameraFix>()
            for ((cam, check) in issues) {
                if (check.verdict != VerdictLevel.MISMATCH) continue
                val lat = check.suggestedLat ?: continue
                val lon = check.suggestedLon ?: continue
                out[cam.id] = CameraFix(
                    lat = lat,
                    lon = lon,
                    note = "OSM 位置交叉驗證建議：${cam.city}${cam.district} ${cam.address}（${check.reason}）",
                )
            }
            for ((id, check) in rejectedSuggestions) {
                val lat = check.suggestedLat ?: continue
                val lon = check.suggestedLon ?: continue
                out[id] = CameraFix(
                    lat = lat,
                    lon = lon,
                    note = check.reason,
                )
            }
            return out.toSortedMap()
        }

        private fun round6(v: Double) = (v * 1e6).roundToInt() / 1e6
    }
}

