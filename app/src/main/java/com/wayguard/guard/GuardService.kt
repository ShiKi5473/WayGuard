package com.wayguard.guard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.wayguard.MainActivity
import com.wayguard.core.engine.AlertEngine
import com.wayguard.core.engine.AlertText
import com.wayguard.core.engine.AlertType
import com.wayguard.core.engine.Fix
import com.wayguard.core.engine.HeadingFilter
import com.wayguard.core.engine.SectionEventType
import com.wayguard.core.engine.SectionTracker
import com.wayguard.data.CameraRepository
import com.wayguard.settings.GuardSettings
import com.wayguard.settings.GuardSettingsStore
import com.waymap.geo.LatLng
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that does the guarding: GPS once a second → [AlertEngine] and
 * [SectionTracker] → voice, beeps, notification, floating panel and [GuardController].
 * Started from the visible Activity, so foreground-only location access suffices.
 */
class GuardService : LifecycleService() {

    private lateinit var lm: LocationManager
    private lateinit var announcer: Announcer
    private val engine = AlertEngine()
    private val sectionTracker = SectionTracker()
    private val headingFilter = HeadingFilter()
    private var overlay: OverlayController? = null
    private var settings = GuardSettings()

    private var lastLocation: Location? = null
    private var lastFixElapsedMs = 0L
    private var lastBeepMs = 0L
    private var lastNotifyMs = 0L

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderDisabled(provider: String) {
            GuardController.update { it.copy(gpsOk = false, error = "GPS 已關閉，請開啟定位") }
            refreshNotification(force = true)
        }
        override fun onProviderEnabled(provider: String) {
            GuardController.update { it.copy(error = null) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        GuardController.update { GuardState(running = true, position = it.position) }
        startForeground(NOTIF_ID, buildNotification(GuardController.current), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        announcer = Announcer(this)
        lm = getSystemService(LocationManager::class.java)
        applySettings(GuardSettingsStore.loadBlocking(this))

        lifecycleScope.launch {
            CameraRepository.state.collect {
                engine.setCameras(it.index)
                sectionTracker.setSections(it.sections)
            }
        }
        lifecycleScope.launch { GuardSettingsStore.flow(this@GuardService).collect(::applySettings) }
        lifecycleScope.launch {
            // GPS watchdog: flag a lost fix (tunnel, indoors) so the UI doesn't show stale data.
            while (isActive) {
                delay(2_000)
                val stale = SystemClock.elapsedRealtime() - lastFixElapsedMs > GPS_STALE_MS
                if (stale && GuardController.current.gpsOk) {
                    GuardController.update { it.copy(gpsOk = false, speedKmh = null, active = null) }
                    overlay?.update(GuardController.current)
                    refreshNotification(force = true)
                }
            }
        }
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            GuardController.update { it.copy(error = "未授予精確定位權限") }
            stopSelf()
            return
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            GuardController.update { it.copy(error = "GPS 已關閉，請開啟定位") }
        }
        val request = LocationRequest.Builder(1_000L)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(500L)
            .build()
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, mainExecutor, listener)
    }

    private fun applySettings(s: GuardSettings) {
        settings = s
        val cfg = s.alertConfig()
        // Replacing the config resets per-camera progress, so only do it on real changes.
        if (cfg != engine.config) engine.config = cfg
        sectionTracker.overspeedToleranceKmh = s.overspeedToleranceKmh
        announcer.voiceEnabled = s.voiceEnabled
        if (s.overlayEnabled) {
            val o = overlay ?: OverlayController(this) { x, y ->
                lifecycleScope.launch {
                    GuardSettingsStore.update(this@GuardService) { it.copy(overlayX = x, overlayY = y) }
                }
            }.also { overlay = it }
            if (!o.isShown) {
                o.show(s.overlayX, s.overlayY, s.overlayAlpha)
                o.update(GuardController.current)
            } else {
                o.setAlpha(s.overlayAlpha)
            }
        } else {
            overlay?.hide()
            overlay = null
        }
    }

    private fun onLocation(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        val nowMs = loc.elapsedRealtimeNanos / 1_000_000
        val prev = lastLocation
        val speedKmh = when {
            loc.hasSpeed() -> loc.speed * 3.6
            prev != null -> {
                val dt = (nowMs - prev.elapsedRealtimeNanos / 1_000_000) / 1000.0
                if (dt > 0) prev.distanceTo(loc) / dt * 3.6 else 0.0
            }
            else -> 0.0
        }
        lastLocation = loc
        lastFixElapsedMs = SystemClock.elapsedRealtime()

        val fix = Fix(
            pos = LatLng(loc.latitude, loc.longitude),
            speedKmh = speedKmh,
            bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            timeMs = nowMs,
        )
        val heading = headingFilter.update(fix)
        val result = engine.update(fix, heading)
        val (sectionEvents, sectionStatus) = sectionTracker.update(fix, heading)

        var message: String? = null
        for (e in result.events) {
            AlertText.speech(e)?.let {
                announcer.say(it, urgent = e.type == AlertType.OVERSPEED || e.type == AlertType.NEAR)
                message = it
            }
        }
        for (e in sectionEvents) {
            AlertText.speech(e)?.let {
                announcer.say(it, urgent = e.type == SectionEventType.OVER_LIMIT)
                message = it
            }
        }

        GuardController.update {
            it.copy(
                position = fix.pos,
                speedKmh = speedKmh,
                heading = heading,
                gpsOk = true,
                active = result.active,
                section = sectionStatus,
                lastMessage = message ?: it.lastMessage,
                error = null,
            )
        }
        val state = GuardController.current
        val now = SystemClock.elapsedRealtime()
        if (state.overspeed && settings.beepOnOverspeed && now - lastBeepMs >= BEEP_INTERVAL_MS) {
            announcer.beep()
            lastBeepMs = now
        }
        overlay?.update(state)
        refreshNotification(force = message != null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        lm.removeUpdates(listener)
        announcer.shutdown()
        overlay?.hide()
        overlay = null
        engine.reset()
        sectionTracker.reset()
        GuardController.reset()
        super.onDestroy()
    }

    // ---------------- notification ----------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "測速守衛", NotificationManager.IMPORTANCE_LOW).apply {
                description = "守衛執行中的常駐通知（車速與前方測速點）"
                setShowBadge(false)
            }
        )
    }

    /** Throttled so a 1 Hz GPS doesn't rebuild the notification every second. */
    private fun refreshNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyMs < NOTIFY_INTERVAL_MS) return
        lastNotifyMs = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(GuardController.current))
    }

    private fun buildNotification(s: GuardState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(if (s.overspeed) "測速守衛中｜超速" else "測速守衛中")
            .setContentText(statusLine(s))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .addAction(Notification.Action.Builder(null, "停止守衛", stop).build())
            .build()
    }

    private fun statusLine(s: GuardState): String {
        s.error?.let { return it }
        if (!s.gpsOk) return "等待 GPS 定位…"
        val speed = "${s.speedKmh?.roundToInt() ?: 0} km/h"
        val ahead = when {
            s.section != null ->
                "區間測速中，平均 ${s.section.averageKmh?.roundToInt() ?: "--"}／限 ${s.section.section.limitKmh}"
            s.active != null -> {
                val limit = s.active.camera.limitKmh?.let { "（速限 $it）" } ?: ""
                "前方${AlertText.kindName(s.active.camera)} ${s.active.distanceM.roundToInt()} m$limit"
            }
            else -> "附近沒有測速點"
        }
        return "$speed · $ahead"
    }

    companion object {
        private const val CHANNEL_ID = "guard"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.wayguard.action.STOP"
        /** Fixes worse than this are ignored (multipath in cities, cold start). */
        private const val MAX_ACCURACY_M = 50f
        private const val GPS_STALE_MS = 5_000L
        private const val BEEP_INTERVAL_MS = 3_000L
        private const val NOTIFY_INTERVAL_MS = 3_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GuardService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
