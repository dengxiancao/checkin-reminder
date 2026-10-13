// 职责：Debug 页，展示实时坐标/精度/定位来源并提供开始停止定位按钮，供真机验证
package com.example.checkin.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.checkin.R
import com.example.checkin.geofence.LocationSnapshot
import com.example.checkin.ui.permission.LocationPermissionState
import com.example.checkin.util.PermissionDenialReason
import java.util.Locale

/**
 * 定位调试页。
 *
 * 用于真机验收三条标准：
 * 1. 能显示稳定的经纬度与精度（精度一般 < 50m）；
 * 2. 连续定位按设定间隔持续回调，点“停止”后不再回调（用回调日志的时间间隔验证）；
 * 3. 权限被拒绝时给出明确引导文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    uiState: DebugUiState,
    permission: LocationPermissionState,
    onRequestSingle: () -> Unit,
    onStartContinuous: () -> Unit,
    onStop: () -> Unit,
    onIntervalChange: (Long) -> Unit,
    onTimeoutChange: (Long) -> Unit,
    onClearLog: () -> Unit,
    onBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 1. 权限引导 ----------
            DebugPermissionCard(permission = permission)

            // ---------- 2. 当前定位结果 ----------
            CurrentLocationCard(
                snapshot = uiState.lastLocation,
                isLocating = uiState.isLocating,
                isContinuous = uiState.isContinuous,
                callbackCount = uiState.callbackCount
            )

            // ---------- 3. 错误提示 ----------
            uiState.lastError?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.debug_error_title, error.errorCode),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = error.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            if (error.errorInfo.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.debug_error_raw, error.errorInfo),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 4. 参数配置 ----------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.debug_config_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    // 连续定位间隔
                    Text(
                        text = stringResource(
                            R.string.debug_interval_label,
                            (uiState.intervalMs / 1000).toInt()
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = uiState.intervalMs.toFloat(),
                        onValueChange = { onIntervalChange(it.toLong()) },
                        valueRange = DebugViewModel.MIN_INTERVAL_MS.toFloat()..
                            DebugViewModel.MAX_INTERVAL_MS.toFloat(),
                        steps = 28,
                        // 定位进行中不允许改间隔，避免与正在运行的客户端状态不一致
                        enabled = !uiState.isLocating
                    )

                    // 单次定位超时
                    Text(
                        text = stringResource(
                            R.string.debug_timeout_label,
                            (uiState.timeoutMs / 1000).toInt()
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = uiState.timeoutMs.toFloat(),
                        onValueChange = { onTimeoutChange(it.toLong()) },
                        valueRange = DebugViewModel.MIN_TIMEOUT_MS.toFloat()..
                            DebugViewModel.MAX_TIMEOUT_MS.toFloat(),
                        steps = 10,
                        enabled = !uiState.isLocating
                    )

                    Text(
                        text = stringResource(R.string.debug_mode_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- 5. 操作按钮 ----------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRequestSingle,
                    enabled = !uiState.isLocating,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.debug_single))
                }

                Button(
                    onClick = { if (uiState.isLocating) onStop() else onStartContinuous() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (uiState.isLocating) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (uiState.isLocating) R.string.debug_stop else R.string.debug_start_continuous
                        )
                    )
                }
            }

            // ---------- 6. 回调日志 ----------
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.debug_log_title, uiState.callbackCount),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onClearLog, enabled = uiState.logs.isNotEmpty()) {
                    Text(stringResource(R.string.debug_log_clear))
                }
            }

            if (uiState.logs.isEmpty()) {
                Text(
                    text = stringResource(R.string.debug_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 日志本身放在已可滚动的外层 Column 中，这里用非滚动列表渲染
                uiState.logs.forEach { entry ->
                    LogRow(entry = entry)
                }
            }
        }
    }
}

/** 权限引导卡片：区分「未申请」「普通拒绝」「永久拒绝」给出不同文案 */
@Composable
private fun DebugPermissionCard(permission: LocationPermissionState) {
    val state = permission.state

    if (state.allGranted) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.debug_permission_ok),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    } else {
        PermissionGuideCard(permission)
    }
}

/**
 * 未全部授权时的引导卡片。
 *
 * 注意：这里刻意拆成独立 Composable 而**不用提前返回**（`return` / `return@Column`）。
 * Compose 编译器对「@Composable 内的限定返回/提前返回」会生成多余的 endReplaceableGroup()，
 * 造成 group start/end 不平衡：debug 构建下被掩盖，release（R8 优化后）会抛出
 * java.lang.ArrayIndexOutOfBoundsException: length=0; index=-5（ComposerImpl.end）。
 * 详见 JetBrains compose-jb issue #2225 / #2230。保持 if/else 结构即可规避。
 */
@Composable
private fun PermissionGuideCard(permission: LocationPermissionState) {
    val state = permission.state

    // 定位永久拒绝 / 通知永久拒绝 => 必须去系统设置
    val permanentlyBlocked = permission.blockedPermanently

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.debug_permission_needed),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Spacer(Modifier.height(8.dp))

            // 明确列出缺哪些权限
            if (!state.fineLocation) {
                Text(
                    text = "• " + stringResource(R.string.debug_permission_location_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            if (!state.notification) {
                Text(
                    text = "• " + stringResource(R.string.debug_permission_notification_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Spacer(Modifier.height(8.dp))

            // 引导文案：永久拒绝时明确告知去哪里开启
            Text(
                text = if (permanentlyBlocked) {
                    stringResource(R.string.debug_permission_denied_forever)
                } else if (permission.locationDenial == PermissionDenialReason.DENIED) {
                    stringResource(R.string.debug_permission_denied_once)
                } else {
                    stringResource(R.string.debug_permission_rationale)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 未永久拒绝时，先给「重新申请」按钮
                if (!permanentlyBlocked) {
                    Button(onClick = { permission.requestAll() }) {
                        Text(stringResource(R.string.debug_permission_request))
                    }
                }
                OutlinedButton(onClick = { permission.openAppSettings() }) {
                    Text(stringResource(R.string.debug_permission_open_settings))
                }
            }

            // 仅通知缺失时，额外提供通知设置入口
            if (state.hasLocation && !state.notification) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { permission.openNotificationSettings() }) {
                    Text(stringResource(R.string.debug_permission_open_notification))
                }
            }
        }
    }
}

/** 当前定位结果卡片 */
@Composable
private fun CurrentLocationCard(
    snapshot: LocationSnapshot?,
    isLocating: Boolean,
    isContinuous: Boolean,
    callbackCount: Int
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.debug_current_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                // 运行状态徽标
                Text(
                    text = when {
                        !isLocating -> stringResource(R.string.debug_status_idle)
                        isContinuous -> stringResource(R.string.debug_status_continuous)
                        else -> stringResource(R.string.debug_status_single)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isLocating) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            if (snapshot == null) {
                Text(
                    text = stringResource(R.string.debug_no_location_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LocationDetailRows(snapshot = snapshot, callbackCount = callbackCount)
            }
        }
    }
}

/**
 * 有定位结果时的明细行（经纬度 / 精度 / 来源 / 可信度 / 时间 / 地址）。
 *
 * 与 [PermissionGuideCard] 同理：这里保持单一 if/else 结构，避免在 @Composable 内提前返回。
 */
@Composable
private fun LocationDetailRows(snapshot: LocationSnapshot, callbackCount: Int) {
    // 经纬度用等宽字体，便于核对数字
    InfoRow(
        label = stringResource(R.string.debug_label_latitude),
        value = String.format(Locale.CHINA, "%.6f", snapshot.latitude),
        mono = true
    )
    InfoRow(
        label = stringResource(R.string.debug_label_longitude),
        value = String.format(Locale.CHINA, "%.6f", snapshot.longitude),
        mono = true
    )
    InfoRow(
        label = stringResource(R.string.debug_label_accuracy),
        value = String.format(Locale.CHINA, "%.1f m", snapshot.accuracy),
        mono = true,
        // 精度优于 50 米标绿，否则标黄提示
        highlight = when {
            snapshot.accuracy <= 50f -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.tertiary
        }
    )
    InfoRow(
        label = stringResource(R.string.debug_label_source),
        value = "${snapshot.sourceName}（locationType=${snapshot.locationType}）",
        mono = true
    )
    InfoRow(
        label = stringResource(R.string.debug_label_trust),
        value = snapshot.trustLevel.toString(),
        mono = true
    )
    InfoRow(
        label = stringResource(R.string.debug_label_time),
        value = DebugViewModel.formatTime(snapshot.timestamp),
        mono = true
    )
    snapshot.address?.takeIf { it.isNotBlank() }?.let { address ->
        InfoRow(
            label = stringResource(R.string.debug_label_address),
            value = address
        )
    }

    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.debug_callback_count, callbackCount),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 一行「标签 - 值」 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    mono: Boolean = false,
    highlight: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = value,
            style = if (mono) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = highlight,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 单条回调日志 */
@Composable
private fun LogRow(entry: LocationLogEntry) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (entry.success) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${entry.sequence}  ${DebugViewModel.formatTime(entry.receivedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                // 与上次回调的间隔，用于验证「按间隔回调」
                entry.gapMs?.let { gap ->
                    Text(
                        text = stringResource(R.string.debug_log_gap, gap),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}
