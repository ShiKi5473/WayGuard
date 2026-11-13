package com.wayguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wayguard.data.CameraData
import com.wayguard.data.DataOrigin
import com.wayguard.settings.GuardSettings
import kotlin.math.roundToInt

/**
 * Full-screen settings page drawn OVER the map (the map stays composed; see WayMap's
 * note on disposing it). Every control persists through [MainActions.onSaveSettings];
 * the guard service picks changes up live.
 */
@Composable
fun SettingsScreen(
    settings: GuardSettings,
    data: CameraData,
    perms: Permissions,
    syncing: Boolean,
    syncMessage: String?,
    actions: MainActions,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val save = actions.onSaveSettings

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("設定", style = MaterialTheme.typography.titleLarge)
            }

            Group("提醒距離") {
                IntSlider("50 km/h 以下", settings.approachSlowM, 100..1000, 50, "m") { v -> save { it.copy(approachSlowM = v) } }
                IntSlider("50–80 km/h", settings.approachMidM, 100..1000, 50, "m") { v -> save { it.copy(approachMidM = v) } }
                IntSlider("80 km/h 以上", settings.approachFastM, 200..1500, 50, "m") { v -> save { it.copy(approachFastM = v) } }
                IntSlider("近距再提醒", settings.nearM, 50..300, 25, "m") { v -> save { it.copy(nearM = v) } }
            }

            Group("超速警示") {
                IntSlider("容許超過速限", settings.overspeedToleranceKmh, 0..20, 1, "km/h") { v ->
                    save { it.copy(overspeedToleranceKmh = v) }
                }
                SwitchRow("超速時嗶聲（每 3 秒）", settings.beepOnOverspeed) { v -> save { it.copy(beepOnOverspeed = v) } }
            }

            Group("提醒類型") {
                SwitchRow("固定測速照相", settings.alertSpeed) { v -> save { it.copy(alertSpeed = v) } }
                SwitchRow("闖紅燈照相", settings.alertRedLight) { v -> save { it.copy(alertRedLight = v) } }
                SwitchRow("區間測速", settings.alertSection) { v -> save { it.copy(alertSection = v) } }
                SwitchRow("自訂點位／流動測速", settings.alertUser) { v -> save { it.copy(alertUser = v) } }
                SwitchRow("過濾橫向交叉路點位（依道路走向）", settings.roadAxisFilter) { v -> save { it.copy(roadAxisFilter = v) } }
                SwitchRow("語音播報", settings.voiceEnabled) { v -> save { it.copy(voiceEnabled = v) } }
            }

            Group("懸浮視窗") {
                SwitchRow("守衛時顯示懸浮面板", settings.overlayEnabled && perms.overlay) { v ->
                    if (v && !perms.overlay) actions.onOpenOverlaySettings()
                    else save { it.copy(overlayEnabled = v) }
                }
                if (!perms.overlay) Text("需要先允許「顯示在其他應用程式上層」。", style = MaterialTheme.typography.bodySmall)
                PercentSlider("不透明度", settings.overlayAlpha) { v -> save { it.copy(overlayAlpha = v) } }
            }

            Group("測速點資料") {
                val ds = data.dataset
                if (ds == null) {
                    Text("尚未載入資料")
                } else {
                    Text("版本 ${ds.version}（${if (data.origin == DataOrigin.DOWNLOADED) "已下載" else "App 內建"}）")
                    Text("資料產生：${formatIsoLocal(ds.generatedAt)}")
                    Text("測速點 ${ds.cameras.size} 個、區間 ${ds.sections.size} 個；自訂 ${data.active.size + data.suppressed.size - ds.cameras.size} 個")
                }
                Text("上次檢查更新：${formatEpochLocal(settings.lastSyncCheckMs)}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = actions.onSyncNow, enabled = !syncing) { Text("立即更新") }
                    if (syncing) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                syncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                SwitchRow("僅在 Wi-Fi 下自動更新（每週）", settings.syncWifiOnly) { v -> save { it.copy(syncWifiOnly = v) } }
            }

            Group("誤報標記") {
                Text("已標記 ${data.suppressed.size} 個點位不提醒。")
                TextButton(onClick = actions.onClearSuppressed, enabled = data.suppressed.isNotEmpty()) { Text("全部清除") }
            }

            Group("關於") {
                Text("資料來源：內政部警政署「測速執法設置點」（政府資料開放平臺，依政府資料開放授權條款第 1 版使用），每週自動更新。")
                Text("地圖：OpenFreeMap / © OpenStreetMap contributors。")
                Text(
                    "提醒僅供參考，實際設置以現場為準；請遵守速限、注意路況，行駛中請勿操作手機。",
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Slider that saves once on release instead of on every drag tick. */
@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, step: Int, unit: String, onChange: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val shown = (v / step).roundToInt() * step
    Text("$label：$shown $unit")
    Slider(
        value = v,
        onValueChange = { v = it },
        onValueChangeFinished = { onChange(shown.coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
    )
}

@Composable
private fun PercentSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Text("$label：${(v * 100).roundToInt()}%")
    Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onChange(v) }, valueRange = 0.3f..1f)
}
