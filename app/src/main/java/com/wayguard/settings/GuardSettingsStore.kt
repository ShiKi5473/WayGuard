package com.wayguard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wayguard.core.engine.AlertConfig
import com.wayguard.core.model.CameraKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** Single DataStore instance for all app settings and small persisted state. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persisted, user-tunable configuration. */
data class GuardSettings(
    // ---- alert distances (m) ----
    val approachSlowM: Int = 300,
    val approachMidM: Int = 500,
    val approachFastM: Int = 800,
    val nearM: Int = 150,
    val overspeedToleranceKmh: Int = 0,
    // ---- what to alert ----
    val alertSpeed: Boolean = true,
    val alertRedLight: Boolean = true,
    val alertSection: Boolean = true,
    /** User-added (mobile / custom) points. */
    val alertUser: Boolean = true,
    /** Filter bidirectional single-road cameras by travel heading vs road axis (±50°). */
    val roadAxisFilter: Boolean = true,
    // ---- output ----
    val voiceEnabled: Boolean = true,
    val beepOnOverspeed: Boolean = true,
    // ---- floating panel ----
    val overlayEnabled: Boolean = false,
    val overlayAlpha: Float = 0.9f,
    /** Last dragged panel position (px); negative = not yet placed. */
    val overlayX: Int = -1,
    val overlayY: Int = -1,
    // ---- data updates ----
    val syncWifiOnly: Boolean = false,
    /** Wall-clock ms of the last successful update check; 0 = never. */
    val lastSyncCheckMs: Long = 0L,
    // ---- app updates ----
    val appAutoUpdate: Boolean = false,
    val appUpdateWifiOnly: Boolean = true,
    /** Wall-clock ms of the last successful GitHub release check; 0 = never. */
    val lastAppUpdateCheckMs: Long = 0L,
    /** Version string skipped by the user (e.g. "0.2.0"); empty if none. */
    val skippedAppVersion: String = "",
    // ---- trip recording ----
    val tripRecordingEnabled: Boolean = true,
    val tripRetentionDays: Int = 90,
    val tripAutoSplitMinutes: Int = 15,
    // ---- misc UI state ----
    val setupDismissed: Boolean = false,
) {
    fun alertConfig(): AlertConfig = AlertConfig(
        approachSlowM = approachSlowM.toDouble(),
        approachMidM = approachMidM.toDouble(),
        approachFastM = approachFastM.toDouble(),
        nearM = nearM.toDouble(),
        overspeedToleranceKmh = overspeedToleranceKmh,
        enabledKinds = buildSet {
            if (alertSpeed) add(CameraKind.SPEED)
            if (alertRedLight) add(CameraKind.RED_LIGHT)
            if (alertSection) add(CameraKind.SECTION)
            if (alertUser) { add(CameraKind.MOBILE); add(CameraKind.CUSTOM) }
        },
        roadAxisFilterEnabled = roadAxisFilter,
    )
}

object GuardSettingsStore {

    private object Keys {
        val approachSlowM = intPreferencesKey("approach_slow_m")
        val approachMidM = intPreferencesKey("approach_mid_m")
        val approachFastM = intPreferencesKey("approach_fast_m")
        val nearM = intPreferencesKey("near_m")
        val overspeedToleranceKmh = intPreferencesKey("overspeed_tolerance_kmh")
        val alertSpeed = booleanPreferencesKey("alert_speed")
        val alertRedLight = booleanPreferencesKey("alert_red_light")
        val alertSection = booleanPreferencesKey("alert_section")
        val alertUser = booleanPreferencesKey("alert_user")
        val roadAxisFilter = booleanPreferencesKey("road_axis_filter")
        val voiceEnabled = booleanPreferencesKey("voice_enabled")
        val beepOnOverspeed = booleanPreferencesKey("beep_on_overspeed")
        val overlayEnabled = booleanPreferencesKey("overlay_enabled")
        val overlayAlpha = floatPreferencesKey("overlay_alpha")
        val overlayX = intPreferencesKey("overlay_x")
        val overlayY = intPreferencesKey("overlay_y")
        val syncWifiOnly = booleanPreferencesKey("sync_wifi_only")
        val lastSyncCheckMs = longPreferencesKey("last_sync_check_ms")
        val appAutoUpdate = booleanPreferencesKey("app_auto_update")
        val appUpdateWifiOnly = booleanPreferencesKey("app_update_wifi_only")
        val lastAppUpdateCheckMs = longPreferencesKey("last_app_update_check_ms")
        val skippedAppVersion = stringPreferencesKey("skipped_app_version")
        val tripRecordingEnabled = booleanPreferencesKey("trip_recording_enabled")
        val tripRetentionDays = intPreferencesKey("trip_retention_days")
        val tripAutoSplitMinutes = intPreferencesKey("trip_auto_split_minutes")
        val setupDismissed = booleanPreferencesKey("setup_dismissed")
    }

    private fun read(p: Preferences): GuardSettings {
        val d = GuardSettings()
        return GuardSettings(
            approachSlowM = p[Keys.approachSlowM] ?: d.approachSlowM,
            approachMidM = p[Keys.approachMidM] ?: d.approachMidM,
            approachFastM = p[Keys.approachFastM] ?: d.approachFastM,
            nearM = p[Keys.nearM] ?: d.nearM,
            overspeedToleranceKmh = p[Keys.overspeedToleranceKmh] ?: d.overspeedToleranceKmh,
            alertSpeed = p[Keys.alertSpeed] ?: d.alertSpeed,
            alertRedLight = p[Keys.alertRedLight] ?: d.alertRedLight,
            alertSection = p[Keys.alertSection] ?: d.alertSection,
            alertUser = p[Keys.alertUser] ?: d.alertUser,
            roadAxisFilter = p[Keys.roadAxisFilter] ?: d.roadAxisFilter,
            voiceEnabled = p[Keys.voiceEnabled] ?: d.voiceEnabled,
            beepOnOverspeed = p[Keys.beepOnOverspeed] ?: d.beepOnOverspeed,
            overlayEnabled = p[Keys.overlayEnabled] ?: d.overlayEnabled,
            overlayAlpha = p[Keys.overlayAlpha] ?: d.overlayAlpha,
            overlayX = p[Keys.overlayX] ?: d.overlayX,
            overlayY = p[Keys.overlayY] ?: d.overlayY,
            syncWifiOnly = p[Keys.syncWifiOnly] ?: d.syncWifiOnly,
            lastSyncCheckMs = p[Keys.lastSyncCheckMs] ?: d.lastSyncCheckMs,
            appAutoUpdate = p[Keys.appAutoUpdate] ?: d.appAutoUpdate,
            appUpdateWifiOnly = p[Keys.appUpdateWifiOnly] ?: d.appUpdateWifiOnly,
            lastAppUpdateCheckMs = p[Keys.lastAppUpdateCheckMs] ?: d.lastAppUpdateCheckMs,
            skippedAppVersion = p[Keys.skippedAppVersion] ?: d.skippedAppVersion,
            tripRecordingEnabled = p[Keys.tripRecordingEnabled] ?: d.tripRecordingEnabled,
            tripRetentionDays = p[Keys.tripRetentionDays] ?: d.tripRetentionDays,
            tripAutoSplitMinutes = p[Keys.tripAutoSplitMinutes] ?: d.tripAutoSplitMinutes,
            setupDismissed = p[Keys.setupDismissed] ?: d.setupDismissed,
        )
    }

    private fun write(p: MutablePreferences, s: GuardSettings) {
        p[Keys.approachSlowM] = s.approachSlowM
        p[Keys.approachMidM] = s.approachMidM
        p[Keys.approachFastM] = s.approachFastM
        p[Keys.nearM] = s.nearM
        p[Keys.overspeedToleranceKmh] = s.overspeedToleranceKmh
        p[Keys.alertSpeed] = s.alertSpeed
        p[Keys.alertRedLight] = s.alertRedLight
        p[Keys.alertSection] = s.alertSection
        p[Keys.alertUser] = s.alertUser
        p[Keys.roadAxisFilter] = s.roadAxisFilter
        p[Keys.voiceEnabled] = s.voiceEnabled
        p[Keys.beepOnOverspeed] = s.beepOnOverspeed
        p[Keys.overlayEnabled] = s.overlayEnabled
        p[Keys.overlayAlpha] = s.overlayAlpha
        p[Keys.overlayX] = s.overlayX
        p[Keys.overlayY] = s.overlayY
        p[Keys.syncWifiOnly] = s.syncWifiOnly
        p[Keys.lastSyncCheckMs] = s.lastSyncCheckMs
        p[Keys.appAutoUpdate] = s.appAutoUpdate
        p[Keys.appUpdateWifiOnly] = s.appUpdateWifiOnly
        p[Keys.lastAppUpdateCheckMs] = s.lastAppUpdateCheckMs
        p[Keys.skippedAppVersion] = s.skippedAppVersion
        p[Keys.tripRecordingEnabled] = s.tripRecordingEnabled
        p[Keys.tripRetentionDays] = s.tripRetentionDays
        p[Keys.tripAutoSplitMinutes] = s.tripAutoSplitMinutes
        p[Keys.setupDismissed] = s.setupDismissed
    }

    fun flow(context: Context): Flow<GuardSettings> = context.settingsDataStore.data.map(::read)

    /** Reads the current settings once, blocking. Used at cold start before the first frame. */
    fun loadBlocking(context: Context): GuardSettings = runBlocking { flow(context).first() }

    /** Applies [transform] to the current settings and persists the result atomically. */
    suspend fun update(context: Context, transform: (GuardSettings) -> GuardSettings) {
        context.settingsDataStore.edit { p -> write(p, transform(read(p))) }
    }
}
