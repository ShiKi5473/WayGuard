package com.wayguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wayguard.core.trip.SpeedTier
import com.wayguard.core.trip.TripAnalytics
import com.wayguard.core.trip.TripDayGroup
import com.wayguard.core.trip.TripDetail
import com.wayguard.core.trip.TripEvent
import com.wayguard.core.trip.TripEventType
import com.wayguard.core.trip.TripFilter
import com.wayguard.core.trip.TripPeriod
import com.wayguard.core.trip.TripSort
import com.wayguard.core.trip.TripStats
import com.wayguard.core.trip.TripSummary
import com.wayguard.core.trip.TripTotals
import com.waymap.geo.LatLng
import com.waymap.ui.MapLine
import com.waymap.ui.MapMarker
import kotlin.math.roundToInt

internal val OverspeedRed = Color(0xFFD32F2F)
internal val WarningOrange = Color(0xFFF57C00)

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
    if (overspeed) OverspeedRed else speedTierColor(SpeedTier.fromSpeed(speedKmh))

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

private const val EVENT_MARKER_PREFIX = "trip-m-ev-"

/** Index into [TripDetail.events] of a marker built by [tripMapMarkers], or null. */
fun tripEventIndexOfMarker(markerId: String): Int? =
    markerId.takeIf { it.startsWith(EVENT_MARKER_PREFIX) }?.removePrefix(EVENT_MARKER_PREFIX)?.toIntOrNull()

/** Builds start/end and key alert markers (tappable) for a trip detail. */
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
        val isOver = ev.isOverspeed
        add(
            MapMarker(
                id = "$EVENT_MARKER_PREFIX$idx",
                pos = LatLng(ev.lat, ev.lon),
                icon = if (isOver) CameraIcons.TRIP_ICON_OVERSPEED else CameraIcons.TRIP_ICON_EVENT,
                label = if (isOver) "超速 ${ev.speedKmh.roundToInt()}" else "",
                sortKey = if (isOver) 1f else 3f,
                tappable = true,
            )
        )
    }
}

internal val TripEvent.isOverspeed: Boolean
    get() = type == TripEventType.OVERSPEED || type == TripEventType.SECTION_OVER

/** Host callbacks for the trip browser. */
data class TripActions(
    val onLoadDetail: suspend (TripSummary) -> TripDetail?,
    val onDelete: (List<String>) -> Unit,
    val onExportGpx: (TripDetail) -> Unit,
    val onNavigate: (TripSummary) -> Unit,
    val onExportBackup: () -> Unit,
    val onImportBackup: () -> Unit,
    val onDismissMessage: () -> Unit,
)

private data class TripListQuery(
    val text: String = "",
    val filter: TripFilter = TripFilter.ALL,
    val sort: TripSort = TripSort.NEWEST,
    val period: TripPeriod = TripPeriod.ALL,
) {
    val isDefault: Boolean get() = text.isBlank() && filter == TripFilter.ALL && period == TripPeriod.ALL
}

private val TripListQuerySaver = listSaver<TripListQuery, String>(
    save = { listOf(it.text, it.filter.name, it.sort.name, it.period.name) },
    restore = {
        TripListQuery(
            text = it[0],
            filter = runCatching { TripFilter.valueOf(it[1]) }.getOrDefault(TripFilter.ALL),
            sort = runCatching { TripSort.valueOf(it[2]) }.getOrDefault(TripSort.NEWEST),
            period = runCatching { TripPeriod.valueOf(it[3]) }.getOrDefault(TripPeriod.ALL),
        )
    },
)

/**
 * Trip browser root: the searchable/filterable list, and a full trip page (own map, speed
 * chart, playback, events) for the selected trip. Prev/next follow the current list order.
 */
@Composable
fun TripBrowser(
    trips: List<TripSummary>,
    message: String?,
    initialTripId: String?,
    actions: TripActions,
    onClose: () -> Unit,
) {
    var openId by rememberSaveable { mutableStateOf(initialTripId) }
    var query by rememberSaveable(stateSaver = TripListQuerySaver) { mutableStateOf(TripListQuery()) }
    val visible = remember(trips, query) {
        TripAnalytics.query(trips, query.text, query.filter, query.sort, query.period)
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onDismissMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val open = openId?.let { id -> trips.firstOrNull { it.id == id } }
        if (open != null) {
            val order = if (visible.any { it.id == open.id }) visible else trips
            val idx = order.indexOfFirst { it.id == open.id }
            TripDetailScreen(
                summary = open,
                prev = order.getOrNull(idx - 1),
                next = order.getOrNull(idx + 1),
                actions = actions,
                onOpenTrip = { openId = it.id },
                onBack = { openId = null },
            )
        } else {
            TripListScreen(
                allCount = trips.size,
                visible = visible,
                query = query,
                onQueryChange = { query = it },
                onOpen = { openId = it.id },
                actions = actions,
                onClose = onClose,
            )
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun TripListScreen(
    allCount: Int,
    visible: List<TripSummary>,
    query: TripListQuery,
    onQueryChange: (TripListQuery) -> Unit,
    onOpen: (TripSummary) -> Unit,
    actions: TripActions,
    onClose: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var searchOpen by rememberSaveable { mutableStateOf(query.text.isNotEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()

    // Drop selections that are no longer visible (deleted, filtered out).
    LaunchedEffect(visible) {
        val ids = visible.mapTo(HashSet()) { it.id }
        if (!ids.containsAll(selected)) selected = selected intersect ids
    }

    BackHandler {
        when {
            selecting -> selected = emptySet()
            searchOpen -> {
                searchOpen = false
                onQueryChange(query.copy(text = ""))
            }
            else -> onClose()
        }
    }

    val totals = remember(visible) { TripAnalytics.totals(visible) }
    val groups = remember(visible, query.sort) {
        if (query.sort.groupsByDate) TripAnalytics.groupByDay(visible) else emptyList()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        if (selecting) {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { selected = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "取消選取")
                    }
                },
                title = { Text("已選 ${selected.size} 趟") },
                actions = {
                    IconButton(onClick = { selected = visible.mapTo(HashSet()) { it.id } }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "全選")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "刪除選取的旅程", tint = MaterialTheme.colorScheme.error)
                    }
                },
            )
        } else {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text("旅程記錄")
                        if (allCount > 0) {
                            Text(
                                "共 $allCount 趟，長按可多選",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searchOpen) onQueryChange(query.copy(text = ""))
                        searchOpen = !searchOpen
                    }) {
                        Icon(
                            if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searchOpen) "關閉搜尋" else "搜尋",
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("匯出全部備份（.zip）") },
                                leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) },
                                enabled = allCount > 0,
                                onClick = { menuOpen = false; actions.onExportBackup() },
                            )
                            DropdownMenuItem(
                                text = { Text("匯入備份") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = { menuOpen = false; actions.onImportBackup() },
                            )
                        }
                    }
                },
            )
        }

        if (searchOpen) {
            OutlinedTextField(
                value = query.text,
                onValueChange = { onQueryChange(query.copy(text = it)) },
                singleLine = true,
                placeholder = { Text("目的地或日期，例如 台中、11/17") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        QueryChips(query = query, onQueryChange = onQueryChange)

        if (allCount == 0) {
            EmptyHint("尚無旅程記錄。\n啟動「開始守衛」行駛後，將自動記錄軌跡與經過的測速提醒。")
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "totals") { TotalsCard(totals, query.period) }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("沒有符合條件的旅程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!query.isDefault) {
                            TextButton(onClick = { onQueryChange(TripListQuery(sort = query.sort)) }) { Text("清除條件") }
                        }
                    }
                }
            }
            val onCardClick = { s: TripSummary ->
                if (selecting) selected = selected.toggle(s.id) else onOpen(s)
            }
            val onCardLongClick = { s: TripSummary -> selected = selected.toggle(s.id) }
            if (groups.isNotEmpty()) {
                for (g in groups) {
                    stickyHeader(key = "h-${g.date}") { DayHeader(g) }
                    items(g.trips, key = { it.id }) { s ->
                        TripSummaryCard(
                            summary = s,
                            showDate = false,
                            selecting = selecting,
                            selected = s.id in selected,
                            onClick = { onCardClick(s) },
                            onLongClick = { onCardLongClick(s) },
                        )
                    }
                }
            } else {
                items(visible, key = { it.id }) { s ->
                    TripSummaryCard(
                        summary = s,
                        showDate = true,
                        selecting = selecting,
                        selected = s.id in selected,
                        onClick = { onCardClick(s) },
                        onLongClick = { onCardLongClick(s) },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除 $n 趟旅程？") },
            text = { Text("刪除後無法復原（除非先前已匯出備份或 GPX）。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        actions.onDelete(selected.toList())
                        selected = emptySet()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun QueryChips(query: TripListQuery, onQueryChange: (TripListQuery) -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in TripPeriod.entries) {
                FilterChip(
                    selected = query.period == p,
                    onClick = { onQueryChange(query.copy(period = p)) },
                    label = { Text(p.label) },
                )
            }
            Spacer(Modifier.width(4.dp))
            Box {
                AssistChip(
                    onClick = { sortOpen = true },
                    label = { Text(query.sort.label) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序", modifier = Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    for (s in TripSort.entries) {
                        DropdownMenuItem(
                            text = {
                                Text(s.label, fontWeight = if (s == query.sort) FontWeight.Bold else FontWeight.Normal)
                            },
                            onClick = {
                                sortOpen = false
                                onQueryChange(query.copy(sort = s))
                            },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (f in TripFilter.entries) {
                FilterChip(
                    selected = query.filter == f,
                    onClick = { onQueryChange(query.copy(filter = f)) },
                    label = { Text(f.label) },
                )
            }
        }
    }
}

@Composable
private fun TotalsCard(t: TripTotals, period: TripPeriod) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${period.label}統計",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("趟數", "${t.count}", Modifier.weight(1f))
                StatCell("里程", formatDistance(t.distanceM), Modifier.weight(1f))
                StatCell("移動時間", formatDuration(t.movingDurationMs), Modifier.weight(1.3f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("極速", "${t.maxSpeedKmh.roundToInt()} km/h", Modifier.weight(1f))
                StatCell("經過測速", "${t.cameraCount} 次", Modifier.weight(1f))
                StatCell(
                    "超速",
                    if (t.distanceM > 0.0) "${t.overspeedCount} 次（每百公里 ${"%.1f".format(t.overspeedPer100Km)}）"
                    else "${t.overspeedCount} 次",
                    Modifier.weight(1.3f),
                    valueColor = if (t.overspeedCount > 0) OverspeedRed else null,
                )
            }
        }
    }
}

@Composable
internal fun StatCell(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Column(modifier = modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor ?: Color.Unspecified,
        )
    }
}

@Composable
private fun DayHeader(g: TripDayGroup) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(g.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                "${g.totals.count} 趟 · ${formatDistance(g.totals.distanceM)}" +
                    if (g.totals.overspeedCount > 0) " · 超速 ${g.totals.overspeedCount}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (g.totals.overspeedCount > 0) OverspeedRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TripSummaryCard(
    summary: TripSummary,
    showDate: Boolean,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val dest = summary.destination
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onLongClick() })
                Spacer(Modifier.width(4.dp))
            }
            // Max-speed tier stripe for at-a-glance scanning.
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 44.dp)
                    .background(speedColor(summary.maxSpeedKmh), RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val range = "${formatHourMinute(summary.startedAtMs)} → ${formatHourMinute(summary.endedAtMs)}"
                    Text(
                        if (showDate) "${formatTripTime(summary.startedAtMs).take(10)}  $range" else range,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (summary.overspeedCount > 0) {
                        Surface(color = OverspeedRed, shape = RoundedCornerShape(10.dp)) {
                            Text(
                                "超速 ${summary.overspeedCount}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (summary.abnormalEnd) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "異常結束（已自動恢復）",
                            tint = WarningOrange,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(2.dp))
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
                    Text(
                        "目的地：$dest",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(formatDistance(summary.distanceM), style = MaterialTheme.typography.bodySmall)
                    Text("移動 ${formatDuration(summary.movingDurationMs)}", style = MaterialTheme.typography.bodySmall)
                    SpeedMetricText("均速", summary.avgMovingSpeedKmh, MaterialTheme.typography.bodySmall)
                    SpeedMetricText("極速", summary.maxSpeedKmh, MaterialTheme.typography.bodySmall)
                    Text(
                        "測速 ${summary.cameraCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SpeedMetricText(
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
internal fun SpeedColorLegend() {
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

internal fun eventLabel(type: TripEventType): String = when (type) {
    TripEventType.APPROACH -> "測速提醒"
    TripEventType.NEAR -> "近距提醒"
    TripEventType.OVERSPEED -> "超速警告"
    TripEventType.PASS -> "通過測速"
    TripEventType.SECTION_ENTER -> "進入區間"
    TripEventType.SECTION_OVER -> "區間超速"
    TripEventType.SECTION_EXIT -> "離開區間"
}

@Composable
internal fun EventRow(ev: TripEvent, selected: Boolean, onClick: () -> Unit) {
    val isOver = ev.isOverspeed
    val place = ev.camera?.address?.takeIf { it.isNotBlank() } ?: ev.sectionId.orEmpty()
    val limit = (ev.limitKmh ?: ev.camera?.limitKmh)?.let { "限 $it" } ?: ""
    val avg = ev.sectionAvgKmh?.let { "均 ${it.roundToInt()}" }
    val speed = "${ev.speedKmh.roundToInt()} km/h"
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatTimeOfDay(ev.tMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    eventLabel(ev.type),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isOver) OverspeedRed else MaterialTheme.colorScheme.onSurface,
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(speedColor(ev.speedKmh, isOver), CircleShape),
                )
                Text(
                    listOfNotNull(speed, avg, limit.ifEmpty { null }).joinToString(" / "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOver) OverspeedRed else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (place.isNotBlank()) {
                Text(
                    place,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
