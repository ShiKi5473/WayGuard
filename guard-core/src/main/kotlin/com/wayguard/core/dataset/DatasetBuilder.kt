package com.wayguard.core.dataset

import com.wayguard.core.model.CameraDataset
import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.DatasetJson
import com.wayguard.core.model.Section
import com.wayguard.core.model.SpeedCamera
import com.wayguard.core.parse.DirectionParser
import com.wayguard.core.parse.NpaRow
import com.wayguard.core.parse.PlaceTarget
import com.wayguard.core.parse.RejectedRow
import com.wayguard.core.validate.CountyBounds
import com.waymap.geo.GeoUtils
import com.waymap.geo.LatLng
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Hand-maintained corrections in data/source/overrides.json, keyed by camera id. */
@Serializable
data class Overrides(
    /** Camera ids to drop (removed, wrong, duplicated under another id…). */
    val exclude: List<String> = emptyList(),
    /** Corrections applied to a camera; also rescues a row rejected for bad coordinates. */
    val fix: Map<String, CameraFix> = emptyMap(),
    /** Extra cameras not in the government data. */
    val add: List<SpeedCamera> = emptyList(),
)

@Serializable
data class CameraFix(
    val lat: Double? = null,
    val lon: Double? = null,
    val limitKmh: Int? = null,
    val headings: List<Int>? = null,
    /** Match tolerance for [headings]; null = the app default (±70°). */
    val headingToleranceDeg: Int? = null,
    /** Why — for humans reading the file. */
    val note: String = "",
)

/** A destination name as it appears in one area. */
data class PlaceRef(val city: String, val district: String, val name: String)

/** A camera whose direction was estimated from the destination its text names. */
data class PlaceDerivation(
    val cameraId: String,
    val directionText: String,
    val place: Place,
    val heading: Int,
    val distanceM: Double,
)

data class BuildReport(
    val sourceRows: Int,
    val rejected: List<RejectedRow>,
    /** Source lines whose latitude/longitude were swapped and corrected. */
    val swappedLines: List<Int>,
    val duplicates: Int,
    /** Direction texts that named no direction → alert both ways. Text → count. */
    val unknownDirections: Map<String, Int>,
    val missingLimit: Int,
    val excluded: Int,
    val fixed: Int,
    val added: Int,
    /** Rows marked 區間測速 — material for curating sections.json by hand. */
    val sectionCandidates: List<Pair<String, NpaRow>>,
    /** Directions estimated from places.json. */
    val placeDerived: List<PlaceDerivation> = emptyList(),
    /** Destination names missing from places.json → positions of the cameras using them. */
    val unmatchedPlaces: Map<PlaceRef, List<LatLng>> = emptyMap(),
    /** Destinations too close to the camera for a meaningful bearing (kept both ways). */
    val placeTooClose: List<PlaceDerivation> = emptyList(),
)

data class BuildResult(val dataset: CameraDataset, val report: BuildReport)

/** Turns parsed NPA rows into the published, validated [CameraDataset]. */
object DatasetBuilder {

    private val LIMIT = Regex("\\d{2,3}")

    /**
     * Tolerance for headings estimated from a destination. Wider than the published
     * ones' ±70° because the road may curve between camera and destination: it only
     * has to rule out travel the opposite way.
     */
    const val PLACE_HEADING_TOLERANCE_DEG = 100

    /** Closer than this, the bearing to the destination says little about the road. */
    const val MIN_PLACE_DISTANCE_M = 150.0

    fun build(
        rows: List<NpaRow>,
        csvRejected: List<RejectedRow>,
        sections: List<Section>,
        overrides: Overrides,
        generatedAt: String,
        source: String,
        places: List<Place> = emptyList(),
    ): BuildResult {
        val rejected = ArrayList(csvRejected)
        val swapped = ArrayList<Int>()
        val unknown = LinkedHashMap<String, Int>()
        val candidates = ArrayList<Pair<String, NpaRow>>()
        val placeDerived = ArrayList<PlaceDerivation>()
        val placeTooClose = ArrayList<PlaceDerivation>()
        val unmatchedPlaces = LinkedHashMap<PlaceRef, MutableList<LatLng>>()
        val directory = PlaceDirectory(places)
        var missingLimit = 0
        var fixed = 0

        val byId = LinkedHashMap<String, SpeedCamera>()
        var duplicates = 0

        for (row in rows) {
            val baseId = stableId(row)
            val parsed = DirectionParser.parse(row.direct)
            if (CameraKind.SECTION in parsed.kinds) candidates += baseId to row

            val city = CountyBounds.normalizeCity(row.city)
            val coords = validCoords(row, city)
            val fix = overrides.fix[baseId]
            // A fix may correct just one coordinate of a rejected row; the other is kept as published.
            val lat = fix?.lat ?: coords?.first ?: row.lat.toDoubleOrNull()?.takeIf { fix != null }
            val lon = fix?.lon ?: coords?.second ?: row.lon.toDoubleOrNull()?.takeIf { fix != null }
            if (lat == null || lon == null) {
                rejected += RejectedRow(row.line, "經緯度不在${city.ifBlank { "臺灣" }}範圍內", rowText(row), baseId)
                continue
            }
            if (coords?.third == true && fix?.lat == null) swapped += row.line
            if (fix != null) fixed++

            val limit = LIMIT.find(row.limit)?.value?.toInt()?.takeIf { it in 10..130 }
            if (limit == null && parsed.limitsByHeading.isEmpty()) missingLimit++

            val district = district(row.region, city)
            var headings = parsed.headings
            var tolerance: Int? = null
            if (fix?.headings != null) {
                headings = fix.headings
                tolerance = fix.headingToleranceDeg
            } else if (!parsed.understood) {
                // 往大溪方向: aim at the named destination when places.json knows it.
                val target = PlaceTarget.of(row.direct)
                val place = target?.let { directory.find(it, city, district) }
                if (target != null && place == null) {
                    unmatchedPlaces.getOrPut(PlaceRef(city, district, target)) { ArrayList() } += LatLng(lat, lon)
                }
                if (place != null) {
                    val pos = LatLng(lat, lon)
                    val d = GeoUtils.haversine(pos, place.pos)
                    val h = (GeoUtils.bearing(pos, place.pos).roundToInt() % 360 + 360) % 360
                    val derivation = PlaceDerivation(baseId, row.direct, place, h, d)
                    if (d >= MIN_PLACE_DISTANCE_M) {
                        headings = listOf(h)
                        tolerance = PLACE_HEADING_TOLERANCE_DEG
                        placeDerived += derivation
                    } else {
                        placeTooClose += derivation
                    }
                }
                if (headings.isEmpty()) unknown.merge(row.direct.ifBlank { "(空白)" }, 1, Int::plus)
            }

            val base = SpeedCamera(
                id = baseId,
                lat = lat,
                lon = lon,
                limitKmh = fix?.limitKmh ?: limit,
                headings = headings,
                headingToleranceDeg = tolerance,
                kinds = parsed.kinds,
                city = city,
                district = district,
                address = row.address,
                directionText = row.direct,
            )
            // 南向60北向70 → one camera per direction, each with its own limit.
            val cameras = if (parsed.limitsByHeading.isEmpty() || fix?.headings != null) listOf(base)
            else parsed.limitsByHeading.map { (h, l) ->
                base.copy(id = "$baseId-h$h", headings = listOf(h), limitKmh = fix?.limitKmh ?: l)
            }
            for (c in cameras) {
                if (byId.putIfAbsent(c.id, c) != null) duplicates++
            }
        }

        // Same spot and same directions under different ids (e.g. two addresses typed
        // for one pole): keep one so the driver isn't warned twice.
        val bySpot = LinkedHashMap<String, SpeedCamera>()
        for (c in byId.values) {
            val key = spotKey(c)
            val prev = bySpot[key]
            if (prev == null) bySpot[key] = c
            else {
                duplicates++
                bySpot[key] = prev.copy(kinds = prev.kinds + c.kinds, limitKmh = prev.limitKmh ?: c.limitKmh)
            }
        }

        val excludedIds = overrides.exclude.toSet()
        val kept = bySpot.values.filter { it.id !in excludedIds }
        val excluded = bySpot.size - kept.size
        val cameras = (kept + overrides.add)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.city }, { it.district }, { it.address }, { it.id }))

        val dataset = CameraDataset(
            version = contentVersion(cameras, sections),
            generatedAt = generatedAt,
            source = source,
            cameras = cameras,
            sections = sections.sortedBy { it.id },
        )
        val report = BuildReport(
            sourceRows = rows.size + csvRejected.size,
            rejected = rejected,
            swappedLines = swapped,
            duplicates = duplicates,
            unknownDirections = unknown,
            missingLimit = missingLimit,
            excluded = excluded,
            fixed = fixed,
            added = overrides.add.size,
            sectionCandidates = candidates,
            placeDerived = placeDerived,
            unmatchedPlaces = unmatchedPlaces,
            placeTooClose = placeTooClose,
        )
        return BuildResult(dataset, report)
    }

    /**
     * Stable across runs as long as the source row doesn't change, so user "false
     * alarm" marks and overrides keep pointing at the same camera.
     */
    fun stableId(row: NpaRow): String {
        val key = listOf(
            CountyBounds.normalizeCity(row.city), row.address.trim(),
            fmt5(row.lat), fmt5(row.lon), row.direct.trim(),
        ).joinToString("|")
        return "npa-" + sha256Hex(key.toByteArray()).take(10)
    }

    /** Content hash of what the app uses; unchanged data → unchanged version → no commit. */
    fun contentVersion(cameras: List<SpeedCamera>, sections: List<Section>): String {
        val text = DatasetJson.encodeToString(ListSerializer(SpeedCamera.serializer()), cameras) +
            DatasetJson.encodeToString(ListSerializer(Section.serializer()), sections)
        return sha256Hex(text.toByteArray()).take(12)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** (lat, lon, swapped) inside the county's box, or null if neither order fits. */
    private fun validCoords(row: NpaRow, city: String): Triple<Double, Double, Boolean>? {
        val lon = row.lon.toDoubleOrNull() ?: return null
        val lat = row.lat.toDoubleOrNull() ?: return null
        val box = CountyBounds.of(city)
        return when {
            box.contains(lat, lon) -> Triple(lat, lon, false)
            box.contains(lon, lat) -> Triple(lon, lat, true)
            else -> null
        }
    }

    /** "福建省金門縣金城鎮" / "臺南市龍崎區" → "金城鎮" / "龍崎區". */
    private fun district(region: String, city: String): String {
        var r = region.trim().removePrefix("福建省")
        for (prefix in listOf(city, city.replace('臺', '台'))) r = r.removePrefix(prefix)
        return r
    }

    private fun spotKey(c: SpeedCamera) =
        "${fmt5(c.lat.toString())}|${fmt5(c.lon.toString())}|${c.headings.sorted()}"

    private fun fmt5(v: String): String =
        v.trim().toDoubleOrNull()?.let { String.format(Locale.ROOT, "%.5f", it) } ?: v.trim()

    private fun rowText(r: NpaRow) =
        listOf(r.city, r.region, r.address, r.dept, r.branch, r.lon, r.lat, r.direct, r.limit).joinToString(",")
}
