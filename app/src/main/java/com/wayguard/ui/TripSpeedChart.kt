package com.wayguard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.wayguard.core.trip.SpeedSample
import com.wayguard.core.trip.SpeedTier
import com.wayguard.core.trip.TripEvent
import kotlin.math.ceil
import kotlin.math.max

private val CHART_LEFT = 34.dp
private val CHART_RIGHT = 8.dp
private val CHART_TOP = 8.dp
private val CHART_BOTTOM = 18.dp

/**
 * Speed-over-time chart colored by [SpeedTier], with overspeed alerts (red dots), posted limits
 * at alert points (dashed ticks) and a scrub cursor. Tap or drag horizontally to scrub.
 */
@Composable
fun TripSpeedChart(
    series: List<SpeedSample>,
    events: List<TripEvent>,
    startMs: Long,
    endMs: Long,
    cursorMs: Long?,
    cursorSpeedKmh: Double?,
    onScrub: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val cursorColor = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val scrub by rememberUpdatedState(onScrub)

    val overs = remember(events) { events.filter { it.isOverspeed } }
    val limits = remember(events) {
        events.mapNotNull { e -> (e.limitKmh ?: e.camera?.limitKmh)?.let { e.tMs to it } }.distinct()
    }
    val topKmh = remember(series, limits) {
        val peak = max(series.maxOfOrNull { it.speedKmh } ?: 0.0, limits.maxOfOrNull { it.second.toDouble() } ?: 0.0)
        max(40.0, ceil(peak / 20.0) * 20.0)
    }
    val spanMs = (endMs - startMs).coerceAtLeast(1L)

    fun Density.xToTime(x: Float, width: Float): Long {
        val left = CHART_LEFT.toPx()
        val plotW = (width - left - CHART_RIGHT.toPx()).coerceAtLeast(1f)
        val f = ((x - left) / plotW).coerceIn(0f, 1f)
        return startMs + (f * spanMs).toLong()
    }

    Canvas(
        modifier = modifier
            .pointerInput(startMs, endMs) {
                detectTapGestures { scrub(xToTime(it.x, size.width.toFloat())) }
            }
            .pointerInput(startMs, endMs) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    scrub(xToTime(change.position.x, size.width.toFloat()))
                }
            },
    ) {
        val left = CHART_LEFT.toPx()
        val right = size.width - CHART_RIGHT.toPx()
        val top = CHART_TOP.toPx()
        val bottom = size.height - CHART_BOTTOM.toPx()
        val plotW = (right - left).coerceAtLeast(1f)
        val plotH = (bottom - top).coerceAtLeast(1f)
        fun x(t: Long) = left + ((t - startMs).toFloat() / spanMs) * plotW
        fun y(kmh: Double) = bottom - (kmh / topKmh).toFloat().coerceIn(0f, 1f) * plotH

        // Horizontal grid + Y labels.
        val step = if (topKmh <= 100.0) 20.0 else 40.0
        var g = 0.0
        while (g <= topKmh + 0.1) {
            val gy = y(g)
            drawLine(gridColor, Offset(left, gy), Offset(right, gy), strokeWidth = 1f)
            val layout = measurer.measure(g.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(left - layout.size.width - 4.dp.toPx(), gy - layout.size.height / 2f))
            g += step
        }
        // X labels: start / middle / end clock time.
        listOf(startMs, startMs + spanMs / 2, endMs).forEachIndexed { i, t ->
            val layout = measurer.measure(formatHourMinute(t), labelStyle)
            val lx = when (i) {
                0 -> left
                1 -> x(t) - layout.size.width / 2f
                else -> right - layout.size.width
            }
            drawText(layout, topLeft = Offset(lx, bottom + 2.dp.toPx()))
        }

        // Speed line, one tier-colored segment per sample pair.
        val stroke = 2.dp.toPx()
        for (i in 1 until series.size) {
            val a = series[i - 1]
            val b = series[i]
            drawLine(
                speedTierColor(SpeedTier.fromSpeed((a.speedKmh + b.speedKmh) / 2.0)),
                Offset(x(a.tMs), y(a.speedKmh)),
                Offset(x(b.tMs), y(b.speedKmh)),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        // Posted limits around alerts.
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        val half = 10.dp.toPx()
        for ((t, limit) in limits) {
            val lx = x(t)
            val ly = y(limit.toDouble())
            drawLine(OverspeedRed.copy(alpha = 0.7f), Offset(lx - half, ly), Offset(lx + half, ly), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
        }
        for (e in overs) {
            drawCircle(OverspeedRed, radius = 3.5.dp.toPx(), center = Offset(x(e.tMs), y(e.speedKmh)))
        }

        // Scrub cursor.
        if (cursorMs != null) {
            val cx = x(cursorMs.coerceIn(startMs, endMs))
            drawLine(cursorColor, Offset(cx, top), Offset(cx, bottom), strokeWidth = 1.5.dp.toPx())
            if (cursorSpeedKmh != null) {
                drawCircle(cursorColor, radius = 4.5.dp.toPx(), center = Offset(cx, y(cursorSpeedKmh)))
            }
        }
    }
}
