package com.wayguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wayguard.core.trip.SpeedTier
import com.wayguard.core.trip.TripDetail
import com.wayguard.core.trip.TripEvent
import com.wayguard.core.trip.TripEventType
import com.wayguard.core.trip.TripStats
import com.wayguard.core.trip.TripSummary
import com.waymap.geo.LatLng
import com.waymap.ui.MapLine
import com.waymap.ui.MapMarker
import kotlin.math.roundToInt

/** Maps a [SpeedTier] to its Compose UI color. */
fun speedTierColor(tier: SpeedTier): Color = when (tier) {
    SpeedTier.SLOW -> Color(0xFF1E88E5)
    SpeedTier.CITY -> Color(0xFF43A047)
    SpeedTier.ARTERIAL -> Color(0xFFF9A825)
    SpeedTier.HIGHWAY -> Color(0xFFFB8C00)
    SpeedTier.HIGH_SPEED -> Color(0xFFD32F2F)
}

/** Returns the color representing [speedKmh], forcing alert red when [overspeed] is true. */
fun speedColor(speedKmh: Double, overspeed: Boolean = false): Color =
    if (overspeed) Color(0xFFD32F2F) else speedTierColor(SpeedTier.fromSpeed(speedKmh))

/** Builds speed-colored track polylines (with red overspeed highlighting) for a trip detail. */
fun tripMapLines(detail: TripDetail): List<MapLine> =
    TripStats.buildSegments(detail.points, detail.events).mapIndexed { idx, seg ->
        MapLine(
            id = "trip-${detail.summary.id}-$idx",
            points = seg.points,
            color = seg.colorHex,
            width = if (seg.overspeed) 6.5f else 5f,
            casingColor = "#FFFFFF",
            casingWidth = if (seg.overspeed) 9.5f else 8f,
        )
    }

/** Builds start/end and key alert markers for a trip detail. */
fun tripMapMarkers(detail: TripDetail): List<MapMarker> = buildList {
    val pts = detail.points
    if (pts.isNotEmpty()) {
        add(
            MapMarker(
                id = "trip-m-start",
                pos = LatLng(pts.first().lat, pts.first().lon),
                icon = CameraIcons.TRIP_ICON_START,
                label = "起點",
                sortKey = 2f,
            )
        )
        if (pts.size >= 2) {
            add(
                MapMarker(
                    id = "trip-m-end",
                    pos = LatLng(pts.last().lat, pts.last().lon),
                    icon = CameraIcons.TRIP_ICON_END,
                    label = "終點",
                    sortKey = 2f,
                )
            )
        }
    }
    detail.events.forEachIndexed { idx, ev ->
        val show = ev.type == TripEventType.OVERSPEED ||
            ev.type == TripEventType.SECTION_OVER ||
            ev.type == TripEventType.NEAR ||
            ev.type == TripEventType.SECTION_ENTER
        if (!show) return@forEachIndexed
        val isOver = ev.type == TripEventType.OVERSPEED || ev.type == TripEventType.SECTION_OVER
        add(
            MapMarker(
                id = "trip-m-ev-$idx",
                pos = LatLng(ev.lat, ev.lon),
                icon = if (isOver) CameraIcons.TRIP_ICON_OVERSPEED else CameraIcons.TRIP_ICON_EVENT,
                label = if (isOver) "超速 ${ev.speedKmh.roundToInt()}" else "",
                sortKey = if (isOver) 1f else 3f,
            )
        )
    }
}

@Composable
fun TripListScreen(
    trips: List<TripSummary>,
    backupMessage: String?,
    onSelectTrip: (TripSummary) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("旅程記錄", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onExportBackup, enabled = trips.isNotEmpty()) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("匯出")
                }
                TextButton(onClick = onImportBackup) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("匯入")
                }
            }

            backupMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (trips.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "尚無旅程記錄。\n啟動「開始守衛」行駛後，將自動記錄軌跡與經過的測速提醒。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(trips, key = { it.id }) { summary ->
                        TripSummaryCard(summary = summary, onClick = { onSelectTrip(summary) })
                    }
                    item { Spacer(Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripSummaryCard(summary: TripSummary, onClick: () -> Unit) {
    val dest = summary.destination
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTripTime(summary.startedAtMs),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (summary.abnormalEnd) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "異常結束（已自動恢復）",
                        tint = Color(0xFFF57C00),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (!summary.backedUp) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = "超過 15MB 自動備份上限，僅存於本機",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (!dest.isNullOrBlank()) {
                Text("目的地：$dest", style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("里程 ${formatDistance(summary.distanceM)}", style = MaterialTheme.typography.bodySmall)
                Text("移動 ${formatDuration(summary.movingDurationMs)}", style = MaterialTheme.typography.bodySmall)
                SpeedMetricText("均速", summary.avgMovingSpeedKmh, MaterialTheme.typography.bodySmall)
                SpeedMetricText("極速", summary.maxSpeedKmh, MaterialTheme.typography.bodySmall)
            }
            val overColor = if (summary.overspeedCount > 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "經過測速／區間 ${summary.cameraCount} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "超速警報 ${summary.overspeedCount} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = overColor,
                    fontWeight = if (summary.overspeedCount > 0) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripDetailOverlay(
    detail: TripDetail,
    onExportGpx: (TripDetail) -> Unit,
    onNavigate: (TripSummary) -> Unit,
    onDelete: (TripSummary) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var confirmDelete by remember { mutableStateOf(false) }
    val s = detail.summary
    val dest = s.destination

    Box(modifier = Modifier.fillMaxSize()) {
        // Top-left back button floating over map
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallFloatingActionButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回旅程列表")
            }
        }

        // Bottom trip summary & event details card
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatTripTime(s.startedAtMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!dest.isNullOrBlank()) {
                    Text("目的地：$dest", style = MaterialTheme.typography.bodyMedium)
                }
                if (s.abnormalEnd) {
                    Text("上次守衛未正常結束，系統已自動恢復記錄。", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF57C00))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("里程 ${formatDistance(s.distanceM)}")
                    Text("移動 ${formatDuration(s.movingDurationMs)}")
                    SpeedMetricText("均速", s.avgMovingSpeedKmh, LocalTextStyle.current)
                    SpeedMetricText("極速", s.maxSpeedKmh, LocalTextStyle.current)
                    Text("測速 ${s.cameraCount} 次")
                    Text(
                        "超速 ${s.overspeedCount} 次",
                        color = if (s.overspeedCount > 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (s.overspeedCount > 0) FontWeight.Bold else FontWeight.Normal,
                    )
                }

                SpeedColorLegend()

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onExportGpx(detail) }) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("匯出 GPX")
                    }
                    if (!dest.isNullOrBlank()) {
                        OutlinedButton(onClick = { onNavigate(s) }) {
                            Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("再次導航")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("刪除")
                    }
                }

                if (detail.events.isNotEmpty()) {
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(detail.events) { ev ->
                            EventRow(ev)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除此趟旅程？") },
            text = { Text("刪除後無法復原（除非先前已匯出備份或 GPX）。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(s)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("刪除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SpeedMetricText(
    label: String,
    speedKmh: Double,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(speedColor(speedKmh), CircleShape),
        )
        Text("$label ${speedKmh.roundToInt()} km/h", style = style)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedColorLegend() {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "時速",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (tier in SpeedTier.entries) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 6.dp)
                        .background(speedTierColor(tier), RoundedCornerShape(3.dp)),
                )
                Text(
                    tier.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "km/h",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventRow(ev: TripEvent) {
    val label = when (ev.type) {
        TripEventType.APPROACH -> "測速提醒"
        TripEventType.NEAR -> "近距提醒"
        TripEventType.OVERSPEED -> "超速警告"
        TripEventType.PASS -> "通過測速"
        TripEventType.SECTION_ENTER -> "進入區間"
        TripEventType.SECTION_OVER -> "區間超速"
        TripEventType.SECTION_EXIT -> "離開區間"
    }
    val isOver = ev.type == TripEventType.OVERSPEED || ev.type == TripEventType.SECTION_OVER
    val place = ev.camera?.address?.takeIf { it.isNotBlank() } ?: ev.sectionId.orEmpty()
    val limit = ev.limitKmh?.let { "限 $it" } ?: ""
    val speed = "${ev.speedKmh.roundToInt()} km/h"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTimeOfDay(ev.tMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (isOver) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(speedColor(ev.speedKmh, isOver), CircleShape),
            )
            Text(
                "$speed${if (limit.isNotEmpty()) " / $limit" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isOver) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (place.isNotBlank()) {
            Text(
                place,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

