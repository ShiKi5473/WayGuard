package com.wayguard.sync

import com.wayguard.core.dataset.CameraRoad
import com.wayguard.core.model.DatasetJson
import com.wayguard.core.parse.AddressParser
import com.wayguard.core.validate.BBox
import com.wayguard.core.validate.CountyBounds
import com.wayguard.core.validate.LocationVerdict
import com.wayguard.core.validate.MilepostIndex
import com.wayguard.core.validate.MilepostMarker
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Local OpenStreetMap extract loader for roads (`highway=*`) and administrative boundaries
 * (`boundary=administrative`, `admin_level` 4/6/7/8) exported via `osmium export` to
 * GeoJSON or GeoJSONSeq.
 */
internal class OsmExtract private constructor(
    private val roadCells: Map<Long, List<RoadPolyline>>,
    private val roadsByName: Map<String, List<RoadPolyline>>,
    private val cityBoundaries: List<AdminPolygon>,
    private val districtBoundaries: List<AdminPolygon>,
    val mileposts: MilepostIndex = MilepostIndex(emptyList()),
) {
    val knownRoadNames: Set<String> get() = roadsByName.keys
    data class RoadHit(val road: String, val distanceM: Double, val bearingDeg: Int)

    data class AdminHit(
        val city: String?,
        val district: String?,
        val borderDistanceM: Double?,
    )

    private data class RoadPolyline(
        val primaryName: String,
        val baseNames: Set<String>,
        val routeRefs: Set<String>,
        val isMotorway: Boolean,
        val points: List<LatLng>,
        val bbox: BBox,
    )

    private data class AdminPolygon(
        val name: String,
        val bbox: BBox,
        val outer: List<LatLng>,
        val holes: List<List<LatLng>>,
    ) {
        fun contains(p: LatLng): Boolean {
            if (!bbox.contains(p.lat, p.lon)) return false
            if (!pointInRing(p, outer)) return false
            return holes.none { pointInRing(p, it) }
        }

        fun distanceToBorderM(p: LatLng): Double {
            var best = Double.MAX_VALUE
            for (i in 0 until outer.size - 1) {
                val proj = GeoUtils.projectOnSegment(p, outer[i], outer[i + 1])
                val d = GeoUtils.haversine(p, proj)
                if (d < best) best = d
            }
            for (hole in holes) {
                for (i in 0 until hole.size - 1) {
                    val proj = GeoUtils.projectOnSegment(p, hole[i], hole[i + 1])
                    val d = GeoUtils.haversine(p, proj)
                    if (d < best) best = d
                }
            }
            return best
        }
    }

    val isEmpty: Boolean
        get() = roadCells.isEmpty() && cityBoundaries.isEmpty() && districtBoundaries.isEmpty()

    /** Finds the nearest named road within [maxDistM] of [pos] along with its tangent bearing. */
    fun nearestStreet(pos: LatLng, maxDistM: Double = 60.0): RoadHit? {
        val candidates = nearbyRoads(pos, maxDistM + 100.0)
        var bestHit: RoadHit? = null
        var bestDist = maxDistM
        for (poly in candidates) {
            if (poly.primaryName.isBlank()) continue
            val nearest = nearestPointAndBearing(poly.points, pos) ?: continue
            if (nearest.second <= bestDist) {
                bestDist = nearest.second
                val b = ((nearest.third.roundToInt() % 360) + 360) % 360
                bestHit = RoadHit(poly.primaryName, nearest.second, b)
            }
        }
        return bestHit
    }

    /** Shortest distance (m) from [pos] to any road segment matching [baseRoadName] within [searchRadiusM]. */
    fun distanceToRoad(pos: LatLng, baseRoadName: String, searchRadiusM: Double = 500.0): Double? {
        val target = CameraRoad.base(baseRoadName)
        if (target.isBlank()) return null
        val matching = roadsByName[target] ?: return null
        var best: Double? = null
        val dLat = searchRadiusM / METERS_PER_DEG_LAT
        val dLon = searchRadiusM / (METERS_PER_DEG_LAT * cos(Math.toRadians(pos.lat)).coerceAtLeast(0.01))
        for (poly in matching) {
            if (poly.bbox.maxLat < pos.lat - dLat || poly.bbox.minLat > pos.lat + dLat ||
                poly.bbox.maxLon < pos.lon - dLon || poly.bbox.minLon > pos.lon + dLon
            ) continue
            val d = distanceToPolyline(poly.points, pos) ?: continue
            if (best == null || d < best) best = d
        }
        return best
    }

    /** Distance (m) within [city]'s bounds to the nearest segment of [baseRoadName], or null if not in extract. */
    fun distanceToRoadInCity(pos: LatLng, baseRoadName: String, city: String): Double? {
        val target = CameraRoad.base(baseRoadName)
        val matching = roadsByName[target] ?: return null
        val box = CountyBounds.of(city)
        var best: Double? = null
        for (poly in matching) {
            if (!overlaps(poly.bbox, box)) continue
            val d = distanceToPolyline(poly.points, pos) ?: continue
            if (best == null || d < best) best = d
        }
        return best
    }

    /** Shortest distance (m) and bearing to the nearest segment matching [baseRoadName] within [maxDistM]. */
    fun nearestMatchingRoad(
        pos: LatLng,
        baseRoadName: String,
        city: String,
        maxDistM: Double = LocationVerdict.ROAD_AXIS_MAX_DIST_M,
    ): RoadHit? {
        val target = CameraRoad.base(baseRoadName)
        if (target.isBlank()) return null
        val matching = roadsByName[target] ?: return null
        val box = CountyBounds.of(city)
        var bestHit: RoadHit? = null
        var bestDist = maxDistM
        for (poly in matching) {
            if (!overlaps(poly.bbox, box)) continue
            val nearest = nearestPointAndBearing(poly.points, pos) ?: continue
            if (nearest.second <= bestDist) {
                bestDist = nearest.second
                val b = ((nearest.third.roundToInt() % 360) + 360) % 360
                bestHit = RoadHit(poly.primaryName, nearest.second, b)
            }
        }
        return bestHit
    }

    /** True when at least one road segment in OSM carries [baseRoadName] (optionally within [box]). */
    fun hasRoad(baseRoadName: String, box: BBox? = null): Boolean {
        val matching = roadsByName[CameraRoad.base(baseRoadName)] ?: return false
        if (box == null) return matching.isNotEmpty()
        return matching.any { overlaps(it.bbox, box) }
    }

    /** Shortest distance (m) from [pos] to a route matching [routeRef] and [isFreeway] class. */
    fun distanceToRoute(
        pos: LatLng,
        routeRef: String,
        isFreeway: Boolean,
        searchRadiusM: Double = 5_000.0,
    ): Double? {
        val normRef = AddressParser.canonicalRouteRef(routeRef) ?: routeRef
        val nearby = nearbyRoads(pos, searchRadiusM)
        var best: Double? = null
        for (poly in nearby) {
            if (normRef !in poly.routeRefs) continue
            if (isFreeway && !poly.isMotorway) continue
            if (!isFreeway && poly.isMotorway) continue
            val d = distanceToPolyline(poly.points, pos) ?: continue
            if (best == null || d < best) best = d
        }
        return best
    }

    /** Determines the Taiwan city/county and district at [pos], plus distance to the district edge. */
    fun adminAt(pos: LatLng): AdminHit {
        val cityPoly = cityBoundaries.firstOrNull { it.contains(pos) }
        val distPoly = districtBoundaries.firstOrNull { it.contains(pos) }
        val borderDist = (distPoly ?: cityPoly)?.distanceToBorderM(pos)
        return AdminHit(
            city = cityPoly?.name,
            district = distPoly?.name,
            borderDistanceM = borderDist,
        )
    }

    /**
     * Searches county-wide for shared nodes or closest approaches (<= 25 m) between [roadA] and [roadB].
     * Clusters nearby hits within 50 m and disambiguates by [district] when multiple candidates exist.
     */
    fun findIntersectionCandidates(
        roadA: String,
        roadB: String,
        city: String,
        district: String,
    ): List<LatLng> {
        val baseA = CameraRoad.base(roadA)
        val baseB = CameraRoad.base(roadB)
        if (baseA.isBlank() || baseB.isBlank() || baseA == baseB) return emptyList()
        val box = CountyBounds.of(city)
        val listA = roadsByName[baseA].orEmpty().filter { overlaps(it.bbox, box) }
        val listB = roadsByName[baseB].orEmpty().filter { overlaps(it.bbox, box) }
        if (listA.isEmpty() || listB.isEmpty()) return emptyList()

        val rawHits = ArrayList<LatLng>()
        for (a in listA) {
            for (b in listB) {
                if (!overlaps(a.bbox, b.bbox, padDeg = 0.0005)) continue
                for (pt in a.points) {
                    val d = distanceToPolyline(b.points, pt) ?: continue
                    if (d <= INTERSECTION_SNAP_M) {
                        rawHits += pt
                    }
                }
                for (pt in b.points) {
                    val d = distanceToPolyline(a.points, pt) ?: continue
                    if (d <= INTERSECTION_SNAP_M) {
                        rawHits += pt
                    }
                }
                for (i in 0 until a.points.size - 1) {
                    for (j in 0 until b.points.size - 1) {
                        segmentIntersection(a.points[i], a.points[i + 1], b.points[j], b.points[j + 1])?.let {
                            rawHits += it
                        }
                    }
                }
            }
        }
        val clustered = clusterPoints(rawHits, CLUSTER_RADIUS_M)
        if (clustered.isEmpty() || district.isBlank() || districtBoundaries.isEmpty()) return clustered

        val normCity = CountyBounds.normalizeCity(city)
        val normDist = CountyBounds.normalizeCity(district)
        val targetDistPolys = districtBoundaries.filter { CountyBounds.normalizeCity(it.name) == normDist }
        if (targetDistPolys.isEmpty()) return clustered

        return clustered.filter { pt ->
            val hit = adminAt(pt)
            val cityOk = normCity.isEmpty() || hit.city == null || CountyBounds.normalizeCity(hit.city) == normCity
            if (!cityOk) return@filter false
            val distOk = hit.district != null && CountyBounds.normalizeCity(hit.district) == normDist
            distOk || targetDistPolys.any { it.distanceToBorderM(pt) <= LocationVerdict.ADMIN_BORDER_TOLERANCE_M }
        }
    }

    private fun nearbyRoads(center: LatLng, radiusM: Double): Set<RoadPolyline> {
        val dLat = radiusM / METERS_PER_DEG_LAT
        val dLon = radiusM / (METERS_PER_DEG_LAT * cos(Math.toRadians(center.lat)).coerceAtLeast(0.01))
        val out = LinkedHashSet<RoadPolyline>()
        for (la in cellOf(center.lat - dLat)..cellOf(center.lat + dLat)) {
            for (lo in cellOf(center.lon - dLon)..cellOf(center.lon + dLon)) {
                roadCells[cellKey(la, lo)]?.let { out.addAll(it) }
            }
        }
        return out
    }

    private fun clusterPoints(pts: List<LatLng>, radiusM: Double): List<LatLng> {
        if (pts.isEmpty()) return emptyList()
        val groups = ArrayList<MutableList<LatLng>>()
        for (p in pts) {
            val g = groups.firstOrNull { GeoUtils.haversine(it.first(), p) <= radiusM }
            if (g != null) g += p else groups += mutableListOf(p)
        }
        return groups.map { g -> LatLng(g.map { it.lat }.average(), g.map { it.lon }.average()) }
    }

    companion object {
        private val DISTRICT_CITY_PREFIX = Regex("^[\\u4e00-\\u9fff]{2,3}[縣市](?=.+[區鄉鎮市]$)")
        private const val CELL_DEG = 0.01
        private const val METERS_PER_DEG_LAT = 111_320.0
        private const val INTERSECTION_SNAP_M = 25.0
        private const val CLUSTER_RADIUS_M = 100.0

        private fun cellOf(deg: Double): Int = floor(deg / CELL_DEG).toInt()
        private fun cellKey(la: Int, lo: Int): Long = (la.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)

        fun load(file: File): OsmExtract {
            if (!file.isFile) return fromFeatures(emptySequence())
            return file.bufferedReader(Charsets.UTF_8).use { reader ->
                fromTextSequence(reader.lineSequence())
            }
        }

        internal fun fromText(content: String): OsmExtract = fromTextSequence(content.lineSequence())

        private fun fromTextSequence(lines: Sequence<String>): OsmExtract {
            val iter = lines.map { it.trimStart('\u001e', ' ', '\t') }.filter { it.isNotEmpty() }.iterator()
            if (!iter.hasNext()) return fromFeatures(emptySequence())
            val first = iter.next()
            return if (first.contains("\"FeatureCollection\"")) {
                val whole = buildString {
                    appendLine(first)
                    while (iter.hasNext()) appendLine(iter.next())
                }
                val root = DatasetJson.parseToJsonElement(whole).jsonObject
                val arr = root["features"]?.jsonArray ?: return fromFeatures(emptySequence())
                fromFeatures(arr.asSequence().mapNotNull { it as? JsonObject })
            } else {
                val seq = sequence {
                    parseFeature(first)?.let { yield(it) }
                    while (iter.hasNext()) parseFeature(iter.next())?.let { yield(it) }
                }
                fromFeatures(seq)
            }
        }

        private fun parseFeature(line: String): JsonObject? {
            if (!line.startsWith("{")) return null
            return runCatching { DatasetJson.parseToJsonElement(line).jsonObject }.getOrNull()
        }

        private fun fromFeatures(features: Sequence<JsonObject>): OsmExtract {
            val roadCells = HashMap<Long, MutableList<RoadPolyline>>()
            val roadsByName = HashMap<String, MutableList<RoadPolyline>>()
            val cityBoundaries = ArrayList<AdminPolygon>()
            val districtBoundaries = ArrayList<AdminPolygon>()
            val milestoneMarkers = ArrayList<MilepostMarker>()

            for (f in features) {
                val props = f["properties"] as? JsonObject ?: continue
                val geom = f["geometry"] as? JsonObject ?: continue
                val geomType = geom["type"]?.jsonPrimitive?.contentOrNull ?: continue
                val coords = geom["coordinates"] as? JsonArray ?: continue

                val highway = props["highway"]?.jsonPrimitive?.contentOrNull
                val route = props["route"]?.jsonPrimitive?.contentOrNull
                if (highway == "milestone" && geomType == "Point") {
                    val lon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                    val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                    val rawRef = props["ref"]?.jsonPrimitive?.contentOrNull
                    val rawDist = props["distance"]?.jsonPrimitive?.contentOrNull
                    if (lon != null && lat != null && rawRef != null && rawDist != null) {
                        val ref = AddressParser.canonicalRouteRef(rawRef)
                        val km = MilepostIndex.parseKmToken(rawDist)
                        if (ref != null && km != null) {
                            val network = props["network"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val isFreeway = network == "TW:freeway" || "國道" in rawRef
                            milestoneMarkers += MilepostMarker(ref, isFreeway, km, LatLng(lat, lon))
                        }
                    }
                    continue
                }
                if (highway != null || route == "road") {
                    val primaryRaw = props["name"]?.jsonPrimitive?.contentOrNull
                        ?: props["name:zh"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    val rawNames = listOfNotNull(
                        props["name"]?.jsonPrimitive?.contentOrNull,
                        props["name:zh"]?.jsonPrimitive?.contentOrNull,
                        props["alt_name"]?.jsonPrimitive?.contentOrNull,
                    ).flatMap { it.split(';', '、') }
                    val baseNames = rawNames.map(CameraRoad::base).filter { it.isNotBlank() }.toSet()
                    val routeRefs = props["ref"]?.jsonPrimitive?.contentOrNull
                        ?.split(';', '/', ',')
                        ?.mapNotNull { AddressParser.canonicalRouteRef(it.trim()) }
                        ?.toSet()
                        ?: emptySet()
                    if (baseNames.isEmpty() && routeRefs.isEmpty()) continue
                    val network = props["network"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val isMotorway = highway == "motorway" || highway == "motorway_link" ||
                        network == "TW:freeway" ||
                        rawNames.any { "國道" in it || "高速公路" in it }
                    val primaryName = CameraRoad.base(primaryRaw).ifBlank { baseNames.firstOrNull().orEmpty() }

                    val lineParts = when (geomType) {
                        "LineString" -> listOf(toLatLngList(coords))
                        "MultiLineString" -> coords.mapNotNull { (it as? JsonArray)?.let(::toLatLngList) }
                        else -> emptyList()
                    }
                    for (pts in lineParts) {
                        if (pts.size < 2) continue
                        val poly = RoadPolyline(primaryName, baseNames, routeRefs, isMotorway, pts, bboxOf(pts))
                        for (name in baseNames) {
                            roadsByName.getOrPut(name) { ArrayList() } += poly
                        }
                        val seenCells = HashSet<Long>()
                        for (p in pts) {
                            val key = cellKey(cellOf(p.lat), cellOf(p.lon))
                            if (seenCells.add(key)) {
                                roadCells.getOrPut(key) { ArrayList() } += poly
                            }
                        }
                    }
                    continue
                }

                val boundary = props["boundary"]?.jsonPrimitive?.contentOrNull
                val adminLevel = props["admin_level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (boundary == "administrative" && adminLevel != null) {
                    val rawName = props["name"]?.jsonPrimitive?.contentOrNull
                        ?: props["name:zh"]?.jsonPrimitive?.contentOrNull
                        ?: continue
                    val normName = CountyBounds.normalizeCity(rawName)
                    if (normName.isBlank()) continue
                    val districtName = normName.replace(DISTRICT_CITY_PREFIX, "")
                    val isDistrict = adminLevel in setOf(7, 8) && districtName.isNotEmpty() && districtName.last() in "區鄉鎮市"
                    val targetList = when {
                        adminLevel in setOf(4, 5, 6) && CountyBounds.isKnown(normName) -> cityBoundaries
                        isDistrict -> districtBoundaries
                        else -> null
                    } ?: continue
                    val storedName = if (targetList === districtBoundaries) districtName else normName

                    val ringsList = when (geomType) {
                        "Polygon" -> listOf(coords)
                        "MultiPolygon" -> coords.mapNotNull { it as? JsonArray }
                        else -> emptyList()
                    }
                    for (polyArr in ringsList) {
                        val rings = polyArr.mapNotNull { (it as? JsonArray)?.let(::toLatLngList) }.filter { it.size >= 3 }
                        if (rings.isEmpty()) continue
                        val outer = rings.first()
                        targetList += AdminPolygon(
                            name = storedName,
                            bbox = bboxOf(outer),
                            outer = outer,
                            holes = rings.drop(1),
                        )
                    }
                }
            }

            return OsmExtract(
                roadCells,
                roadsByName,
                cityBoundaries,
                districtBoundaries,
                MilepostIndex(milestoneMarkers),
            )
        }

        private fun toLatLngList(arr: JsonArray): List<LatLng> =
            arr.mapNotNull { el ->
                val pair = el as? JsonArray ?: return@mapNotNull null
                val lon = pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val lat = pair.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                LatLng(lat, lon)
            }

        private fun bboxOf(pts: List<LatLng>): BBox {
            var minLat = Double.POSITIVE_INFINITY
            var maxLat = Double.NEGATIVE_INFINITY
            var minLon = Double.POSITIVE_INFINITY
            var maxLon = Double.NEGATIVE_INFINITY
            for (p in pts) {
                minLat = min(minLat, p.lat)
                maxLat = max(maxLat, p.lat)
                minLon = min(minLon, p.lon)
                maxLon = max(maxLon, p.lon)
            }
            return BBox(minLat, maxLat, minLon, maxLon)
        }

        private fun overlaps(a: BBox, b: BBox, padDeg: Double = 0.0): Boolean =
            a.minLat <= b.maxLat + padDeg && a.maxLat >= b.minLat - padDeg &&
                a.minLon <= b.maxLon + padDeg && a.maxLon >= b.minLon - padDeg

        internal fun nearestPointAndBearing(pts: List<LatLng>, p: LatLng): Triple<LatLng, Double, Double>? {
            if (pts.size < 2) return null
            var best: Triple<LatLng, Double, Double>? = null
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                if (GeoUtils.haversine(a, b) <= 0.5) continue
                val proj = GeoUtils.projectOnSegment(p, a, b)
                val dist = GeoUtils.haversine(p, proj)
                if (best == null || dist < best.second) {
                    best = Triple(proj, dist, GeoUtils.bearing(a, b))
                }
            }
            return best
        }

        internal fun distanceToPolyline(pts: List<LatLng>, p: LatLng): Double? {
            if (pts.size < 2) return pts.singleOrNull()?.let { GeoUtils.haversine(p, it) }
            var best = Double.MAX_VALUE
            for (i in 0 until pts.size - 1) {
                val proj = GeoUtils.projectOnSegment(p, pts[i], pts[i + 1])
                val d = GeoUtils.haversine(p, proj)
                if (d < best) best = d
            }
            return if (best == Double.MAX_VALUE) null else best
        }

        /** 2D intersection point between segments ([a1], [a2]) and ([b1], [b2]), or null if disjoint. */
        internal fun segmentIntersection(a1: LatLng, a2: LatLng, b1: LatLng, b2: LatLng): LatLng? {
            val dx1 = a2.lon - a1.lon
            val dy1 = a2.lat - a1.lat
            val dx2 = b2.lon - b1.lon
            val dy2 = b2.lat - b1.lat
            val denom = dx1 * dy2 - dy1 * dx2
            if (kotlin.math.abs(denom) < 1e-12) return null
            val rx = b1.lon - a1.lon
            val ry = b1.lat - a1.lat
            val t = (rx * dy2 - ry * dx2) / denom
            val u = (rx * dy1 - ry * dx1) / denom
            if (t !in 0.0..1.0 || u !in 0.0..1.0) return null
            return LatLng(a1.lat + t * dy1, a1.lon + t * dx1)
        }

        /** Standard ray-casting point-in-polygon check. */
        internal fun pointInRing(p: LatLng, ring: List<LatLng>): Boolean {
            if (ring.size < 3) return false
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val yi = ring[i].lat
                val xi = ring[i].lon
                val yj = ring[j].lat
                val xj = ring[j].lon
                val intersect = ((yi > p.lat) != (yj > p.lat)) &&
                    (p.lon < (xj - xi) * (p.lat - yi) / ((yj - yi).let { if (it == 0.0) 1e-12 else it }) + xi)
                if (intersect) inside = !inside
                j = i
            }
            return inside
        }
    }
}
