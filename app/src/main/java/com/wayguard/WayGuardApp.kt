package com.wayguard

import android.app.Application
import com.wayguard.data.CameraRepository
import com.wayguard.settings.GuardSettingsStore
import com.wayguard.settings.UserCameraStore
import com.wayguard.sync.SyncScheduler

class WayGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // User data first: the repository merges it into the camera set.
        UserCameraStore.init(this)
        CameraRepository.init(this)
        val settings = GuardSettingsStore.loadBlocking(this)
        SyncScheduler.schedule(this, settings.syncWifiOnly)
        SyncScheduler.catchUpIfStale(this, settings.lastSyncCheckMs, settings.syncWifiOnly)
    }
}
