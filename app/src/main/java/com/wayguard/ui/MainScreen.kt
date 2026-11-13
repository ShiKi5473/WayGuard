package com.wayguard.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wayguard.core.engine.AlertText
import com.wayguard.core.model.SpeedCamera
import com.wayguard.core.trip.TripDetail
import com.wayguard.core.trip.TripStats
import com.wayguard.core.trip.TripSummary
import com.wayguard.data.CameraData
import com.wayguard.guard.GuardState
import com.wayguard.settings.GuardSettings
import com.wayguard.settings.UserCameraStore
import com.wayguard.update.AppUpdateState
import com.wayguard.update.AvailableUpdate
import com.waymap.geo.LatLng
import com.waymap.ui.CenterRequest
import com.waymap.ui.CompassPlacement
import com.waymap.ui.MapLine
import com.waymap.ui.PointLayer
import com.waymap.ui.UserLocationMode
import com.waymap.ui.WayMap
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val StartGreen = Color(0xFF2E7D32)
private val AlertRed = Color(0xFFD32F2F)

/** Lambdas the UI needs from the host Activity (permissions, service control, data). */
data class MainActions(
    val onStartGuard: () -> Unit,
    val onStopGuard: () -> Unit,
    val onRequestLocation: () -> Unit,
    val onOpenOverlaySettings: () -> Unit,
    val onRequestBattery: () -> Unit,
    val onDismissSetup: () -> Unit,
    /** Persists a settings change; the transform receives the current [GuardSettings]. */
    val onSaveSettings: ((GuardSettings) -> GuardSettings) -> Unit,
    val onSyncNow: () -> Unit,
    val onClearSuppressed: () -> Unit,
    val onLoadTripDetail: suspend (TripSummary) -> TripDetail?,
    val onDeleteTrip: (TripSummary) -> Unit,
    val onExportTripGpx: (TripDetail) -> Unit,
    val onNavigateTripDest: (TripSummary) -> Unit,
    val onExportTripBackup: () -> Unit,
    val onImportTripBackup: () -> Unit,
    val onCheckAppUpdate: () -> Unit,
    val onDownloadAppUpdate: (AvailableUpdate) -> Unit,
    val onInstallAppUpdate: (AvailableUpdate, File) -> Unit,
    val onCancelAppUpdate: () -> Unit,
    val onDismissAppUpdate: () -> Unit,
    val onSkipAppVersion: (String) -> Unit,
    val onOpenInstallSettings: () -> Unit,
    val onRelaunchAppUpdateConfirm: (Intent) -> Unit,
)

@Composable
fun MainScreen(
    guard: GuardState,
    data: CameraData,
    settings: GuardSettings,
    perms: Permissions,
    syncing: Boolean,
    syncMessage: String?,
    appUpdate: AppUpdateState,
    trips: List<TripSummary>,
    backupMessage: String?,
    actions: MainActions,
) {
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showTrips by remember { mutableStateOf(false) }
    var activeTripDetail by remember { mutableStateOf<TripDetail?>(null) }
    var selected by remember { mutableStateOf<SpeedCamera?>(null) }
    var addAt by remember { mutableStateOf<LatLng?>(null) }
    /** Camera follows the user; panning the map turns it off, the locate button back on. */
    var follow by remember { mutableStateOf(false) }

    LaunchedEffect(guard.running) { if (guard.running) follow = true }

    // Rebuilt only when the data changes (not on every 1 Hz guard update).
    val icons = remember(data) { CameraIcons.iconsFor(data.active, data.suppressed) }
    val layers = remember(data) {
        listOf(
            PointLayer(
                "suppressed",
                data.suppressed.map { com.waymap.ui.MapPoint(it.id, it.pos, CameraIcons.iconId(it, true)) },
                minZoom = 11f, iconSize = 0.55f,
            ),
            PointLayer(
                "cameras",
                data.active.map { com.waymap.ui.MapPoint(it.id, it.pos, CameraIcons.iconId(it, false)) },
                minZoom = 9f, iconSize = 0.7f,
            ),
        )
    }
    val sectionLines = remember(data.sections) {
        data.sections.map {
            MapLine("sec-${it.id}", listOf(it.start, it.end), color = "#1976D2", width = 3f, casingColor = "#FFFFFF", casingWidth = 6f)
        }
    }
    val tripLines = remember(activeTripDetail) {
        activeTripDetail?.let(::tripMapLines).orEmpty()
    }
    val tripMarkers = remember(activeTripDetail) {
        activeTripDetail?.let(::tripMapMarkers).orEmpty()
    }
    val tripCenter = remember(activeTripDetail) {
        activeTripDetail?.let { d ->
            TripStats.computeCenterAndZoom(d.points, d.events)?.let { (pt, z) -> CenterRequest(pt, z) }
        }
    }
    val userLocation = when {
        !perms.location -> UserLocationMode.HIDDEN
        activeTripDetail != null -> UserLocationMode.SHOW
        follow && guard.running -> UserLocationMode.FOLLOW_BEARING
        follow -> UserLocationMode.FOLLOW
        else -> UserLocationMode.SHOW
    }

    val fullOverlayOpen = showSettings || showTrips

    Box(modifier = Modifier.fillMaxSize()) {
        WayMap(
            modifier = Modifier.fillMaxSize(),
            icons = icons,
            markers = tripMarkers,
            lines = sectionLines + tripLines,
            pointLayers = layers,
            centerOn = tripCenter,
            userLocation = userLocation,
            // Above the bottom controls.
            compass = CompassPlacement(endDp = 16, bottomDp = 150),
            // Ignore taps that fall through from full-screen overlays to the map.
            onPointTap = { _, id -> if (!fullOverlayOpen) selected = data.find(id) },
            onLongPress = { if (!fullOverlayOpen && activeTripDetail == null) addAt = it },
            onFollowDismissed = { follow = false },
        )

        val currentDetail = activeTripDetail
        if (currentDetail != null) {
            TripDetailOverlay(
                detail = currentDetail,
                onExportGpx = actions.onExportTripGpx,
                onNavigate = actions.onNavigateTripDest,
                onDelete = { summary ->
                    actions.onDeleteTrip(summary)
                    activeTripDetail = null
                    showTrips = true
                },
                onBack = {
                    activeTripDetail = null
                    showTrips = true
                },
            )
        } else {
            // ============ TOP — status / setup, floating over the map ============
            Column(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                guard.error?.let { msg ->
                    Card(modifier = Modifier.fillMaxWidth()) { Text(msg, modifier = Modifier.padding(12.dp)) }
                }
                SetupScreen(
                    perms = perms,
                    needOverlay = settings.overlayEnabled,
                    onRequestLocation = actions.onRequestLocation,
                    onOpenOverlay = actions.onOpenOverlaySettings,
                    onRequestBattery = actions.onRequestBattery,
                    dismissed = settings.setupDismissed,
                    onDismiss = actions.onDismissSetup,
                )
                if (guard.running) SpeedPanel(guard) else DataSummary(data)
            }

            // ============ BOTTOM — controls ============
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = settings.overlayEnabled && perms.overlay,
                        onClick = {
                            if (!perms.overlay) actions.onOpenOverlaySettings()
                            else actions.onSaveSettings { it.copy(overlayEnabled = !it.overlayEnabled) }
                        },
                        label = { Text("懸浮視窗") },
                    )
                    Spacer(Modifier.weight(1f))
                    SmallFloatingActionButton(onClick = { showTrips = true }) {
                        Icon(Icons.Default.History, contentDescription = "旅程記錄")
                    }
                    Spacer(Modifier.width(8.dp))
                    SmallFloatingActionButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                    Spacer(Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = { if (perms.location) follow = true else actions.onRequestLocation() },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "回到目前位置")
                    }
                }
                if (guard.running) {
                    Button(
                        onClick = actions.onStopGuard,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("停止守衛", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(
                        onClick = actions.onStartGuard,
                        enabled = data.dataset != null,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StartGreen, contentColor = Color.White),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("開始守衛", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        selected?.let { cam ->
            val suppressed = data.isSuppressed(cam.id)
            CameraDetailDialog(
                camera = cam,
                suppressed = suppressed,
                onSuppress = { UserCameraStore.suppress(cam); selected = null },
                onUnsuppress = { UserCameraStore.unsuppress(cam); selected = null },
                onDelete = { UserCameraStore.removeCamera(cam.id); selected = null },
                onDismiss = { selected = null },
            )
        }
        addAt?.let { p ->
            AddCameraDialog(
                pos = p,
                currentHeading = guard.heading,
                onAdd = { UserCameraStore.addCamera(it); addAt = null },
                onDismiss = { addAt = null },
            )
        }

        if (showTrips) {
            TripListScreen(
                trips = trips,
                backupMessage = backupMessage,
                onSelectTrip = { summary ->
                    scope.launch {
                        val detail = actions.onLoadTripDetail(summary)
                        if (detail != null) {
                            activeTripDetail = detail
                            showTrips = false
                        }
                    }
                },
                onExportBackup = actions.onExportTripBackup,
                onImportBackup = actions.onImportTripBackup,
                onBack = { showTrips = false },
            )
        }

        // Settings is drawn ON TOP of the map rather than replacing it, so the MapView
        // (and its location component) is never torn down mid-update.
        if (showSettings) {
            SettingsScreen(
                settings = settings,
                data = data,
                perms = perms,
                guardRunning = guard.running,
                syncing = syncing,
                syncMessage = syncMessage,
                appUpdate = appUpdate,
                actions = actions,
                onBack = { showSettings = false },
            )
        }

        AppUpdateDialog(
            state = appUpdate,
            guardRunning = guard.running,
            autoUpdateEnabled = settings.appAutoUpdate,
            hasInstallPermission = perms.installPackages,
            onDownload = actions.onDownloadAppUpdate,
            onInstall = actions.onInstallAppUpdate,
            onCancelDownload = actions.onCancelAppUpdate,
            onOpenInstallSettings = actions.onOpenInstallSettings,
            onRelaunchConfirm = actions.onRelaunchAppUpdateConfirm,
            onEnableAutoUpdate = { enabled -> actions.onSaveSettings { it.copy(appAutoUpdate = enabled) } },
            onSkipVersion = actions.onSkipAppVersion,
            onDismiss = actions.onDismissAppUpdate,
        )
    }
}

/** Speed, limit ahead and what's coming — large, glanceable. */
@Composable
private fun SpeedPanel(g: GuardState) {
    val colors = if (g.overspeed) CardDefaults.cardColors(containerColor = AlertRed, contentColor = Color.White)
    else CardDefaults.cardColors()
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                g.speedKmh?.roundToInt()?.toString() ?: "--",
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(" km/h", modifier = Modifier.padding(end = 12.dp))
            val limit = g.section?.section?.limitKmh ?: g.active?.camera?.limitKmh
            if (limit != null) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.White, CircleShape)
                        .border(5.dp, AlertRed, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(limit.toString(), color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                val s = g.section
                val a = g.active
                when {
                    !g.gpsOk -> Text("等待 GPS 定位…")
                    s != null -> {
                        Text("區間測速中", fontWeight = FontWeight.Bold)
                        Text("平均 ${s.averageKmh?.roundToInt() ?: "--"} km/h｜剩 ${formatDistance(s.remainingM)}")
                        s.maxSpeedForRemainingKmh?.let { Text("剩餘路段建議 ≤ ${it.roundToInt()} km/h") }
                    }
                    a != null -> {
                        Text(AlertText.kindName(a.camera), fontWeight = FontWeight.Bold)
                        Text("前方 ${formatDistance(a.distanceM)}")
                    }
                    else -> Text("守衛中，附近沒有測速點")
                }
            }
        }
    }
}

@Composable
private fun DataSummary(data: CameraData) {
    val ds = data.dataset ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))) {
        Text(
            "測速點 ${data.active.size} 個｜資料 ${formatIsoLocal(ds.generatedAt).take(10)}｜長按地圖可新增點位",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
