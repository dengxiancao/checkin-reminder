// 职责：打卡点列表页，展示全部打卡点并支持启用开关、编辑与删除
package com.example.checkin.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.checkin.R
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.geofence.FenceState
import com.example.checkin.ui.PointListUiState
import java.util.Locale

/**
 * 打卡点列表页。
 *
 * 顶部是后台监测总开关；列表项点击进入编辑页，
 * 右侧开关控制启用状态，末尾删除按钮带二次确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointListScreen(
    uiState: PointListUiState,
    onAdd: () -> Unit,
    onEdit: (CheckinPoint) -> Unit,
    onDelete: (CheckinPoint) -> Unit,
    onToggleEnabled: (CheckinPoint) -> Unit,
    onToggleMonitoring: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<CheckinPoint?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.point_list_title)) })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            MonitoringBar(
                enabled = uiState.monitoringEnabled,
                enabledCount = uiState.enabledCount,
                onToggle = onToggleMonitoring
            )

            if (uiState.points.isEmpty()) {
                EmptyHint(text = stringResource(R.string.point_list_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = uiState.points, key = { it.id }) { point ->
                        PointCard(
                            point = point,
                            fenceState = uiState.fenceStateOf(point.id),
                            distanceMeters = uiState.fenceStates[point.id]?.distanceMeters,
                            onEdit = { onEdit(point) },
                            onDelete = { pendingDelete = point },
                            onToggleEnabled = { onToggleEnabled(point) }
                        )
                    }
                }
            }
        }
    }

    // 删除确认（提示会一并删除打卡记录）
    pendingDelete?.let { point ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.point_delete_confirm, point.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(point)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/** 顶部后台监测总开关 */
@Composable
private fun MonitoringBar(
    enabled: Boolean,
    enabledCount: Int,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (enabled) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) {
                        stringResource(R.string.monitoring_on, enabledCount)
                    } else {
                        stringResource(R.string.monitoring_off)
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (enabled) {
                        stringResource(R.string.home_stop_monitoring)
                    } else {
                        stringResource(R.string.home_start_monitoring)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = { onToggle() })
        }
    }
}

/** 单个打卡点卡片：整卡可点击进入编辑 */
@Composable
private fun PointCard(
    point: CheckinPoint,
    /** 围栏实时状态；null 表示尚未判定（服务未运行或刚启动） */
    fenceState: FenceState?,
    /** 到打卡点的实时距离（米） */
    distanceMeters: Double?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit
) {
    // 未启用时整体降低透明度，状态一眼可辨
    val container = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 打卡点颜色（未设置时用主题色）
                val dotColor = parseColorOrNull(point.color)
                    ?: MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(dotColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = point.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.point_radius_format, point.radiusMeters) +
                            " · " +
                            stringResource(
                                if (point.enabled) R.string.point_enabled else R.string.point_disabled
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    // 坐标用等宽字体，便于核对
                    Text(
                        text = String.format(
                            Locale.CHINA,
                            "%.5f, %.5f",
                            point.latitude,
                            point.longitude
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 围栏实时状态（由前台服务写入，UI 订阅展示）
                    if (point.enabled && fenceState != null && fenceState != FenceState.UNKNOWN) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(fenceStateColor(fenceState), CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = fenceStateLabel(fenceState) +
                                    (distanceMeters?.let { "（${it.toInt()} 米）" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = fenceStateColor(fenceState)
                            )
                        }
                    }
                }

                Switch(checked = point.enabled, onCheckedChange = { onToggleEnabled() })
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.action_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete)
                    )
                }
            }
        }
    }
}

/** 把 "#AARRGGBB" / "#RRGGBB" 解析为 Compose Color，失败返回 null */
internal fun parseColorOrNull(raw: String?): Color? {
    val text = raw?.trim()?.removePrefix("#") ?: return null
    return runCatching {
        when (text.length) {
            6 -> Color(0xFF000000L.toInt() or text.toLong(16).toInt())
            8 -> Color(text.toLong(16).toInt())
            else -> null
        }
    }.getOrNull()
}

/** 围栏状态对应的展示文案 */
@Composable
private fun fenceStateLabel(state: FenceState): String = stringResource(
    when (state) {
        FenceState.INSIDE -> R.string.fence_state_inside
        FenceState.NEAR -> R.string.fence_state_near
        FenceState.LEFT -> R.string.fence_state_left
        FenceState.OUTSIDE -> R.string.fence_state_outside
        FenceState.UNKNOWN -> R.string.fence_state_unknown
    }
)

/** 围栏状态对应的指示色：进入=主色，接近=警示色，离开=第三色，远离=灰 */
@Composable
private fun fenceStateColor(state: FenceState): Color = when (state) {
    FenceState.INSIDE -> MaterialTheme.colorScheme.primary
    FenceState.NEAR -> MaterialTheme.colorScheme.tertiary
    FenceState.LEFT -> MaterialTheme.colorScheme.error
    FenceState.OUTSIDE, FenceState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 空状态提示 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp)
        )
    }
}
