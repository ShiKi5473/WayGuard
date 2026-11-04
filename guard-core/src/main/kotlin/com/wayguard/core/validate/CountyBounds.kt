package com.wayguard.core.validate

/** Axis-aligned bounding box in degrees. */
data class BBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
}

/**
 * Approximate bounding boxes of Taiwan's counties/cities (incl. offshore islands
 * of the same county), padded by [MARGIN]. Coarse on purpose: the goal is to catch
 * typos such as a latitude off by 2°, not to geocode.
 */
object CountyBounds {

    private const val MARGIN = 0.05

    /** Whole of Taiwan, Penghu, Kinmen and Matsu — used when the county is unknown. */
    val TAIWAN = BBox(21.8, 26.5, 118.0, 122.1)

    private val BOXES: Map<String, BBox> = mapOf(
        "臺北市" to box(24.96, 25.21, 121.45, 121.67),
        "新北市" to box(24.67, 25.30, 121.28, 122.01),
        "基隆市" to box(25.05, 25.20, 121.62, 121.81),
        "桃園市" to box(24.59, 25.13, 120.98, 121.48),
        "新竹市" to box(24.72, 24.86, 120.88, 121.03),
        "新竹縣" to box(24.43, 24.95, 120.95, 121.42),
        "苗栗縣" to box(24.27, 24.75, 120.62, 121.27),
        "臺中市" to box(23.99, 24.45, 120.46, 121.46),
        "彰化縣" to box(23.78, 24.21, 120.24, 120.70),
        "南投縣" to box(23.44, 24.25, 120.61, 121.35),
        "雲林縣" to box(23.50, 23.84, 120.13, 120.75),
        "嘉義市" to box(23.44, 23.52, 120.38, 120.50),
        "嘉義縣" to box(23.20, 23.64, 120.08, 120.95),
        "臺南市" to box(22.88, 23.42, 120.03, 120.66),
        "高雄市" to box(22.47, 23.48, 120.17, 121.05),
        "屏東縣" to box(21.89, 22.88, 120.32, 120.91),
        "宜蘭縣" to box(24.31, 24.99, 121.30, 121.98),
        // East edge reaches ~121.76 at 和平 (秀林鄉, 蘇花改).
        "花蓮縣" to box(23.09, 24.37, 121.06, 121.77),
        "臺東縣" to box(21.94, 23.45, 120.73, 121.62),
        "澎湖縣" to box(23.18, 23.79, 119.31, 119.73),
        "金門縣" to box(24.37, 24.53, 118.20, 118.50),
        "連江縣" to box(25.94, 26.39, 119.90, 120.52),
    )

    private fun box(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) =
        BBox(minLat - MARGIN, maxLat + MARGIN, minLon - MARGIN, maxLon + MARGIN)

    /** `台` → `臺` and trimmed, matching the official county names. */
    fun normalizeCity(city: String): String = city.trim().replace('台', '臺')

    /** The county's box, or [TAIWAN] when the name isn't recognised. */
    fun of(city: String): BBox = BOXES[normalizeCity(city)] ?: TAIWAN

    fun isKnown(city: String): Boolean = normalizeCity(city) in BOXES
}
