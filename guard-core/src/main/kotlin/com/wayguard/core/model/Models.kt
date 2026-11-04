package com.wayguard.core.model

import com.waymap.geo.LatLng
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Current published dataset schema; the app ignores datasets with a different one. */
const val DATASET_SCHEMA = 1

@Serializable
enum class CameraKind {
    /** Fixed speed camera (the government dataset). */
    SPEED,
    /** Also enforces red lights. */
    RED_LIGHT,
    /** Part of an average-speed (區間測速) section. */
    SECTION,
    /** Mobile / temporary enforcement point (user-added). */
    MOBILE,
    /** Any other user-added point. */
    CUSTOM,
}

/**
 * One enforcement point. [headings] are the travel bearings (degrees, 0 = north)
 * the camera enforces; empty means every direction (bidirectional or unknown).
 */
@Serializable
data class SpeedCamera(
    val id: String,
    val lat: Double,
    val lon: Double,
    val limitKmh: Int? = null,
    val headings: List<Int> = emptyList(),
    val kinds: Set<CameraKind> = setOf(CameraKind.SPEED),
    val city: String = "",
    val district: String = "",
    val address: String = "",
    /** Direction text as published, kept for display. */
    val directionText: String = "",
) {
    val pos: LatLng get() = LatLng(lat, lon)
}

/**
 * An average-speed enforcement section, driven from [start] to [end]. Each driving
 * direction is its own entry. Curated by hand in data/source/sections.json.
 */
@Serializable
data class Section(
    val id: String,
    val name: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    /** Road length between the two cameras (what enforcement divides by). */
    val lengthM: Double,
    val limitKmh: Int,
    /** Travel bearing when passing the start camera; null = don't check. */
    val heading: Int? = null,
) {
    val start: LatLng get() = LatLng(startLat, startLon)
    val end: LatLng get() = LatLng(endLat, endLon)
}

/** The published file `cameras.json`. */
@Serializable
data class CameraDataset(
    val schema: Int = DATASET_SCHEMA,
    /** Content hash of [cameras] + [sections]; equal versions mean identical data. */
    val version: String,
    /** ISO-8601 UTC time the dataset was generated. */
    val generatedAt: String,
    val source: String = "",
    val cameras: List<SpeedCamera>,
    val sections: List<Section> = emptyList(),
)

/** The published file `meta.json`, fetched first to decide whether to download. */
@Serializable
data class DatasetMeta(
    val schema: Int = DATASET_SCHEMA,
    val version: String,
    val generatedAt: String,
    val count: Int,
    val sectionCount: Int,
    /** Lower-case hex SHA-256 of the exact bytes of [file]. */
    val sha256: String,
    val file: String = "cameras.json",
)

/** Shared JSON settings for the dataset files (writer and app reader). */
val DatasetJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}
