package com.wayguard.sync

import com.wayguard.core.dataset.CameraFix
import com.wayguard.core.dataset.CameraRoad
import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.SpeedCamera
import com.wayguard.core.parse.AddressKind
import com.wayguard.core.parse.AddressParser
import com.wayguard.core.parse.NpaRow
import com.wayguard.core.parse.RejectedRow
import com.wayguard.core.validate.CountyBounds
import com.wayguard.core.validate.LocationCheck
import com.wayguard.core.validate.LocationVerdict
import com.wayguard.core.validate.MilepostIndex
import com.wayguard.core.validate.TgosCsv
import com.wayguard.core.validate.TgosHit
import com.wayguard.core.validate.TgosRow
import com.wayguard.core.validate.VerdictLevel
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-layer location cross-verification for speed cameras:
 * 1. Data-internal conflict check (< 10 m apart with different road names / routes).
 * 2. Local OSM road/intersection/route distance + highway milepost interpolation + OSM admin boundaries.
 * 3. Secondary NLSC API check on local suspect/mismatch points + optional TGOS batch house-number check.
 */
internal class LocationChecker(
    private val osm: OsmExtract?,
    private val nlsc: NlscClient? = null,
    private val offline: Boolean = false,
    private val mileposts: MilepostIndex? = null,
    private val tgosResults: Map<String, TgosHit> = emptyMap(),
    private val tgos: TgosClient? = null,
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
        val activeMileposts = mileposts?.takeUnless { it.isEmpty() } ?: osm?.mileposts?.takeUnless { it.isEmpty() }
        val hasTgosApi = tgos != null && !offline
        val hasExternalSources = (osm != null && !osm.isEmpty) || tgosResults.isNotEmpty() || activeMileposts != null || hasTgosApi
        val internal = LocationVerdict.findInternalConflicts(governmentCameras, knownRoads, now)
        val out = LinkedHashMap<String, LocationCheck>()
        var checkedCount = 0

        for (cam in governmentCameras) {
            val prev = existing[cam.id]?.takeIf { it.matchesPosition(cam.lat, cam.lon) }
            val hasTgos = cam.id in tgosResults
            val needsTgosApi = hasTgosApi && prev?.method != "tgos" &&
                AddressParser.parse(cam.address, cam.city, cam.directionText, knownRoads).kind == AddressKind.HOUSE_NUMBER
            val extCheck = if (
                hasExternalSources &&
                (verifyAll || prev == null || prev.method == "internal" || prev.verdict != VerdictLevel.OK || hasTgos || needsTgosApi)
            ) {
                checkedCount++
                verifyCamera(cam, osm, activeMileposts, prev?.roadAxis, knownRoads, now)
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
            if (hasExternalSources && (verifyAll || prev == null)) {
                suggestRejected(id, row, osm, activeMileposts, knownRoads, now)?.let { out[id] = it }
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

    private fun verifyCamera(
        cam: SpeedCamera,
        osm: OsmExtract?,
        mileposts: MilepostIndex?,
        prevRoadAxis: Int?,
        knownRoads: Set<String>,
        now: String,
    ): LocationCheck {
        val parsed = AddressParser.parse(cam.address, cam.city, cam.directionText, knownRoads)
        val reasons = ArrayList<String>()

        var method = "unverifiable"
        var rawDistM: Double? = null
        var suggested: LatLng? = null

        var intersectionPair = parsed.roads.take(2)
        val roadVerdict: VerdictLevel = when (parsed.kind) {
            AddressKind.INTERSECTION -> {
                method = "intersection"
                if (osm == null || osm.isEmpty) {
                    VerdictLevel.UNVERIFIABLE
                } else {
                    val chosen = pickIntersectionPair(cam.pos, cam.address, cam.city, parsed.roads, osm)
                    intersectionPair = listOf(chosen.first, chosen.second)
                    val dA = chosen.third.first
                    val dB = chosen.third.second
                    val rA = chosen.first
                    val rB = chosen.second
                    when {
                        dA != null && dB != null -> {
                            val minD = min(dA, dB)
                            val maxD = max(dA, dB)
                            val offsetM = parsed.offsetMeters
                            val effectiveMaxD = if (offsetM != null && minD <= LocationVerdict.ROAD_OK_M) {
                                (maxD - offsetM).coerceAtLeast(minD)
                            } else {
                                maxD
                            }
                            rawDistM = maxD
                            val v = LocationVerdict.forIntersection(effectiveMaxD)
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
            }
            AddressKind.HOUSE_NUMBER, AddressKind.ROAD_ONLY -> {
                val roadName = parsed.roads.first()
                val osmDist = osm?.let {
                    it.distanceToRoadInCity(cam.pos, roadName, cam.city)
                        ?: parsed.routeRef?.let { ref -> it.distanceToRoute(cam.pos, ref, isFreeway = false) }
                }
                val osmVerdict = if (osmDist == null) {
                    VerdictLevel.UNVERIFIABLE
                } else {
                    method = "road"
                    rawDistM = osmDist
                    val v = LocationVerdict.forRoad(osmDist)
                    if (v != VerdictLevel.OK) reasons += "距 $roadName ${osmDist.roundToInt()} m"
                    v
                }

                val tgosHit = tgosResults[cam.id] ?: if (!offline && parsed.kind == AddressKind.HOUSE_NUMBER) {
                    val cleaned = TgosCsv.cleanAddressForTgos(cam.city, cam.district, cam.address)
                    tgos?.query(cam.id, cleaned)
                } else {
                    null
                }
                if (tgosHit != null) {
                    // Notice: only conclusion & rounded distance are kept; TGOS coords stay out of public outputs.
                    val tgosDist = GeoUtils.haversine(cam.pos, tgosHit.pos)
                    val tgosVerdict = LocationVerdict.forTgos(tgosDist)
                    method = "tgos"
                    rawDistM = tgosDist
                    if (tgosVerdict != VerdictLevel.OK) {
                        reasons += "距 TGOS 門牌位置 ${tgosDist.roundToInt()} m"
                    }
                    LocationVerdict.combine(osmVerdict, tgosVerdict)
                } else {
                    osmVerdict
                }
            }
            AddressKind.FREEWAY_KM, AddressKind.PROVINCIAL_KM -> {
                method = "route"
                val ref = parsed.routeRef
                val osmDist = if (ref != null && osm != null && !osm.isEmpty) {
                    osm.distanceToRoute(cam.pos, ref, parsed.isFreeway)
                } else {
                    null
                }
                val osmRouteVerdict = if (osmDist == null) {
                    VerdictLevel.UNVERIFIABLE
                } else {
                    rawDistM = osmDist
                    val v = if (parsed.isRampOrSpan) {
                        LocationVerdict.forIntersection(osmDist)
                    } else {
                        LocationVerdict.forRoad(osmDist)
                    }
                    val label = if (parsed.isFreeway) "國道${ref}號" else "${ref}線"
                    if (v != VerdictLevel.OK) reasons += "距 $label ${osmDist.roundToInt()} m"
                    v
                }

                val km = parsed.km
                val mpPos = if (ref != null && km != null) {
                    mileposts?.interpolate(ref, parsed.isFreeway, km)
                } else {
                    null
                }
                if (mpPos != null) {
                    val mpDist = GeoUtils.haversine(cam.pos, mpPos)
                    val mpVerdict = LocationVerdict.forMilepost(mpDist)
                    if (osmDist == null) method = "milepost"
                    if (mpVerdict != VerdictLevel.OK) {
                        reasons += "距里程內插點 ${mpDist.roundToInt()} m"
                        rawDistM = mpDist
                    }
                    LocationVerdict.combine(osmRouteVerdict, mpVerdict)
                } else {
                    osmRouteVerdict
                }
            }
            AddressKind.OTHER -> VerdictLevel.UNVERIFIABLE
        }

        // Layer 2b: Admin district check (local OSM boundary first; NLSC secondary check on flags).
        var adminVerdict = VerdictLevel.UNVERIFIABLE
        var nlscPending = false
        if (osm != null && !osm.isEmpty && !AddressParser.isRouteCity(cam.city) && CountyBounds.isKnown(cam.city)) {
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

        // Reverse-locate unambiguous intersection candidate only when flagged MISMATCH.
        if (osm != null && !osm.isEmpty &&
            parsed.kind == AddressKind.INTERSECTION &&
            combined == VerdictLevel.MISMATCH &&
            intersectionPair.size >= 2
        ) {
            val candidates = osm.findIntersectionCandidates(
                intersectionPair[0], intersectionPair[1], cam.city, cam.district,
            )
            if (candidates.size == 1) {
                val cand = candidates.single()
                val nlscAgrees = if (!offline && nlsc != null && CountyBounds.isKnown(cam.city)) {
                    val nh = nlsc.query(cand.lat, cand.lon)
                    nh == null || LocationVerdict.forAdmin(cam.city, cam.district, nh.city, nh.district) == VerdictLevel.OK
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

        // Compute stabilized roadAxis (0..170 deg) for single-road cameras within 25 m of OSM centerline.
        val camRoad = CameraRoad.of(cam.address, cam.directionText, knownRoads)
        val roadAxis = if (osm != null && !osm.isEmpty && camRoad is CameraRoad.Known) {
            osm.nearestMatchingRoad(cam.pos, camRoad.base, cam.city, LocationVerdict.ROAD_AXIS_MAX_DIST_M)
                ?.let { hit -> LocationVerdict.stabilizeRoadAxis(hit.bearingDeg, prevRoadAxis) }
        } else {
            null
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
            roadAxis = roadAxis,
        )
    }

    private fun pickIntersectionPair(
        pos: LatLng,
        address: String,
        city: String,
        roads: List<String>,
        osm: OsmExtract,
    ): Triple<String, String, Pair<Double?, Double?>> {
        if ("至" in address && roads.size >= 3) {
            val pairs = listOf(roads[0] to roads[1], roads[roads.size - 2] to roads.last())
            var best: Triple<String, String, Pair<Double?, Double?>>? = null
            var bestScore = Double.POSITIVE_INFINITY
            for ((a, b) in pairs) {
                val da = osm.distanceToRoadInCity(pos, a, city)
                val db = osm.distanceToRoadInCity(pos, b, city)
                val score = when {
                    da != null && db != null -> max(da, db)
                    da != null -> da + 10_000.0
                    db != null -> db + 10_000.0
                    else -> Double.POSITIVE_INFINITY
                }
                if (best == null || score < bestScore) {
                    best = Triple(a, b, da to db)
                    bestScore = score
                }
            }
            if (best != null) return best
        }
        val rA = roads[0]
        val rB = roads[1]
        return Triple(rA, rB, osm.distanceToRoadInCity(pos, rA, city) to osm.distanceToRoadInCity(pos, rB, city))
    }

    private fun suggestRejected(
        id: String,
        row: NpaRow,
        osm: OsmExtract?,
        mileposts: MilepostIndex?,
        knownRoads: Set<String>,
        now: String,
    ): LocationCheck? {
        val city = CountyBounds.normalizeCity(row.city)
        val parsed = AddressParser.parse(row.address, city, row.direct, knownRoads)
        val district = row.region.trim().removePrefix("福建省").removePrefix(city).removePrefix(city.replace('臺', '台'))

        if (parsed.kind == AddressKind.INTERSECTION && parsed.roads.size >= 2 && osm != null && !osm.isEmpty) {
            val candidates = osm.findIntersectionCandidates(parsed.roads[0], parsed.roads[1], city, district)
            if (candidates.size == 1) {
                val cand = candidates.single()
                val ok = if (!offline && nlsc != null && CountyBounds.isKnown(city)) {
                    val nh = nlsc.query(cand.lat, cand.lon)
                    nh == null || LocationVerdict.forAdmin(city, district, nh.city, nh.district) == VerdictLevel.OK
                } else {
                    true
                }
                if (ok) {
                    return LocationCheck(
                        verdict = VerdictLevel.MISMATCH,
                        method = "intersection",
                        suggestedLat = round6(cand.lat),
                        suggestedLon = round6(cand.lon),
                        reason = "被拒絕列反推路口座標（${parsed.roads[0]} × ${parsed.roads[1]}）",
                        checkedAt = now,
                    )
                }
            }
        }
        return null
    }

    private fun merge(external: LocationCheck?, internal: LocationCheck?): LocationCheck? {
        if (external == null) return internal
        if (internal == null) return external
        if (external.method == "internal") return internal
        val combined = LocationVerdict.combine(external.verdict, internal.verdict)
        return when {
            internal.verdict.severity > external.verdict.severity ->
                internal.copy(
                    verdict = combined,
                    suggestedLat = internal.suggestedLat ?: external.suggestedLat,
                    suggestedLon = internal.suggestedLon ?: external.suggestedLon,
                    roadAxis = external.roadAxis,
                )
            external.verdict == VerdictLevel.SUSPECT && internal.verdict == VerdictLevel.SUSPECT ->
                external.copy(
                    verdict = combined,
                    reason = listOf(external.reason, internal.reason).filter { it.isNotBlank() }.distinct().joinToString("；"),
                )
            else -> external.copy(verdict = combined)
        }
    }

    companion object {
        /** Exports house-number cameras without an active TGOS check to CSV for batch TGOS upload. */
        fun buildTgosUploadRows(
            cameras: List<SpeedCamera>,
            rejected: List<RejectedRow>,
            rowsById: Map<String, NpaRow>,
            checks: Map<String, LocationCheck>,
            knownRoads: Set<String> = emptySet(),
        ): List<TgosRow> {
            val out = ArrayList<TgosRow>()
            for (cam in cameras) {
                if (CameraKind.MOBILE in cam.kinds || CameraKind.CUSTOM in cam.kinds) continue
                val parsed = AddressParser.parse(cam.address, cam.city, cam.directionText, knownRoads)
                if (parsed.kind != AddressKind.HOUSE_NUMBER) continue
                val prev = checks[cam.id]?.takeIf { it.matchesPosition(cam.lat, cam.lon) }
                if (prev?.method == "tgos") continue
                val cleaned = TgosCsv.cleanAddressForTgos(cam.city, cam.district, cam.address)
                if (cleaned.isNotBlank()) out += TgosRow(cam.id, cleaned)
            }
            for (rej in rejected) {
                val id = rej.id ?: continue
                val row = rowsById[id] ?: continue
                val parsed = AddressParser.parse(row.address, row.city, row.direct, knownRoads)
                if (parsed.kind != AddressKind.HOUSE_NUMBER) continue
                val cleaned = TgosCsv.cleanAddressForTgos(row.city, row.region, row.address)
                if (cleaned.isNotBlank()) out += TgosRow(id, cleaned)
            }
            return out.sortedBy { it.id }
        }

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

