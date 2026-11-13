package com.wayguard.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wayguard.BuildConfig
import com.wayguard.core.engine.AlertText
import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.SpeedCamera
import com.wayguard.update.AppUpdateState
import com.wayguard.update.AvailableUpdate
import com.wayguard.update.InstallPauseReason
import com.waymap.geo.LatLng
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Details of a tapped camera with false-alarm / delete actions. */
@Composable
fun CameraDetailDialog(
    camera: SpeedCamera,
    suppressed: Boolean,
    onSuppress: () -> Unit,
    onUnsuppress: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isUser = CameraKind.MOBILE in camera.kinds || CameraKind.CUSTOM in camera.kinds
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AlertText.kindName(camera)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val place = listOf(camera.city, camera.district, camera.address).filter { it.isNotBlank() }.joinToString(" ")
                if (place.isNotBlank()) Text(place, fontWeight = FontWeight.Bold)
                Text("速限：${camera.limitKmh?.let { "$it km/h" } ?: "未提供"}")
                Text("方向：${formatHeadings(camera.headings)}")
                if (camera.headings.isNotEmpty() && camera.headingToleranceDeg != null) {
                    // Estimated from a destination name (往大溪方向): only the opposite way is ruled out.
                    Text("（依方向文字中的地名推算，只排除相反方向）")
                }
                if (camera.directionText.isNotBlank()) Text("原始方向：${camera.directionText}")
                if (CameraKind.RED_LIGHT in camera.kinds) Text("兼闖紅燈照相")
                when (camera.loc) {
                    "mismatch" -> Text("⚠️ 位置不符（公告座標與地址道路或行政區差距過大）")
                    "suspect" -> Text("⚠️ 位置可疑（公告座標與地址道路或行政區略有偏差）")
                }
                Text(if (isUser) "來源：自訂點位（僅存在本機）" else "來源：內政部警政署開放資料")
                if (suppressed) Text("已標記為誤報，不會提醒。")
            }
        },
        confirmButton = {
            when {
                isUser -> TextButton(onClick = onDelete) { Text("刪除此點") }
                suppressed -> TextButton(onClick = onUnsuppress) { Text("取消誤報標記") }
                else -> TextButton(onClick = onSuppress) { Text("標記誤報／已拆除") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

private val DIRECTIONS = listOf("北" to 0, "東北" to 45, "東" to 90, "東南" to 135, "南" to 180, "西南" to 225, "西" to 270, "西北" to 315)

/**
 * Adds a user point at [pos]. [currentHeading] (the direction of travel, when known)
 * is offered as a one-tap direction.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddCameraDialog(
    pos: LatLng,
    currentHeading: Double?,
    onAdd: (SpeedCamera) -> Unit,
    onDismiss: () -> Unit,
) {
    var mobile by remember { mutableStateOf(true) }
    var limitText by remember { mutableStateOf("") }
    /** null = both ways. */
    var heading by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增自訂點位") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%.5f, %.5f".format(pos.lat, pos.lon))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mobile, onClick = { mobile = true }, label = { Text("流動測速") })
                    FilterChip(selected = !mobile, onClick = { mobile = false }, label = { Text("自訂提醒") })
                }
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { v -> limitText = v.filter(Char::isDigit).take(3) },
                    label = { Text("速限 km/h（可空白）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("提醒方向")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(selected = heading == null, onClick = { heading = null }, label = { Text("雙向") })
                    currentHeading?.let { h ->
                        val r = (h.roundToInt() % 360 + 360) % 360
                        FilterChip(selected = heading == r, onClick = { heading = r }, label = { Text("目前行進方向") })
                    }
                    DIRECTIONS.forEach { (name, deg) ->
                        FilterChip(selected = heading == deg, onClick = { heading = deg }, label = { Text("往$name") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onAdd(
                    SpeedCamera(
                        id = "user-${UUID.randomUUID()}",
                        lat = pos.lat,
                        lon = pos.lon,
                        limitKmh = limitText.toIntOrNull()?.takeIf { it in 10..130 },
                        headings = listOfNotNull(heading),
                        kinds = setOf(if (mobile) CameraKind.MOBILE else CameraKind.CUSTOM),
                        address = if (mobile) "流動測速（自訂）" else "自訂提醒點",
                    )
                )
            }) { Text("新增") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** GitHub release check / download / self-install prompt. */
@Composable
fun AppUpdateDialog(
    state: AppUpdateState,
    guardRunning: Boolean,
    autoUpdateEnabled: Boolean,
    hasInstallPermission: Boolean,
    onDownload: (AvailableUpdate) -> Unit,
    onInstall: (AvailableUpdate, File) -> Unit,
    onCancelDownload: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    onRelaunchConfirm: (Intent) -> Unit,
    onEnableAutoUpdate: (Boolean) -> Unit,
    onSkipVersion: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is AppUpdateState.Available -> {
            if (!state.showPrompt) return
            val rel = state.release
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("發現新版本 v${rel.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("目前版本：v${BuildConfig.VERSION_NAME} → 最新版本：v${rel.version}", fontWeight = FontWeight.Bold)
                        if (rel.sizeBytes > 0L) {
                            Text("安裝檔大小：${formatBytes(rel.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (rel.notes.isNotBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(rel.notes, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text("可從 GitHub Releases 下載新版 APK 並更新 App。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!autoUpdateEnabled) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onEnableAutoUpdate(true) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = false, onCheckedChange = { onEnableAutoUpdate(it) })
                                Text("之後自動檢查並更新 App", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onDownload(rel) }) { Text("下載並更新") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { onSkipVersion(rel.version) }) { Text("略過此版") }
                        TextButton(onClick = onDismiss) { Text("稍後再說") }
                    }
                },
            )
        }

        is AppUpdateState.Downloading -> {
            if (!state.showPrompt) return
            val total = state.totalBytes
            val downloaded = state.downloadedBytes
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("正在下載更新 v${state.release.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (total > 0L) {
                            val fraction = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            val pct = ((downloaded * 100L) / total).coerceIn(0L, 100L)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                            Text("$pct%（${formatBytes(downloaded)} / ${formatBytes(total)}）", style = MaterialTheme.typography.bodySmall)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("已下載 ${formatBytes(downloaded)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("背景下載") }
                },
                dismissButton = {
                    TextButton(onClick = onCancelDownload) { Text("取消下載") }
                },
            )
        }

        is AppUpdateState.Verifying -> {
            if (!state.showPrompt) return
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("正在驗證更新檔") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在驗證 SHA-256 雜湊與 APK 簽章…", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("背景處理") }
                },
            )
        }

        is AppUpdateState.ReadyToInstall -> {
            if (!state.showPrompt) return
            val needsPerm = !hasInstallPermission || state.reason == InstallPauseReason.PERMISSION_REQUIRED
            val guardBlocked = guardRunning || state.reason == InstallPauseReason.GUARDING_ACTIVE
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("準備安裝 v${state.release.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("更新檔已下載並通過驗證。")
                        when {
                            guardBlocked -> Text("測速守衛執行中，請先停止守衛後再進行安裝。", color = MaterialTheme.colorScheme.error)
                            needsPerm -> Text("需要允許「安裝未知應用程式」權限才能更新，授權後返回 App 即可自動安裝。")
                            else -> Text("點擊「立即安裝」即可開始更新。")
                        }
                    }
                },
                confirmButton = {
                    when {
                        guardBlocked -> TextButton(onClick = {}, enabled = false) { Text("守衛中無法安裝") }
                        needsPerm -> TextButton(onClick = onOpenInstallSettings) { Text("前往授權") }
                        else -> TextButton(onClick = { onInstall(state.release, state.apkFile) }) { Text("立即安裝") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("稍後安裝") }
                },
            )
        }

        is AppUpdateState.Installing -> {
            if (!state.showPrompt) return
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("正在安裝 v${state.release.version}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在呼叫系統安裝程序…", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("關閉") }
                },
            )
        }

        is AppUpdateState.PendingUserAction -> {
            if (!state.showPrompt) return
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("確認更新 v${state.release.version}") },
                text = { Text("請在系統跳出的確認視窗中點擊「更新」以完成安裝；若未看到視窗可點擊下方按鈕重新開啟。") },
                confirmButton = {
                    TextButton(onClick = { onRelaunchConfirm(state.confirmIntent) }) { Text("開啟安裝視窗") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("關閉") }
                },
            )
        }

        is AppUpdateState.Failed -> {
            if (!state.showPrompt) return
            val rel = state.release
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("更新失敗") },
                text = { Text(state.message) },
                confirmButton = {
                    if (rel != null) {
                        TextButton(onClick = { onDownload(rel) }) { Text("重試") }
                    } else {
                        TextButton(onClick = onDismiss) { Text("確定") }
                    }
                },
                dismissButton = {
                    if (rel != null) {
                        TextButton(onClick = onDismiss) { Text("關閉") }
                    }
                },
            )
        }

        AppUpdateState.Idle, AppUpdateState.Checking, is AppUpdateState.UpToDate -> Unit
    }
}
