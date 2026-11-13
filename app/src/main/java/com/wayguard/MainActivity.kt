package com.wayguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wayguard.core.trip.TripDetail
import com.wayguard.core.trip.TripSummary
import com.wayguard.data.CameraRepository
import com.wayguard.guard.GuardController
import com.wayguard.guard.GuardService
import com.wayguard.settings.GuardSettings
import com.wayguard.settings.GuardSettingsStore
import com.wayguard.settings.UserCameraStore
import com.wayguard.sync.CameraSync
import com.wayguard.sync.SyncOutcome
import com.wayguard.sync.SyncScheduler
import com.wayguard.trip.TripStore
import com.wayguard.ui.MainActions
import com.wayguard.ui.MainScreen
import com.wayguard.ui.Permissions
import com.wayguard.update.AppUpdateNotifier
import com.wayguard.update.AppUpdateScheduler
import com.wayguard.update.AppUpdater
import java.time.LocalDate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val perms = mutableStateOf(Permissions())
    private val syncing = mutableStateOf(false)
    private val syncMessage = mutableStateOf<String?>(null)
    private val backupMessage = mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val count = runCatching {
                contentResolver.openOutputStream(uri)?.use { TripStore.exportBackupZip(it) } ?: 0
            }.getOrElse {
                backupMessage.value = "匯出失敗：${it.localizedMessage}"
                return@launch
            }
            backupMessage.value = "已匯出 $count 趟旅程備份"
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val count = runCatching {
                contentResolver.openInputStream(uri)?.use { TripStore.importBackupZip(it) } ?: 0
            }.getOrElse {
                backupMessage.value = "匯入失敗：${it.localizedMessage}"
                return@launch
            }
            backupMessage.value = if (count > 0) "已匯入 $count 趟旅程" else "沒有新增的旅程資料"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        val initialSettings = GuardSettingsStore.loadBlocking(this)

        // On a cold start, ask for location right away: guarding can't work without it.
        if (savedInstanceState == null && !perms.value.location) requestLocationPermission()

        // A changed Wi-Fi-only preference re-schedules the weekly update.
        lifecycleScope.launch {
            GuardSettingsStore.flow(this@MainActivity)
                .distinctUntilChangedBy { it.syncWifiOnly }
                .drop(1)
                .collect { SyncScheduler.schedule(this@MainActivity, it.syncWifiOnly) }
        }

        // Re-schedule automatic app updates when toggled or Wi-Fi preference changes.
        lifecycleScope.launch {
            GuardSettingsStore.flow(this@MainActivity)
                .distinctUntilChangedBy { it.appAutoUpdate to it.appUpdateWifiOnly }
                .drop(1)
                .collect {
                    AppUpdateScheduler.syncSchedule(this@MainActivity, it.appAutoUpdate, it.appUpdateWifiOnly)
                    if (it.appAutoUpdate) AppUpdateScheduler.checkOnStartupIfDue(this@MainActivity, it)
                }
        }

        // Resume deferred update install once driving guard stops.
        lifecycleScope.launch {
            GuardController.state
                .distinctUntilChangedBy { it.running }
                .drop(1)
                .collect { if (!it.running) AppUpdater.resumePendingInstallIfReady(this@MainActivity) }
        }

        // Launch PackageInstaller confirmation UI when the Activity is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppUpdater.confirmEvents.collect { confirmIntent ->
                    launchConfirmIntent(confirmIntent)
                }
            }
        }

        if (intent?.action == AppUpdateNotifier.ACTION_OPEN_UPDATE) {
            AppUpdater.showPromptIfUpdatePending(this)
        }

        val actions = MainActions(
            onStartGuard = ::startGuard,
            onStopGuard = { GuardService.stop(this) },
            onRequestLocation = ::requestLocationPermission,
            onOpenOverlaySettings = ::openOverlaySettings,
            onRequestBattery = ::requestIgnoreBattery,
            onDismissSetup = { saveSettings { it.copy(setupDismissed = true) } },
            onSaveSettings = ::saveSettings,
            onSyncNow = ::syncNow,
            onClearSuppressed = UserCameraStore::clearSuppressed,
            onLoadTripDetail = TripStore::loadDetail,
            onDeleteTrip = { TripStore.deleteTrip(it.id) },
            onExportTripGpx = ::exportTripGpx,
            onNavigateTripDest = ::navigateTripDest,
            onExportTripBackup = { exportBackupLauncher.launch("wayguard-trips-${LocalDate.now()}.zip") },
            onImportTripBackup = { importBackupLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
            onCheckAppUpdate = { AppUpdater.checkManual(this) },
            onDownloadAppUpdate = { rel -> AppUpdater.startDownloadAndInstall(this, rel, showPrompt = true) },
            onInstallAppUpdate = { rel, apk -> AppUpdater.installDownloaded(this, rel, apk, showPrompt = true) },
            onCancelAppUpdate = { AppUpdater.cancelDownload(this) },
            onDismissAppUpdate = AppUpdater::dismissPrompt,
            onSkipAppVersion = { ver ->
                saveSettings { it.copy(skippedAppVersion = ver) }
                AppUpdater.dismissPrompt()
            },
            onOpenInstallSettings = ::openInstallSettings,
            onRelaunchAppUpdateConfirm = ::launchConfirmIntent,
        )

        // Created once: a new Flow instance per recomposition would restart collection.
        val settingsFlow = GuardSettingsStore.flow(this)
        setContent {
            MaterialTheme {
                Surface {
                    val guard by GuardController.state.collectAsState()
                    val data by CameraRepository.state.collectAsState()
                    val settings by settingsFlow.collectAsState(initialSettings)
                    val trips by TripStore.trips.collectAsState()
                    val appUpdate by AppUpdater.state.collectAsState()
                    MainScreen(
                        guard = guard,
                        data = data,
                        settings = settings,
                        perms = perms.value,
                        syncing = syncing.value,
                        syncMessage = syncMessage.value,
                        appUpdate = appUpdate,
                        trips = trips,
                        backupMessage = backupMessage.value,
                        actions = actions,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == AppUpdateNotifier.ACTION_OPEN_UPDATE) {
            AppUpdater.showPromptIfUpdatePending(this)
        }
    }

    override fun onStart() {
        super.onStart()
        AppUpdater.setForeground(true)
    }

    override fun onStop() {
        AppUpdater.setForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        if (perms.value.installPackages) {
            AppUpdater.resumePendingInstallIfReady(this)
        }
    }

    private fun refreshPermissions() {
        val pm = getSystemService(PowerManager::class.java)
        perms.value = Permissions(
            location = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS),
            overlay = Settings.canDrawOverlays(this),
            battery = pm.isIgnoringBatteryOptimizations(packageName),
            installPackages = packageManager.canRequestPackageInstalls(),
        )
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startGuard() {
        if (!perms.value.location) {
            requestLocationPermission()
            return
        }
        // Started while this Activity is foreground — satisfies the API 34 FGS limit.
        GuardService.start(this)
    }

    private fun saveSettings(transform: (GuardSettings) -> GuardSettings) {
        lifecycleScope.launch { GuardSettingsStore.update(this@MainActivity, transform) }
    }

    private fun syncNow() {
        if (syncing.value) return
        syncing.value = true
        syncMessage.value = null
        lifecycleScope.launch {
            syncMessage.value = when (val r = CameraSync.run(this@MainActivity)) {
                SyncOutcome.UpToDate -> "已是最新資料"
                is SyncOutcome.Updated -> "已更新：${r.meta.count} 個測速點"
                is SyncOutcome.Failed -> "更新失敗：${r.message}"
            }
            syncing.value = false
        }
    }

    private fun requestLocationPermission() {
        val list = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        permissionLauncher.launch(list)
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openInstallSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    private fun launchConfirmIntent(confirmIntent: Intent) {
        runCatching {
            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(confirmIntent)
        }
    }

    private fun requestIgnoreBattery() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun exportTripGpx(detail: TripDetail) {
        lifecycleScope.launch {
            val file = TripStore.writeGpxShareFile(this@MainActivity, detail)
            val uri = FileProvider.getUriForFile(this@MainActivity, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "分享 GPX 軌跡"))
        }
    }

    private fun navigateTripDest(summary: TripSummary) {
        val dest = summary.destination
        val query = if (!dest.isNullOrBlank()) Uri.encode(dest) else "${summary.endLat},${summary.endLon}"
        val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$query")).apply {
            setPackage("com.google.android.apps.maps")
        }
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/maps?q=$query"))
        runCatching { startActivity(mapsIntent) }.onFailure { startActivity(fallback) }
    }
}
