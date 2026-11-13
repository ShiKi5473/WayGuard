package com.wayguard.core.engine

import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.SpeedCamera
import java.util.Locale
import kotlin.math.roundToInt

/** Spoken / displayed Chinese (Taiwan) texts for alerts. */
object AlertText {

    fun kindName(c: SpeedCamera): String = when {
        CameraKind.MOBILE in c.kinds -> "流動測速"
        CameraKind.CUSTOM in c.kinds -> "自訂提醒點"
        CameraKind.SECTION in c.kinds -> "區間測速"
        CameraKind.RED_LIGHT in c.kinds -> "測速兼闖紅燈照相"
        else -> "測速照相"
    }

    /** Distance rounded to the nearest 50 m — what a driver can act on. */
    fun roundedDistance(m: Double): Int = ((m / 50.0).roundToInt() * 50).coerceAtLeast(50)

    data class UtteranceSpec(val key: String, val text: String, val urgent: Boolean)

    /** Text to speak for [e], or null when the event is shown but not spoken. */
    fun speech(e: AlertEvent): String? {
        val c = e.camera
        val limit = c.limitKmh?.let { "，速限$it" } ?: ""
        return when (e.type) {
            AlertType.APPROACH -> "前方${roundedDistance(e.distanceM)}公尺，${kindName(c)}$limit"
            AlertType.NEAR -> "注意，${kindName(c)}就在前方$limit"
            AlertType.OVERSPEED -> c.limitKmh?.let { "超速，速限$it，請減速" }
            AlertType.PASSED -> null
        }
    }

    /**
     * Converts events from a single fix into spoken utterances, merging a camera's
     * distance warning and overspeed warning into one urgent sentence so overspeed
     * does not drown out the distance announcement.
     */
    fun speeches(events: List<AlertEvent>): List<UtteranceSpec> {
        val overspeedByCam = events.filter { it.type == AlertType.OVERSPEED }.map { it.camera.id }.toSet()
        val consumedOverspeed = HashSet<String>()
        val out = ArrayList<UtteranceSpec>(events.size)

        for (e in events) {
            val c = e.camera
            when (e.type) {
                AlertType.APPROACH, AlertType.NEAR -> {
                    val base = speech(e) ?: continue
                    if (c.id in overspeedByCam && consumedOverspeed.add(c.id)) {
                        out += UtteranceSpec("camera:${c.id}", "$base，超速請減速", urgent = true)
                    } else {
                        out += UtteranceSpec("camera:${c.id}", base, urgent = e.type == AlertType.NEAR)
                    }
                }
                AlertType.OVERSPEED -> {
                    if (c.id !in consumedOverspeed) {
                        speech(e)?.let { out += UtteranceSpec("overspeed", it, urgent = true) }
                    }
                }
                AlertType.PASSED -> {}
            }
        }
        return out
    }

    fun speech(e: SectionEvent): String? {
        val s = e.status
        return when (e.type) {
            SectionEventType.ENTER ->
                "進入區間測速，速限${s.section.limitKmh}，全長${String.format(Locale.ROOT, "%.1f", s.section.lengthM / 1000)}公里"
            SectionEventType.OVER_LIMIT -> "區間平均速度超過速限，請減速"
            SectionEventType.COMPLETE -> s.averageKmh?.let { "離開區間測速，平均時速${it.roundToInt()}" }
                ?: "離開區間測速"
            SectionEventType.ABORT -> null
        }
    }
}
