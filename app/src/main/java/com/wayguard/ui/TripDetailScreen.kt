package com.wayguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wayguard.core.trip.PlaybackPosition
import com.wayguard.core.trip.SpeedTier
import com.wayguard.core.trip.TripAnalytics
import com.wayguard.core.trip.TripDetail
import com.wayguard.core.trip.TripEvent
import com.wayguard.core.trip.TripEventType
import com.wayguard.core.trip.TripStats
import com.wayguard.core.trip.TripSummary
import com.waymap.geo.LatLng
import com.waymap.ui.CenterRequest
import com.waymap.ui.CompassPlacement
import com.waymap.ui.FitRequest
import com.waymap.ui.MapMarker
import com.waymap.ui.UserLocationMode
import com.waymap.ui.WayMap
import com.waymap.ui.WayMapDefaults
import kotlin.math.roundToInt

private enum class DetailTab(val label: String) { OVERVIEW("概覽"), SPEED("速度與回放"), EVENTS("事件") }

private enum class EventFilter(val label: String) {
    ALL("全部"),
    OVER("超速"),
    CAMERA("測速"),
    SECTION("區間"),
    ;

    fun matches(e: TripEvent): Boolean = when (this) {
        ALL -> true
        OVER -> e.isOverspeed
        CAMERA -> e.type == TripEventType.APPROACH || e.type == TripEventType.NEAR ||
            e.type == TripEventType.OVERSPEED || e.type == TripEventType.PASS
        SECTION -> e.type == TripEventType.SECTION_ENTER || e.type == TripEventType.SECTION_OVER ||
            e.type == TripEventType.SECTION_EXIT
    }
}

private val PLAYBACK_RATES = listOf(10, 30, 60, 120)
private const val FOLLOW_RECENTER_MS = 1_000L
private const val FOCUS_ZOOM = 16.0

/** Full trip page: own map, overview statistics, speed chart with playback, and event list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    summary: TripSummary,
    prev: TripSummary?,
    next: TripSummary?,
    actions: TripActions,
    onOpenTrip: (TripSummary) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var detail by remember(summary.id) { mutableStateOf<TripDetail?>(null) }
    var loadFailed by remember(summary.id) { mutableStateOf(false) }
    LaunchedEffect(summary.id) {
        val d = actions.onLoadDetail(summary)
        if (d == null) loadFailed = true else detail = d
    }

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回旅程列表")
                }
            },
            title = {
                Column {
                    Text(formatTripTime(summary.startedAtMs), maxLines = 1)
                    Text(
                        summary.destination?.takeIf { it.isNotBlank() }?.let { "目的地：$it" }
                            ?: "${formatDistance(summary.distanceM)} · ${formatDuration(summary.endedAtMs - summary.startedAtMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            actions = {
                IconButton(onClick = { prev?.let(onOpenTrip) }, enabled = prev != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一趟")
                }
                IconButton(onClick = { next?.let(onOpenTrip) }, enabled = next != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一趟")
                }
            },
        )
        val d = detail
        when {
            loadFailed -> EmptyHint("無法讀取此趟旅程的軌跡檔。")
            d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            // Keyed by trip so map framing, playback and tab state reset when switching trips.
            else -> key(d.summary.id) { TripDetailBody(d, actions, onDeleted = onBack) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.TripDetailBody(detail: TripDetail, actions: TripActions, onDeleted: () -> Unit) {
    val points = detail.points
    val startMs = points.firstOrNull()?.tMs ?: detail.summary.startedAtMs
    val endMs = points.lastOrNull()?.tMs ?: detail.summary.endedAtMs

    var tab by rememberSaveable { mutableStateOf(DetailTab.OVERVIEW) }
    var mapExpanded by rememberSaveable { mutableStateOf(false) }
    var playT by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    var rate by rememberSaveable { mutableIntStateOf(30) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var focusIdx by remember { mutableStateOf<Int?>(null) }
    var centerReq by remember { mutableStateOf<CenterRequest?>(null) }

    val icons = remember { CameraIcons.tripIcons() }
    val lines = remember(detail) { tripMapLines(detail) }
    val baseMarkers = remember(detail) { tripMapMarkers(detail) }
    val frame = remember(detail) { TripStats.computeCenterAndZoom(points, detail.events) }
    val fitTo = remember(detail) {
        val all = points.map { LatLng(it.lat, it.lon) } + detail.events.map { LatLng(it.lat, it.lon) }
        if (all.isEmpty()) null else FitRequest(all, paddingDp = 40, animate = false)
    }
    val series = remember(detail) { TripAnalytics.speedSeries(points) }
    val playPos: PlaybackPosition? = playT?.let { TripAnalytics.positionAt(points, it) }

    val markers = buildList {
        addAll(baseMarkers)
        focusIdx?.let { detail.events.getOrNull(it) }?.let { ev ->
            add(MapMarker("trip-focus", LatLng(ev.lat, ev.lon), CameraIcons.TRIP_ICON_FOCUS, sortKey = 0.5f))
        }
        playPos?.let { p ->
            add(
                MapMarker(
                    "trip-playhead", p.pos, CameraIcons.TRIP_ICON_PLAYHEAD,
                    label = "${p.speedKmh.roundToInt()} km/h", sortKey = 5f,
                )
            )
        }
    }

    fun scrubTo(t: Long) {
        playing = false
        playT = t
        if (follow) TripAnalytics.positionAt(points, t)?.let { centerReq = CenterRequest(it.pos) }
    }

    fun focusEvent(idx: Int) {
        val ev = detail.events.getOrNull(idx) ?: return
        playing = false
        focusIdx = idx
        playT = ev.tMs.coerceIn(startMs, endMs)
        centerReq = CenterRequest(LatLng(ev.lat, ev.lon), FOCUS_ZOOM)
    }

    LaunchedEffect(playing) {
        if (!playing || points.size < 2) return@LaunchedEffect
        if (playT == null || playT!! >= endMs) playT = startMs
        var last = withFrameMillis { it }
        var lastCenter = 0L
        while (playing) {
            val now = withFrameMillis { it }
            val t = TripAnalytics.advancePlayback(points, playT ?: startMs, (now - last) * rate)
            last = now
            playT = t
            if (follow && now - lastCenter >= FOLLOW_RECENTER_MS) {
                TripAnalytics.positionAt(points, t)?.let { centerReq = CenterRequest(it.pos) }
                lastCenter = now
            }
            if (t >= endMs) playing = false
        }
    }

    Box(modifier = Modifier.fillMaxWidth().weight(if (mapExpanded) 1f else 0.48f)) {
        WayMap(
            modifier = Modifier.fillMaxSize(),
            icons = icons,
            markers = markers,
            lines = lines,
            centerOn = centerReq,
            fitTo = fitTo,
            styleUrl = if (isSystemInDarkTheme()) WayMapDefaults.STYLE_URL_DARK else WayMapDefaults.STYLE_URL,
            userLocation = UserLocationMode.HIDDEN,
            initialCenter = frame?.first ?: WayMapDefaults.TAIPEI,
            initialZoom = frame?.second ?: WayMapDefaults.ZOOM,
            compass = CompassPlacement(endDp = 12, bottomDp = 12),
            onMarkerTap = { id ->
                tripEventIndexOfMarker(id)?.let { idx ->
                    focusEvent(idx)
                    tab = DetailTab.EVENTS
                    mapExpanded = false
                }
            },
        )
        SmallFloatingActionButton(
            onClick = { mapExpanded = !mapExpanded },
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
        ) {
            Icon(
                if (mapExpanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (mapExpanded) "縮小地圖" else "放大地圖",
            )
        }
        if (mapExpanded && points.size >= 2) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
            ) {
                PlaybackControls(
                    startMs = startMs, endMs = endMs, playT = playT, playPos = playPos, playing = playing,
                    rate = rate, follow = follow,
                    onPlayToggle = { playing = !playing },
                    onScrub = ::scrubTo,
                    onRate = { rate = it },
                    onFollow = { follow = it },
                    compact = true,
                )
            }
        }
    }

    if (!mapExpanded) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            for (t in DetailTab.entries) {
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        Text(if (t == DetailTab.EVENTS) "${t.label}（${detail.events.size}）" else t.label, maxLines = 1)
                    },
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(0.52f)) {
            when (tab) {
                DetailTab.OVERVIEW -> OverviewTab(detail, actions, onDeleted)
                DetailTab.SPEED -> Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (points.size < 2) {
                        Text("軌跡點不足，無法繪製速度圖。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        TripSpeedChart(
                            series = series,
                            events = detail.events,
                            startMs = startMs,
                            endMs = endMs,
                            cursorMs = playT,
                            cursorSpeedKmh = playPos?.speedKmh,
                            onScrub = ::scrubTo,
                            modifier = Modifier.fillMaxWidth().height(170.dp),
                        )
                        PlaybackControls(
                            startMs = startMs, endMs = endMs, playT = playT, playPos = playPos, playing = playing,
                            rate = rate, follow = follow,
                            onPlayToggle = { playing = !playing },
                            onScrub = ::scrubTo,
                            onRate = { rate = it },
                            onFollow = { follow = it },
                            compact = false,
                        )
                        Text(
                            "點按或左右拖曳圖表可查看任一時刻的位置；紅點為超速警報、紅色虛線為該處速限。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                DetailTab.EVENTS -> EventsTab(detail.events, focusIdx, onFocus = ::focusEvent)
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    startMs: Long,
    endMs: Long,
    playT: Long?,
    playPos: PlaybackPosition?,
    playing: Boolean,
    rate: Int,
    follow: Boolean,
    onPlayToggle: () -> Unit,
    onScrub: (Long) -> Unit,
    onRate: (Int) -> Unit,
    onFollow: (Boolean) -> Unit,
    compact: Boolean,
) {
    val span = (endMs - startMs).coerceAtLeast(1L)
    Column(modifier = Modifier.padding(horizontal = if (compact) 10.dp else 0.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onPlayToggle) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "暫停回放" else "播放回放",
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (playPos != null && playT != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(speedColor(playPos.speedKmh), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${playPos.speedKmh.roundToInt()} km/h",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${formatTimeOfDay(playT)}（+${formatElapsed(playT - startMs)}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        "按播放鍵依時間重播這趟旅程",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Slider(
            value = playT?.let { ((it - startMs).toFloat() / span).coerceIn(0f, 1f) } ?: 0f,
            onValueChange = { f -> onScrub(startMs + (f * span).toLong()) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (r in PLAYBACK_RATES) {
                FilterChip(selected = rate == r, onClick = { onRate(r) }, label = { Text("${r}×") })
            }
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = follow, onClick = { onFollow(!follow) }, label = { Text("地圖跟隨") })
        }
    }
}

@Composable
private fun OverviewTab(detail: TripDetail, actions: TripActions, onDeleted: () -> Unit) {
    val s = detail.summary
    val dest = s.destination
    var confirmDelete by remember { mutableStateOf(false) }
    val tiers = remember(detail) { TripAnalytics.tierDurations(detail.points) }
    val tierTotal = tiers.values.sum()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (s.abnormalEnd) {
            Text("上次守衛未正常結束，系統已自動恢復記錄。", style = MaterialTheme.typography.bodySmall, color = WarningOrange)
        }
        if (!s.backedUp) {
            Text(
                "此趟超過 15 MB 自動備份上限，僅存於本機；建議匯出備份或 GPX。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            StatCell("出發", formatHourMinute(s.startedAtMs), Modifier.weight(1f))
            StatCell("抵達", formatHourMinute(s.endedAtMs), Modifier.weight(1f))
            StatCell("總時長", formatDuration(s.endedAtMs - s.startedAtMs), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            StatCell("里程", formatDistance(s.distanceM), Modifier.weight(1f))
            StatCell("移動", formatDuration(s.movingDurationMs), Modifier.weight(1f))
            StatCell("停留", formatDuration(TripAnalytics.stoppedDurationMs(s)), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            StatCell("均速", "${s.avgMovingSpeedKmh.roundToInt()} km/h", Modifier.weight(1f), speedColor(s.avgMovingSpeedKmh))
            StatCell("極速", "${s.maxSpeedKmh.roundToInt()} km/h", Modifier.weight(1f), speedColor(s.maxSpeedKmh))
            StatCell(
                "測速／超速",
                "${s.cameraCount}／${s.overspeedCount}",
                Modifier.weight(1f),
                if (s.overspeedCount > 0) OverspeedRed else null,
            )
        }

        if (tierTotal > 0L) {
            HorizontalDivider()
            Text("速度分布（依移動時間）", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().height(12.dp).background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(6.dp),
                ),
            ) {
                for ((tier, ms) in tiers) {
                    if (ms <= 0L) continue
                    Box(
                        Modifier
                            .weight(ms.toFloat())
                            .fillMaxHeight()
                            .background(speedTierColor(tier)),
                    )
                }
            }
            for ((tier, ms) in tiers) {
                if (ms <= 0L) continue
                TierRow(tier, ms, ms * 100.0 / tierTotal)
            }
        } else {
            SpeedColorLegend()
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { actions.onExportGpx(detail) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("匯出 GPX")
            }
            OutlinedButton(onClick = { actions.onNavigate(s) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (!dest.isNullOrBlank()) "再次導航" else "導航到終點")
            }
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("刪除")
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
                        actions.onDelete(listOf(s.id))
                        onDeleted()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TierRow(tier: SpeedTier, ms: Long, percent: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 8.dp).background(speedTierColor(tier), RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Text("${tier.label} km/h", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(96.dp))
        Text(formatDuration(ms), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text("${percent.roundToInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EventsTab(events: List<TripEvent>, focusIdx: Int?, onFocus: (Int) -> Unit) {
    var filter by rememberSaveable { mutableStateOf(EventFilter.ALL) }
    val counts = remember(events) { EventFilter.entries.associateWith { f -> events.count(f::matches) } }
    val shown = remember(events, filter) { events.withIndex().filter { filter.matches(it.value) } }
    val listState = rememberLazyListState()

    // A marker tapped on the map may point at an event the current filter hides.
    LaunchedEffect(focusIdx) {
        val idx = focusIdx ?: return@LaunchedEffect
        val ev = events.getOrNull(idx) ?: return@LaunchedEffect
        if (!filter.matches(ev)) filter = EventFilter.ALL
    }
    LaunchedEffect(focusIdx, shown) {
        val pos = shown.indexOfFirst { it.index == focusIdx }
        if (pos >= 0) listState.animateScrollToItem(pos)
    }

    if (events.isEmpty()) {
        EmptyHint("這趟旅程沒有經過測速或區間測速。")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (f in EventFilter.entries) {
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text("${f.label} ${counts[f] ?: 0}") },
                    enabled = f == EventFilter.ALL || (counts[f] ?: 0) > 0,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(shown, key = { it.index }) { (idx, ev) ->
                EventRow(ev, selected = idx == focusIdx, onClick = { onFocus(idx) })
            }
        }
    }
}
