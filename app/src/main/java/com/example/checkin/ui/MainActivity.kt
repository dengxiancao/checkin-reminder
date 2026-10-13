// 职责：主界面，承载底部三个 Tab（打卡点/打卡记录/设置）、权限申请、页面跳转与文件导入导出
package com.example.checkin.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.checkin.R
import com.example.checkin.ui.components.PermissionCard
import com.example.checkin.ui.components.PrivacyConsentDialog
import com.example.checkin.ui.debug.DebugScreen
import com.example.checkin.ui.debug.DebugViewModel
import com.example.checkin.ui.legal.PrivacyPolicyScreen
import com.example.checkin.ui.permission.rememberLocationPermission
import com.example.checkin.ui.point.AddEditPointActivity
import com.example.checkin.ui.records.ClearRecordsDialog
import com.example.checkin.ui.records.RecordEditDialog
import com.example.checkin.ui.records.RecordsScreen
import com.example.checkin.ui.records.RecordsViewModel
import com.example.checkin.ui.screen.BackgroundGuideScreen
import com.example.checkin.ui.screen.PointListScreen
import com.example.checkin.ui.screen.SettingsScreen
import com.example.checkin.ui.theme.CheckinTheme
import com.example.checkin.util.BatteryOptimizationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用唯一的主 Activity。
 *
 * 架构：单 Activity + Compose；底部三个 Tab（打卡点 / 打卡记录 / 设置）。
 * 新增/编辑打卡点因需要内嵌高德地图，单独使用 [AddEditPointActivity]。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CheckinTheme {
                CheckinAppRoot()
            }
        }
    }
}

/** 底部 Tab 索引 */
private object Tabs {
    const val POINTS = 0
    const val RECORDS = 1
    const val SETTINGS = 2
}

/** 设置 Tab 下的子页面 */
private enum class SubPage {
    /** 设置主页 */
    NONE,

    /** 定位调试页 */
    DEBUG,

    /** 后台权限设置引导页 */
    BACKGROUND_GUIDE,

    /** 隐私政策页 */
    PRIVACY_POLICY
}

@Composable
private fun CheckinAppRoot() {
    val viewModel: MainViewModel = viewModel()
    val recordsViewModel: RecordsViewModel = viewModel()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()

    val records by recordsViewModel.records.collectAsStateWithLifecycle()
    val dailyStats by recordsViewModel.dailyStats.collectAsStateWithLifecycle()
    val recordsView by recordsViewModel.view.collectAsStateWithLifecycle()
    val statsRange by recordsViewModel.statsRange.collectAsStateWithLifecycle()
    val recordsMessage by recordsViewModel.message.collectAsStateWithLifecycle()
    val editingRecord by recordsViewModel.editing.collectAsStateWithLifecycle()
    val clearConfirmVisible by recordsViewModel.clearConfirmVisible.collectAsStateWithLifecycle()

    // 定位/通知权限统一管理，列表页提示卡与设置页共用同一份状态
    val permission = rememberLocationPermission()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableIntStateOf(Tabs.POINTS) }
    var restoreConfirmVisible by remember { mutableStateOf(false) }

    val undoLabel = stringResource(R.string.records_undo)
    val exportSuccessText = stringResource(R.string.export_success)
    val exportFailedFormat = stringResource(R.string.export_failed)

    // 新增/编辑打卡点：用 Activity Result 打开独立页面，返回后数据由 Room Flow 自动刷新
    val editLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            permission.requestLocationIfNeeded()
        }
    }

    // ------------------------------------------------------------ 文件导出 / 导入（SAF）
    //
    // 用 Storage Access Framework 而不是直接写外部存储：
    // minSdk 26 起全兼容、无需任何存储权限，且用户始终明确知道文件写到了哪里。

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = runCatching {
                val text = recordsViewModel.buildCsv()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("无法写入所选位置")
                }
            }
            recordsViewModel.notify(
                outcome.fold(
                    onSuccess = { exportSuccessText },
                    onFailure = { exportFailedFormat.format(it.message.orEmpty()) }
                )
            )
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = runCatching {
                val text = recordsViewModel.buildBackupJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        out.flush()
                    } ?: error("无法写入所选位置")
                }
            }
            recordsViewModel.notify(
                outcome.fold(
                    onSuccess = { exportSuccessText },
                    onFailure = { exportFailedFormat.format(it.message.orEmpty()) }
                )
            )
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) {
                recordsViewModel.notify("无法读取所选文件")
            } else {
                recordsViewModel.restoreFromJson(text) { success ->
                    // 恢复会整体替换打卡点，必须让服务重新注册围栏
                    if (success) viewModel.onExternalDataChanged()
                }
            }
        }
    }

    // 消息提示（主 ViewModel）
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current.text)
        viewModel.consumeMessage()
    }

    // 消息提示（记录 ViewModel）：删除记录时带「撤销」动作
    LaunchedEffect(recordsMessage) {
        val current = recordsMessage ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = current.text,
            actionLabel = if (current.undoable) undoLabel else null,
            withDismissAction = current.undoable,
            duration = if (current.undoable) SnackbarDuration.Long else SnackbarDuration.Short
        )
        recordsViewModel.consumeMessage()
        // 带上消息序号：Snackbar 会排队，用户点到的可能是较早那条删除的「撤销」，
        // 序号校验可避免把「别的」记录写回去或误清掉新的撤销缓冲。
        if (result == SnackbarResult.ActionPerformed) {
            recordsViewModel.undoDelete(current.seq)
        } else {
            // 撤销窗口关闭：丢弃缓冲，使该次删除不可再撤销
            recordsViewModel.commitDelete(current.seq)
        }
    }

    // 首次进入且未同意隐私政策时弹出合规声明
    if (!uiState.privacyAgreed) {
        PrivacyConsentDialog(onAgree = { viewModel.agreePrivacy() })
    }

    // 记录编辑对话框
    editingRecord?.let { record ->
        RecordEditDialog(
            record = record,
            onDismiss = { recordsViewModel.cancelEdit() },
            onSave = { recordsViewModel.saveEdit(it) }
        )
    }

    // 清空全部记录的二次确认
    if (clearConfirmVisible) {
        ClearRecordsDialog(
            recordCount = records.size,
            onDismiss = { recordsViewModel.dismissClear() },
            onConfirm = { recordsViewModel.confirmClear() }
        )
    }

    // 从备份恢复前的二次确认（破坏性操作）
    if (restoreConfirmVisible) {
        AlertDialog(
            onDismissRequest = { restoreConfirmVisible = false },
            title = { Text(stringResource(R.string.settings_data_restore_confirm_title)) },
            text = { Text(stringResource(R.string.settings_data_restore_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreConfirmVisible = false
                        restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                ) {
                    Text(stringResource(R.string.settings_data_restore_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreConfirmVisible = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == Tabs.POINTS,
                    onClick = { selectedTab = Tabs.POINTS },
                    icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_points)) }
                )
                NavigationBarItem(
                    selected = selectedTab == Tabs.RECORDS,
                    onClick = { selectedTab = Tabs.RECORDS },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_records)) }
                )
                NavigationBarItem(
                    selected = selectedTab == Tabs.SETTINGS,
                    onClick = { selectedTab = Tabs.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 权限未齐全时在页面顶部长驻提示卡（三个 Tab 共用）
            PermissionCard(
                permissions = permission.state,
                onRequestForeground = { permission.requestLocation() },
                onRequestBackground = { permission.openAppSettings() },
                onRequestNotification = { permission.requestNotification() }
            )

            when (selectedTab) {
                Tabs.POINTS -> PointListScreen(
                    uiState = uiState,
                    onAdd = {
                        editLauncher.launch(AddEditPointActivity.newIntentForCreate(context))
                    },
                    onEdit = { point ->
                        editLauncher.launch(
                            AddEditPointActivity.newIntentForEdit(context, point.id)
                        )
                    },
                    onDelete = { viewModel.deletePoint(it) },
                    onToggleEnabled = { viewModel.toggleEnabled(it) },
                    onToggleMonitoring = { viewModel.toggleMonitoring() }
                )

                Tabs.RECORDS -> RecordsScreen(
                    records = records,
                    dailyStats = dailyStats,
                    view = recordsView,
                    statsRange = statsRange,
                    onSelectView = { recordsViewModel.selectView(it) },
                    onSelectStatsRange = { recordsViewModel.selectStatsRange(it) },
                    onDelete = { recordsViewModel.deleteRecord(it) },
                    onEdit = { recordsViewModel.startEdit(it) },
                    onClearRequest = { recordsViewModel.requestClear() }
                )

                else -> {
                    // 设置 Tab：内嵌定位调试页 / 后台权限引导页 / 隐私政策页
                    var subPage by rememberSaveable { mutableStateOf(SubPage.NONE) }

                    when (subPage) {
                        SubPage.DEBUG -> {
                            val debugViewModel: DebugViewModel = viewModel()
                            val debugState by debugViewModel.uiState.collectAsStateWithLifecycle()
                            DebugScreen(
                                uiState = debugState,
                                permission = permission,
                                onRequestSingle = { debugViewModel.requestSingle() },
                                onStartContinuous = { debugViewModel.startContinuous() },
                                onStop = { debugViewModel.stop() },
                                onIntervalChange = { debugViewModel.setInterval(it) },
                                onTimeoutChange = { debugViewModel.setTimeout(it) },
                                onClearLog = { debugViewModel.clearLog() },
                                onBack = { subPage = SubPage.NONE }
                            )
                        }

                        SubPage.BACKGROUND_GUIDE -> {
                            BackgroundGuideScreen(
                                initialVendor = uiState.romVendor,
                                onOpenAutoStart = {
                                    BatteryOptimizationHelper.openAutoStartSettings(context)
                                },
                                onOpenBatterySettings = {
                                    viewModel.requestIgnoreBatteryOptimizations()
                                },
                                onOpenAppInfo = {
                                    BatteryOptimizationHelper.openAppDetails(context)
                                },
                                onBack = { subPage = SubPage.NONE }
                            )
                        }

                        SubPage.PRIVACY_POLICY -> {
                            PrivacyPolicyScreen(onBack = { subPage = SubPage.NONE })
                        }

                        SubPage.NONE -> {
                            val powerState by viewModel.powerState.collectAsStateWithLifecycle()
                            val selfCheck by viewModel.selfCheckResult.collectAsStateWithLifecycle()
                            val selfChecking by viewModel.selfChecking.collectAsStateWithLifecycle()

                            SettingsScreen(
                                uiState = uiState,
                                permission = permission,
                                powerState = powerState,
                                selfCheck = selfCheck,
                                selfChecking = selfChecking,
                                onToggleMonitoring = { enabled ->
                                    if (enabled != uiState.monitoringEnabled) {
                                        if (enabled && !permission.canMonitor) {
                                            // 权限不足时不放行，并引导授权
                                            permission.requestAll()
                                        } else {
                                            viewModel.toggleMonitoring()
                                        }
                                    }
                                },
                                onUpdateNotifyPrefs = { viewModel.updateNotifyPreferences(it) },
                                onRequestIgnoreBattery = {
                                    viewModel.requestIgnoreBatteryOptimizations()
                                },
                                onRunSelfCheck = { viewModel.runSelfCheck() },
                                onOpenBackgroundGuide = { subPage = SubPage.BACKGROUND_GUIDE },
                                onOpenNotificationSettings = {
                                    BatteryOptimizationHelper.openNotificationSettings(context)
                                },
                                onOpenFullScreenIntentSettings = {
                                    viewModel.openFullScreenIntentSettings()
                                },
                                onOpenLocationDebug = { subPage = SubPage.DEBUG },
                                onExportCsv = { exportCsvLauncher.launch(recordsViewModel.csvFileName()) },
                                onExportBackup = {
                                    exportBackupLauncher.launch(recordsViewModel.backupFileName())
                                },
                                onRestoreBackup = { restoreConfirmVisible = true },
                                onOpenPrivacyPolicy = { subPage = SubPage.PRIVACY_POLICY }
                            )
                        }
                    }
                }
            }
        }
    }
}
