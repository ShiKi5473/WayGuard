package com.wayguard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Permission checklist floating over the map until the required steps are done. */
@Composable
fun SetupScreen(
    perms: Permissions,
    needOverlay: Boolean,
    onRequestLocation: () -> Unit,
    onOpenOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    dismissed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only location is required. Notifications, battery and overlay are listed as
    // rows but don't keep the card on screen once location is granted.
    val requiredDone = perms.location
    AnimatedVisibility(visible = !requiredDone || (!dismissed && !perms.allDone(needOverlay)), modifier = modifier) {
        Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("設定步驟", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (requiredDone) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "關閉提示")
                        }
                    }
                }
                SetupRow(perms.location, "1. 精確定位權限（必要）", "授權", onRequestLocation)
                SetupRow(perms.notifications, "2. 通知權限（顯示守衛狀態）", "授權", onRequestLocation)
                SetupRow(perms.battery, "3. 電池最佳化豁免（長途行車不中斷）", "開啟", onRequestBattery)
                if (needOverlay) SetupRow(perms.overlay, "4. 懸浮視窗權限（疊在導航上顯示）", "開啟", onOpenOverlay)
            }
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, title: String, button: String, onClick: () -> Unit) {
    // Hide rows that are already done to keep the checklist short.
    AnimatedVisibility(visible = !done) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("•")
            Text(title, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

/** Snapshot of the permission-ish state the UI depends on. */
data class Permissions(
    val location: Boolean = false,
    val notifications: Boolean = false,
    val overlay: Boolean = false,
    val battery: Boolean = false,
    val installPackages: Boolean = false,
) {
    fun allDone(needOverlay: Boolean) = location && notifications && battery && (overlay || !needOverlay)
}
