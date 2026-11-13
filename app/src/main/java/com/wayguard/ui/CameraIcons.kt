package com.wayguard.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.SpeedCamera
import com.waymap.ui.MapIcon

/**
 * Map icons drawn on the fly: a round speed-limit sign (white disc, coloured ring,
 * limit number). The ring colour tells the camera type apart. Nothing here copies
 * another app's artwork; it's the generic road-sign shape.
 */
object CameraIcons {

    enum class Style(val key: String, val ring: Int) {
        SPEED("s", Color.rgb(211, 47, 47)),
        RED_LIGHT("rl", Color.rgb(211, 47, 47)),
        SECTION("sec", Color.rgb(25, 118, 210)),
        USER("usr", Color.rgb(123, 31, 162)),
        OFF("off", Color.rgb(158, 158, 158)),
    }

    private const val SIZE_PX = 72

    fun styleOf(c: SpeedCamera, suppressed: Boolean): Style = when {
        suppressed -> Style.OFF
        CameraKind.MOBILE in c.kinds || CameraKind.CUSTOM in c.kinds -> Style.USER
        CameraKind.SECTION in c.kinds -> Style.SECTION
        CameraKind.RED_LIGHT in c.kinds -> Style.RED_LIGHT
        else -> Style.SPEED
    }

    /** Icon id for [c]; register the same ids via [iconsFor]. */
    fun iconId(c: SpeedCamera, suppressed: Boolean): String =
        "cam-${styleOf(c, suppressed).key}-${c.limitKmh ?: "x"}"

    /** One [MapIcon] per distinct style/limit combination among the cameras. */
    fun iconsFor(active: List<SpeedCamera>, suppressed: List<SpeedCamera>): List<MapIcon> {
        val combos = LinkedHashSet<Pair<Style, Int?>>()
        active.forEach { combos += styleOf(it, false) to it.limitKmh }
        suppressed.forEach { combos += Style.OFF to it.limitKmh }
        return combos.map { (style, limit) ->
            MapIcon("cam-${style.key}-${limit ?: "x"}") { sign(style, limit) }
        } + tripIcons()
    }

    const val TRIP_ICON_START = "trip-start"
    const val TRIP_ICON_END = "trip-end"
    const val TRIP_ICON_EVENT = "trip-event"
    const val TRIP_ICON_OVERSPEED = "trip-over"

    private fun tripIcons(): List<MapIcon> = listOf(
        MapIcon(TRIP_ICON_START) { dotPin(Color.rgb(46, 125, 50), 40) },
        MapIcon(TRIP_ICON_END) { dotPin(Color.rgb(38, 50, 56), 40) },
        MapIcon(TRIP_ICON_EVENT) { dotPin(Color.rgb(25, 118, 210), 32) },
        MapIcon(TRIP_ICON_OVERSPEED) { dotPin(Color.rgb(211, 47, 47), 36) },
    )

    private fun dotPin(fillColor: Int, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = sizePx / 2f
        val r = c - sizePx * 0.12f
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawCircle(c, c, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sizePx * 0.12f
        paint.color = Color.WHITE
        canvas.drawCircle(c, c, r, paint)
        return bmp
    }

    private fun sign(style: Style, limit: Int?): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val c = SIZE_PX / 2f
        val ringWidth = SIZE_PX * 0.13f
        val radius = c - ringWidth / 2f - SIZE_PX * 0.04f

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.setShadowLayer(SIZE_PX * 0.04f, 0f, SIZE_PX * 0.02f, Color.argb(110, 0, 0, 0))
        canvas.drawCircle(c, c, radius, paint)
        paint.clearShadowLayer()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringWidth
        paint.color = style.ring
        canvas.drawCircle(c, c, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (style == Style.OFF) Color.GRAY else Color.BLACK
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val label = limit?.toString() ?: "測"
        paint.textSize = SIZE_PX * if (label.length >= 3) 0.30f else 0.38f
        val baseline = c - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, c, baseline, paint)

        // Red-light cameras get a small red "light" in the corner.
        if (style == Style.RED_LIGHT) {
            paint.color = Color.rgb(211, 47, 47)
            canvas.drawCircle(SIZE_PX * 0.82f, SIZE_PX * 0.18f, SIZE_PX * 0.12f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = SIZE_PX * 0.03f
            paint.color = Color.WHITE
            canvas.drawCircle(SIZE_PX * 0.82f, SIZE_PX * 0.18f, SIZE_PX * 0.12f, paint)
        }
        return bmp
    }
}
