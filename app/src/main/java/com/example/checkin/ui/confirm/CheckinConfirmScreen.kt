// 职责：打卡确认页 UI，展示打卡点信息与当前距离并提供「立即打卡」
package com.example.checkin.ui.confirm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.checkin.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 打卡确认页。
 *
 * 由「进入/离开打卡范围」通知点击后打开，承担需求 3、4：
 * - 显示打卡点信息、当前距离；
 * - 一个「立即打卡」按钮，点击后用当前位置写入 CheckinRecord；
 * - 成功后展示成功状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckinConfirmScreen(
    pointId: Long,
    isEnter: Boolean,
    initialDistance: Double?,
    notificationAllowed: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onClose: () -> Unit,
    viewModel: CheckinConfirmViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 进入页面时加载打卡点并自动定位一次
    LaunchedEffect(pointId) {
        viewModel.load(pointId, isEnter, initialDistance)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.confirm_title)) },
                actions = {
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.confirm_close))
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
            // ---------------- 通知权限引导（需求 6） ----------------
            if (!notificationAllowed) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.NotificationsOff, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.confirm_permission_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenNotificationSettings) {
                            Text(stringResource(R.string.confirm_permission_button))
                        }
                    }
                }
            }

            // ---------------- 状态分流 ----------------
            // 说明：这里用 if/else if/else 而**不用提前返回**（return@Column）。
            // Compose 编译器对「@Composable 内的限定返回」会生成多余的 endReplaceableGroup()，
            // 导致 group start/end 不平衡：debug 下被掩盖，release（R8 优化后）会抛
            // java.lang.ArrayIndexOutOfBoundsException: length=0; index=-5（ComposerImpl.end）。
            // 参考 JetBrains compose-jb issue #2225 / #2230。
            val point = uiState.point
            val success = uiState.success

            if (uiState.loading) {
                // ---------------- 加载中 ----------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.confirm_locating))
                }
            } else if (point == null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.confirm_point_deleted),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else if (success != null) {
                // ---------------- 打卡成功状态 ----------------
                SuccessCard(success)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.confirm_close))
                }
            } else {
                // ---------------- 主内容（走到这里 point 一定非空） ----------------
                val currentDistance = uiState.currentDistanceMeters

                // ---------------- 打卡点信息 ----------------
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    if (uiState.isEnter) R.string.confirm_enter_subtitle
                                    else R.string.confirm_leave_subtitle
                                ),
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        InfoRow(
                            label = stringResource(R.string.confirm_point_label),
                            value = point.name,
                            bold = true
                        )
                        InfoRow(
                            label = stringResource(R.string.confirm_radius_label),
                            value = stringResource(R.string.confirm_radius_value, point.radiusMeters)
                        )
                        InfoRow(
                            label = stringResource(R.string.confirm_distance_label),
                            value = when {
                                uiState.locating -> stringResource(R.string.confirm_distance_unknown)
                                currentDistance != null -> stringResource(
                                    R.string.confirm_distance_value,
                                    currentDistance.toInt()
                                )

                                else -> "—"
                            },
                            mono = true
                        )
                    }
                }

                // ---------------- 定位/错误提示 ----------------
                uiState.locationError?.let { message ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Error, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.confirm_location_failed),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(text = message, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                uiState.error?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // ---------------- 操作按钮 ----------------
                Button(
                    onClick = { viewModel.checkIn() },
                    enabled = !uiState.submitting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (uiState.submitting) R.string.confirm_checking_in
                            else R.string.confirm_checkin_button
                        )
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.refreshLocation() },
                    enabled = !uiState.locating && !uiState.submitting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.confirm_locating))
                }
            }
        }
    }
}

/** 打卡成功卡片 */
@Composable
private fun SuccessCard(success: CheckinSuccess) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.confirm_success_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.confirm_success_text,
                    formatTime(success.time),
                    success.pointName
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            success.distanceMeters?.let { distance ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.confirm_success_distance,
                        distance.toInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 「标签 - 值」信息行 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    bold: Boolean = false,
    mono: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            text = value,
            style = when {
                mono -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                bold -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyMedium
            },
            modifier = Modifier.weight(1f)
        )
    }
}

/** 时间格式化：yyyy-MM-dd HH:mm:ss（静态复用的线程安全 DateTimeFormatter） */
private val TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

/** 时间格式化：yyyy-MM-dd HH:mm:ss */
private fun formatTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(TIME_FMT)
