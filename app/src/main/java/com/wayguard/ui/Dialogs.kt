package com.wayguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wayguard.core.engine.AlertText
import com.wayguard.core.model.CameraKind
import com.wayguard.core.model.SpeedCamera
import com.waymap.geo.LatLng
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
