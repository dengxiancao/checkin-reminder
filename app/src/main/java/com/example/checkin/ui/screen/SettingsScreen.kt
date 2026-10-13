// 职责：设置页，提供监控总开关、提醒方式、电池优化、自检与后台权限引导入口
package com.example.checkin.ui.screen

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.checkin.BuildConfig
import com.example.checkin.R
import com.example.checkin.ui.PointListUiState
import com.example.checkin.ui.permission.LocationPermissionState
import com.example.checkin.util.NotifyPreferences
import com.example.checkin.util.PowerState
import com.example.checkin.util.SelfCheckResult

/**
 * 设置页。
 *
 * 五个区块：
 * 1. 后台监控总开关
 * 2. 提醒方式（声音 / 震动 / 全屏）
 * 3. 电池优化状态与「设置不优化」
 * 4. 保活自检（一键盘点各项状态）
 * 5. 后台权限设置引导入口（按 ROM 给出步骤）+ 定位调试入口 + 关于
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: PointListUiState,
    permission: LocationPermissionState,
    powerState: PowerState,
    selfCheck: SelfCheckResult?,
    selfChecking: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    onUpdateNotifyPrefs: (NotifyPreferences) -> Unit,
    onRequestIgnoreBattery: () -> Unit,
    onRunSelfCheck: () -> Unit,
    onOpenBackgroundGuide: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenFullScreenIntentSettings: () -> Unit,
    onOpenLocationDebug: () -> Unit,
    onExportCsv: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------- 1. 监控总开关 ----------------
            SettingsSection(title = stringResource(R.string.settings_monitor_section)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_monitor_switch),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (uiState.monitoringEnabled) {
                                stringResource(
                                    R.string.settings_monitor_desc_on,
                                    uiState.enabledCount,
                                    // 定位间隔由服务实时维护，这里未运行时按低频档展示
                                    30
                                )
                            } else {
                                stringResource(R.string.settings_monitor_desc_off)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.monitoringEnabled,
                        onCheckedChange = { onToggleMonitoring(it) }
                    )
                }

                // 权限不足时给出明确提示
                if (!permission.state.allGranted) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_monitor_need_permission),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ---------------- 2. 提醒方式 ----------------
            SettingsSection(title = stringResource(R.string.settings_notify_section)) {
                SwitchRow(
                    title = stringResource(R.string.settings_notify_sound),
                    desc = stringResource(R.string.settings_notify_sound_desc),
                    checked = uiState.notifyPrefs.sound,
                    onCheckedChange = {
                        onUpdateNotifyPrefs(uiState.notifyPrefs.copy(sound = it))
                    }
                )
                SwitchRow(
                    title = stringResource(R.string.settings_notify_vibrate),
                    desc = stringResource(R.string.settings_notify_vibrate_desc),
                    checked = uiState.notifyPrefs.vibrate,
                    onCheckedChange = {
                        onUpdateNotifyPrefs(uiState.notifyPrefs.copy(vibrate = it))
                    }
                )
                SwitchRow(
                    title = stringResource(R.string.settings_notify_fullscreen),
                    desc = stringResource(R.string.settings_notify_fullscreen_desc),
                    checked = uiState.notifyPrefs.fullScreen,
                    onCheckedChange = {
                        onUpdateNotifyPrefs(uiState.notifyPrefs.copy(fullScreen = it))
                    }
                )
                // Android 14+：全屏提醒权限默认被撤销，未授权时全屏 Intent 会被静默降级。
                // 仅在用户开启了「全屏提醒」但系统未授权时给出引导，避免无谓打扰。
                if (uiState.notifyPrefs.fullScreen && !uiState.fullScreenIntentAllowed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_notify_fullscreen_needs_permission),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = onOpenFullScreenIntentSettings) {
                        Text(stringResource(R.string.settings_notify_fullscreen_grant))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.ChevronRight, contentDescription = null)
                    }
                }
            }

            // ---------------- 3. 电池优化 ----------------
            SettingsSection(title = stringResource(R.string.settings_battery_section)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (uiState.batteryOptimizationExempt) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.BatteryAlert
                        },
                        contentDescription = null,
                        tint = if (uiState.batteryOptimizationExempt) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (uiState.batteryOptimizationExempt) {
                                R.string.settings_battery_status_ignored
                            } else {
                                R.string.settings_battery_status_optimized
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }

                // 省电模式提示
                if (powerState.powerSaveMode) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_battery_power_save_on),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_battery_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                if (uiState.batteryOptimizationExempt) {
                    Text(
                        text = stringResource(R.string.settings_battery_done),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Button(onClick = onRequestIgnoreBattery) {
                        Text(stringResource(R.string.settings_battery_action))
                    }
                }
            }

            // ---------------- 4. 自检 ----------------
            SettingsSection(title = stringResource(R.string.settings_selfcheck_section)) {
                Button(
                    onClick = onRunSelfCheck,
                    enabled = !selfChecking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (selfChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (selfChecking) R.string.settings_selfcheck_running
                            else R.string.settings_selfcheck_action
                        )
                    )
                }

                // 自检结果清单
                selfCheck?.takeIf { it.completed }?.let { result ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_selfcheck_summary,
                            result.passedCount,
                            result.failedCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (result.allCriticalPassed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    result.items.forEach { item -> CheckRow(item) }
                }
            }

            // ---------------- 5. 后台权限引导 ----------------
            SettingsSection(title = stringResource(R.string.settings_rom_section)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_rom_detected,
                            uiState.romVendor.displayName
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_rom_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenBackgroundGuide, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_rom_action))
                }
            }

            // ---------------- 6. 权限状态 ----------------
            SettingsSection(title = stringResource(R.string.settings_permission_section)) {
                PermissionLine(
                    label = stringResource(R.string.permission_location),
                    granted = permission.state.hasLocation,
                    onRequest = { permission.requestLocation() }
                )
                PermissionLine(
                    label = stringResource(R.string.permission_background_short),
                    granted = permission.state.backgroundLocation,
                    onRequest = { permission.openAppSettings() }
                )
                PermissionLine(
                    label = stringResource(R.string.permission_notification),
                    granted = permission.state.notification,
                    onRequest = { onOpenNotificationSettings() }
                )
            }

            // ---------------- 7. 数据概览 ----------------
            SettingsSection(title = stringResource(R.string.settings_data_section)) {
                InfoLine(
                    label = stringResource(R.string.settings_point_count),
                    value = uiState.points.size.toString()
                )
                InfoLine(
                    label = stringResource(R.string.settings_enabled_count),
                    value = uiState.enabledCount.toString()
                )
                InfoLine(
                    label = stringResource(R.string.settings_record_count),
                    value = uiState.recordCount.toString()
                )
                InfoLine(
                    label = stringResource(R.string.settings_monitoring_state),
                    value = stringResource(
                        if (uiState.monitoringEnabled) R.string.settings_state_on
                        else R.string.settings_state_off
                    )
                )
            }

            // ---------------- 8. 数据管理 ----------------
            //
            // 这一节是「数据可携带」能力的入口。本应用关闭了系统自动备份
            //（allowBackup=false，避免位置隐私被同步到云端），因此**必须**提供
            // 用户主动导出与恢复的通道，否则卸载或换机就等于数据永久丢失。
            SettingsSection(title = stringResource(R.string.settings_data_manage_section)) {
                Text(
                    text = stringResource(R.string.settings_data_local_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                ActionRow(
                    title = stringResource(R.string.settings_data_export_csv),
                    desc = stringResource(R.string.settings_data_export_csv_desc),
                    onClick = onExportCsv
                )
                ActionRow(
                    title = stringResource(R.string.settings_data_backup),
                    desc = stringResource(R.string.settings_data_backup_desc),
                    onClick = onExportBackup
                )
                ActionRow(
                    title = stringResource(R.string.settings_data_restore),
                    desc = stringResource(R.string.settings_data_restore_desc),
                    onClick = onRestoreBackup
                )
            }

            // ---------------- 9. 调试与关于 ----------------
            SettingsSection(title = stringResource(R.string.settings_debug_section)) {
                Text(
                    text = stringResource(R.string.settings_debug_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onOpenLocationDebug) {
                    Text(stringResource(R.string.settings_open_debug))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }

            SettingsSection(title = stringResource(R.string.settings_about_section)) {
                InfoLine(
                    label = stringResource(R.string.settings_version),
                    value = BuildConfig.VERSION_NAME
                )
                InfoLine(
                    label = stringResource(R.string.settings_map_sdk),
                    value = stringResource(R.string.settings_map_sdk_value)
                )
                // 隐私政策必须可随时查阅（上架硬性要求），不能只有首次启动的弹窗
                TextButton(onClick = onOpenPrivacyPolicy) {
                    Text(stringResource(R.string.privacy_policy_action))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }
        }
    }
}

/** 自检结果一行：通过打勾，未通过给出原因；无法自动检测的项单独标明 */
@Composable
private fun CheckRow(item: com.example.checkin.util.CheckItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = when {
                item.passed -> Icons.Filled.CheckCircle
                item.manualOnly -> Icons.AutoMirrored.Filled.HelpOutline
                else -> Icons.Filled.ErrorOutline
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = when {
                item.passed -> MaterialTheme.colorScheme.primary
                item.manualOnly -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(item.titleRes),
                style = MaterialTheme.typography.bodySmall
            )
            // 未通过或需手动确认时给出原因
            val hintRes = item.hintRes
            if (hintRes != null && !item.passed) {
                Text(
                    text = stringResource(hintRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.manualOnly) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
        Text(
            text = stringResource(
                if (item.passed) R.string.settings_selfcheck_item_ok
                else R.string.settings_selfcheck_item_fail
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (item.passed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
}

/** 分组卡片 */
@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** 带说明文字的开关行 */
@Composable
internal fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 可点击的动作行：标题 + 副标题 + 右箭头。
 *
 * 与 [SwitchRow] 的区别是它表达「进入下一步/触发一次动作」，而不是「切换开关」，
 * 因此用箭头而不是 Switch —— 形状本身就在提示交互结果。
 */
@Composable
internal fun ActionRow(
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 权限行：已授权显示对勾，未授权提供申请按钮 */
@Composable
internal fun PermissionLine(    label: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (granted) {
            Text(
                text = stringResource(R.string.permission_granted),
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            TextButton(onClick = onRequest) {
                Text(stringResource(R.string.permission_grant))
            }
        }
    }
}

/** 普通信息行 */
@Composable
internal fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
