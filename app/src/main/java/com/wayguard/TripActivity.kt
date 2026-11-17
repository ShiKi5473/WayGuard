package com.wayguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.wayguard.core.trip.TripDetail
import com.wayguard.core.trip.TripSummary
import com.wayguard.trip.TripStore
import com.wayguard.ui.TripActions
import com.wayguard.ui.TripBrowser
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * Stand-alone trip browser (list, statistics, per-trip map, speed chart and playback). Kept
 * separate from [MainActivity] so browsing history never disturbs the live guard map.
 */
class TripActivity : ComponentActivity() {

    private val backupMessage = mutableStateOf<String?>(null)

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
        val initialTripId = intent?.getStringExtra(EXTRA_TRIP_ID)
        val actions = TripActions(
            onLoadDetail = TripStore::loadDetail,
            onDelete = { ids -> ids.forEach(TripStore::deleteTrip) },
            onExportGpx = ::exportTripGpx,
            onNavigate = ::navigateTripDest,
            onExportBackup = { exportBackupLauncher.launch("wayguard-trips-${LocalDate.now()}.zip") },
            onImportBackup = { importBackupLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
            onDismissMessage = { backupMessage.value = null },
        )
        setContent {
            MaterialTheme {
                Surface {
                    val trips by TripStore.trips.collectAsState()
                    TripBrowser(
                        trips = trips,
                        message = backupMessage.value,
                        initialTripId = initialTripId,
                        actions = actions,
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    private fun exportTripGpx(detail: TripDetail) {
        lifecycleScope.launch {
            val file = TripStore.writeGpxShareFile(this@TripActivity, detail)
            val uri = FileProvider.getUriForFile(this@TripActivity, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
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

    companion object {
        const val EXTRA_TRIP_ID = "com.wayguard.extra.TRIP_ID"

        /** Opens the trip list, or straight into the trip with [tripId] when given. */
        fun intent(context: Context, tripId: String? = null): Intent =
            Intent(context, TripActivity::class.java).apply {
                if (tripId != null) putExtra(EXTRA_TRIP_ID, tripId)
            }
    }
}
