package com.wayguard.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wayguard.settings.GuardSettings
import com.wayguard.settings.GuardSettingsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/** Background worker for automatic GitHub release checks, APK downloads and updates. */
class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = GuardSettingsStore.flow(applicationContext).first()
        if (!settings.appAutoUpdate) return Result.success()
        val ok = AppUpdater.runAutoCheckAndUpdate(applicationContext, settings.skippedAppVersion)
        return if (ok) Result.success()
        else if (runAttemptCount < 2) Result.retry()
        else Result.failure()
    }
}

object AppUpdateScheduler {

    private const val PERIODIC = "app-update-periodic"
    private const val STARTUP = "app-update-startup"
    private val CHECK_INTERVAL_MS = TimeUnit.DAYS.toMillis(1)

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    fun syncSchedule(context: Context, enabled: Boolean, wifiOnly: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(STARTUP)
            return
        }
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints(wifiOnly))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun checkOnStartupIfDue(context: Context, settings: GuardSettings) {
        if (!settings.appAutoUpdate) return
        if (System.currentTimeMillis() - settings.lastAppUpdateCheckMs < CHECK_INTERVAL_MS) return
        val request = OneTimeWorkRequestBuilder<AppUpdateWorker>()
            .setConstraints(constraints(settings.appUpdateWifiOnly))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(STARTUP, ExistingWorkPolicy.KEEP, request)
    }
}

