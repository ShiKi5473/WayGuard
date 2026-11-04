package com.wayguard.guard

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.wayguard.MainActivity
import com.wayguard.core.engine.AlertText
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Floating panel drawn over other apps (e.g. a navigation app): current speed, the
 * limit ahead, distance to the camera or the section average. Dragging moves it
 * (the final position is reported via [onMoved]); a tap opens WayGuard.
 */
class OverlayController(
    private val context: Context,
    private val onMoved: (x: Int, y: Int) -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var speedText: TextView
    private lateinit var limitBadge: TextView
    private lateinit var infoText: TextView
    private val background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }

    val isShown: Boolean get() = panel != null

    fun show(x: Int, y: Int, alpha: Float) {
        if (panel != null || !Settings.canDrawOverlays(context)) return
        speedText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            text = "--"
        }
        val unit = TextView(context).apply {
            setTextColor(Color.argb(200, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = "km/h"
            setPadding(dp(2), 0, dp(10), 0)
        }
        limitBadge = TextView(context).apply {
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(4), Color.rgb(211, 47, 47))
            }
            visibility = View.GONE
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speedText)
            addView(unit)
            addView(limitBadge, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        infoText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "等待 GPS…"
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = this@OverlayController.background
            this.alpha = alpha
            addView(top)
            addView(infoText)
        }
        setOverspeed(false)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Restore the last dragged position, or a sensible default the first time.
            this.x = if (x >= 0) x else dp(16)
            this.y = if (y >= 0) y else dp(120)
        }
        clampToScreen()
        attachDragAndTap(container)
        runCatching { wm.addView(container, params) }.onSuccess { panel = container }
    }

    fun setAlpha(alpha: Float) {
        panel?.alpha = alpha
    }

    fun update(s: GuardState) {
        if (panel == null) return
        speedText.text = s.speedKmh?.roundToInt()?.toString() ?: "--"
        val limit = s.section?.section?.limitKmh ?: s.active?.camera?.limitKmh
        limitBadge.visibility = if (limit != null) View.VISIBLE else View.GONE
        limit?.let { limitBadge.text = it.toString() }
        infoText.text = when {
            !s.gpsOk -> "等待 GPS…"
            s.section != null -> {
                val avg = s.section.averageKmh?.roundToInt()?.toString() ?: "--"
                "區間平均 $avg｜剩 ${String.format(Locale.ROOT, "%.1f", s.section.remainingM / 1000)} km"
            }
            s.active != null -> "${AlertText.kindName(s.active.camera)} ${s.active.distanceM.roundToInt()} m"
            else -> "守衛中"
        }
        setOverspeed(s.overspeed)
    }

    fun hide() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    /** Keep the panel on-screen after rotation. */
    fun onConfigurationChanged() {
        val p = panel ?: return
        clampToScreen()
        wm.updateViewLayout(p, params)
    }

    private fun setOverspeed(over: Boolean) {
        background.setColor(if (over) Color.argb(230, 211, 47, 47) else Color.argb(190, 20, 20, 20))
    }

    /** Drag the whole panel; a touch that barely moves is a tap that opens the app. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap(view: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = dp(8)
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        clampToScreen()
                        panel?.let { wm.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) onMoved(params.x, params.y) else openApp()
                    true
                }
                else -> false
            }
        }
    }

    private fun openApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun clampToScreen() {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - dp(80)).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - dp(60)).coerceAtLeast(0)
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()
}
