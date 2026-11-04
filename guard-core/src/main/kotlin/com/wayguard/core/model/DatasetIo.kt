package com.wayguard.core.model

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Reads and writes the published dataset files. */
object DatasetIo {

    private val prettyJson = Json(DatasetJson) { prettyPrint = true }

    /**
     * Valid JSON with one camera/section per line, so the weekly data commits show a
     * readable per-camera diff instead of one changed giant line.
     */
    fun encode(ds: CameraDataset): String = buildString {
        fun str(s: String) = DatasetJson.encodeToString(String.serializer(), s)
        append("{\"schema\":").append(ds.schema)
        append(",\"version\":").append(str(ds.version))
        append(",\"generatedAt\":").append(str(ds.generatedAt))
        append(",\"source\":").append(str(ds.source))
        append(",\n\"sections\":[")
        ds.sections.forEachIndexed { i, s ->
            append(if (i == 0) "\n" else ",\n").append(DatasetJson.encodeToString(Section.serializer(), s))
        }
        append("\n],\n\"cameras\":[")
        ds.cameras.forEachIndexed { i, c ->
            append(if (i == 0) "\n" else ",\n").append(DatasetJson.encodeToString(SpeedCamera.serializer(), c))
        }
        append("\n]}\n")
    }

    fun decode(text: String): CameraDataset = DatasetJson.decodeFromString(CameraDataset.serializer(), text)

    fun encodeMeta(meta: DatasetMeta): String = prettyJson.encodeToString(DatasetMeta.serializer(), meta) + "\n"

    fun decodeMeta(text: String): DatasetMeta = DatasetJson.decodeFromString(DatasetMeta.serializer(), text)
}
