package com.wayguard

import android.app.Application
import com.wayguard.data.CameraRepository
import com.wayguard.settings.GuardSettingsStore
import com.wayguard.settings.UserCameraStore
import com.wayguard.sync.SyncScheduler
import com.wayguard.trip.TripStore
import com.wayguard.update.AppUpdateScheduler
import com.wayguard.update.AppUpdater
import com.waymap.ui.WayMapCache

class WayGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // User data first: the repository merges it into the camera set.
        UserCameraStore.init(this)
        CameraRepository.init(this)
        val settings = GuardSettingsStore.loadBlocking(this)
        TripStore.init(this, settings.tripRetentionDays)
        SyncScheduler.schedule(this, settings.syncWifiOnly)
        SyncScheduler.catchUpIfStale(this, settings.lastSyncCheckMs, settings.syncWifiOnly)
        AppUpdater.cleanupStaleFiles(this)
        AppUpdateScheduler.syncSchedule(this, settings.appAutoUpdate, settings.appUpdateWifiOnly)
        AppUpdateScheduler.checkOnStartupIfDue(this, settings)
        // 看過的區域圖磚留在本機，山區等沒有訊號處仍可離線顯示
        WayMapCache.setAmbientCacheSize(this, 200L * 1024 * 1024)
    }
}
