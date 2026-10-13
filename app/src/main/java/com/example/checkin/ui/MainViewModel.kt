// 职责：打卡点列表 ViewModel，以 StateFlow 暴露打卡点/记录/监测状态并处理增删改
package com.example.checkin.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.data.local.entity.CheckinRecord
import com.example.checkin.data.repository.CheckinPointRepository
import com.example.checkin.data.repository.CheckinRecordRepository
import com.example.checkin.data.repository.ServiceLocator
import com.example.checkin.geofence.FenceMonitor
import com.example.checkin.geofence.FenceRuntimeState
import com.example.checkin.geofence.FenceState
import com.example.checkin.notify.Notifier
import com.example.checkin.service.CheckinForegroundService
import com.example.checkin.util.BatteryOptimizationHelper
import com.example.checkin.util.NotifyPreferences
import com.example.checkin.util.PowerState
import com.example.checkin.util.PreferencesStore
import com.example.checkin.util.RomUtils
import com.example.checkin.util.RomVendor
import com.example.checkin.util.SelfCheckResult
import com.example.checkin.util.SelfCheckRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 列表页 UI 状态 */
data class PointListUiState(
    val points: List<CheckinPoint> = emptyList(),
    /** 启用中的数量，用于顶部状态文案 */
    val enabledCount: Int = 0,
    /** 记录总数 */
    val recordCount: Int = 0,
    /** 后台监测（前台服务）是否开启 */
    val monitoringEnabled: Boolean = false,
    /** 隐私政策是否已同意 */
    val privacyAgreed: Boolean = false,
    /** 首次加载中 */
    val loading: Boolean = true,
    /** 各打卡点围栏状态（Key = 打卡点 id），由前台服务实时写入 */
    val fenceStates: Map<Long, FenceRuntimeState> = emptyMap(),
    /** 提醒方式偏好 */
    val notifyPrefs: NotifyPreferences = NotifyPreferences(),
    /** 是否已排除电池优化 */
    val batteryOptimizationExempt: Boolean = false,
    /** 前台服务是否正在运行 */
    val serviceRunning: Boolean = false,
    /** 是否具备「全屏提醒」权限（Android 14+ 需用户手动授予，否则锁屏只显示横幅） */
    val fullScreenIntentAllowed: Boolean = true,
    /** 检测到的 ROM 厂商 */
    val romVendor: RomVendor = RomVendor.OTHER
) {
    /** 取某打卡点的围栏状态；未判定过时返回 null */
    fun fenceStateOf(pointId: Long): FenceState? = fenceStates[pointId]?.state

    /** 当前是否有打卡点处于接近/已进入状态（用于顶部提示） */
    val approachingCount: Int
        get() = fenceStates.values.count {
            it.state == FenceState.NEAR || it.state == FenceState.INSIDE
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val pointRepository: CheckinPointRepository =
        ServiceLocator.checkinPointRepository(application)

    private val recordRepository: CheckinRecordRepository =
        ServiceLocator.checkinRecordRepository(application)

    // 统一走进程级单例：此前用 PreferencesStore(application) 新实例，
    // 导致写入的提醒方式缓存在别的实例上、Notifier 读取不到（见 PreferencesStore 说明）。
    private val preferencesStore = PreferencesStore.get(application)

    private val monitoring = MutableStateFlow(false)
    private val privacyAgreed = MutableStateFlow(false)
    private val loading = MutableStateFlow(true)

    /**
     * 一次性提示消息（Snackbar 用）。
     *
     * [UiMessage.seq] 必须存在：`MutableStateFlow` 会合并**相等**的新值，
     * 若连续两次产生同一句提示（例如重复点按同一个失败操作），
     * 第二条会被静默丢弃、用户看不到任何反馈。带上自增序号可保证「每次投递都会发射」。
     */
    data class UiMessage(val text: String, val seq: Long)

    private val _messages = MutableStateFlow<UiMessage?>(null)
    val messages: StateFlow<UiMessage?> = _messages.asStateFlow()

    private var messageSeq: Long = 0L

    /** 记录页数据（时间倒序） */
    val records: StateFlow<List<CheckinRecord>> = recordRepository.allRecordsByTimeDesc
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // ---------------------------------------------------------------- 设置页：保活相关状态

    /** 提醒方式偏好（声音 / 震动 / 全屏） */
    private val notifyPrefs = MutableStateFlow(NotifyPreferences())

    /** 是否已排除电池优化（不优化状态） */
    private val batteryOptimizationExempt = MutableStateFlow(false)

    /**
     * 是否具备「全屏提醒」权限（Android 14+ 需在系统设置中单独授予）。
     *
     * 未持有时 `setFullScreenIntent` 会被系统静默降级为普通横幅通知，
     * 因此设置页需要在开关旁给出引导入口。
     */
    private val fullScreenIntentAllowed = MutableStateFlow(true)

    /** 自检结果 */
    private val _selfCheckResult = MutableStateFlow<SelfCheckResult?>(null)
    val selfCheckResult: StateFlow<SelfCheckResult?> = _selfCheckResult.asStateFlow()

    /** 是否正在自检 */
    private val _selfChecking = MutableStateFlow(false)
    val selfChecking: StateFlow<Boolean> = _selfChecking.asStateFlow()

    /** 检测到的 ROM 厂商（用于展示与引导） */
    val romVendor: RomVendor get() = RomUtils.vendor

    /**
     * 手动触发一次「服务运行状态」刷新（自检/开关切换后调用，避免等待下一个轮询周期）。
     */
    private val serviceRefreshTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * 服务是否正在运行（进程内真实状态），由订阅驱动的探针给出。
     *
     * 关键改进：原实现是在 init 中无条件 `while (true) { ...; delay(2s) }`，
     * 即使 App 退到后台、设置页从未打开也持续每 2 秒唤醒一次——对一个本身
     * 以省电为卖点的应用并不合适。现在改为 [stateIn] + `WhileSubscribed`：
     * 只有 uiState / runtimeFlags 真正被 UI 订阅时才轮询，空闲 5 秒后自动停止。
     */
    private val serviceRunningProbe: Flow<Boolean> = merge(
        flow {
            while (true) {
                emit(Unit)
                delay(SERVICE_STATE_POLL_MS)
            }
        },
        serviceRefreshTick
    ).map { CheckinForegroundService.isRunning() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = CheckinForegroundService.isRunning()
        )

    /** 电源状态提示（省电模式 / 电池优化） */
    private val _powerState = MutableStateFlow(PowerState())
    val powerState: StateFlow<PowerState> = _powerState.asStateFlow()

    /**
     * 顶部状态标志位。
     *
     * 打包成 [MonitorFlags] 是因为 combine 的类型化重载最多支持 5 个 Flow，
     * 而本页需要聚合更多数据源（列表/记录数/开关/隐私/加载中/围栏状态/偏好）。
     */
    private data class MonitorFlags(
        val monitoring: Boolean,
        val privacyAgreed: Boolean,
        val loading: Boolean,
        val notifyPrefs: NotifyPreferences
    )

    private val flags: Flow<MonitorFlags> = combine(
        monitoring,
        privacyAgreed,
        loading,
        notifyPrefs
    ) { monitoringOn, agreed, isLoading, prefs ->
        MonitorFlags(monitoringOn, agreed, isLoading, prefs)
    }

    /** 围栏与保活相关的组合状态，避免单次 combine 参数过多 */
    private data class RuntimeFlags(
        val fenceStates: Map<Long, FenceRuntimeState>,
        val batteryExempt: Boolean,
        val serviceRunning: Boolean,
        val fullScreenAllowed: Boolean
    )

    private val runtimeFlags: Flow<RuntimeFlags> = combine(
        FenceMonitor.states,
        batteryOptimizationExempt,
        serviceRunningProbe,
        fullScreenIntentAllowed
    ) { states, exempt, runningNow, fullScreenOk ->
        RuntimeFlags(states, exempt, runningNow, fullScreenOk)
    }

    val uiState: StateFlow<PointListUiState> = combine(
        pointRepository.allPoints,
        recordRepository.recordCount,
        flags,
        runtimeFlags
    ) { points, recordCount, f, r ->
        PointListUiState(
            points = points,
            enabledCount = points.count { it.enabled },
            recordCount = recordCount,
            monitoringEnabled = f.monitoring,
            privacyAgreed = f.privacyAgreed,
            loading = f.loading,
            fenceStates = r.fenceStates,
            notifyPrefs = f.notifyPrefs,
            batteryOptimizationExempt = r.batteryExempt,
            serviceRunning = r.serviceRunning,
            fullScreenIntentAllowed = r.fullScreenAllowed,
            romVendor = RomUtils.vendor
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PointListUiState()
    )

    init {
        viewModelScope.launch {
            // 一次性恢复：监测开关、隐私同意状态、提醒方式与电源状态。
            // （原实现拆成两个 init 块，且重复读取了 monitoring/privacy/loading，此处合并）
            monitoring.value = preferencesStore.readMonitoringEnabled()
            privacyAgreed.value = preferencesStore.readPrivacyAgreed()
            notifyPrefs.value = preferencesStore.readNotifyPreferences()
            loading.value = false
            refreshPowerState()
        }
    }

    /** 刷新电源/电池优化状态（从系统设置页返回后调用） */
    fun refreshPowerState() {
        val context = getApplication<Application>()
        batteryOptimizationExempt.value =
            BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        _powerState.value = PowerState(
            batteryOptimizationExempt = batteryOptimizationExempt.value,
            powerSaveMode = BatteryOptimizationHelper.isPowerSaveMode(context),
            exactAlarmAllowed = BatteryOptimizationHelper.canScheduleExactAlarms(context)
        )
        // 全屏提醒权限（Android 14+）无法监听变化，只能在每次回到页面/自检时重读
        fullScreenIntentAllowed.value = Notifier.canUseFullScreenIntent(context)
    }

    /**
     * 打开「全屏提醒」授权设置页（Android 14+）。
     *
     * 低版本或 ROM 未提供该设置页时静默忽略。
     */
    fun openFullScreenIntentSettings() {
        val context = getApplication<Application>()
        val intent = Notifier.fullScreenIntentSettingsIntent(context) ?: return
        runCatching { context.startActivity(intent) }
            .onFailure { postMessage("无法打开「全屏提醒」设置页，请在系统设置中手动开启") }
    }

    /** 更新提醒方式偏好并持久化 */
    fun updateNotifyPreferences(prefs: NotifyPreferences) {
        notifyPrefs.value = prefs
        viewModelScope.launch { preferencesStore.setNotifyPreferences(prefs) }
    }

    /** 请求把本应用加入电池优化白名单（拉起系统弹窗） */
    fun requestIgnoreBatteryOptimizations() {
        val context = getApplication<Application>()
        BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context)
    }

    /** 运行自检 */
    fun runSelfCheck() {
        viewModelScope.launch {
            _selfChecking.value = true
            val result = runCatching {
                SelfCheckRunner.run(getApplication(), pointRepository)
            }.getOrElse { e ->
                postMessage("自检失败：${e.message}")
                SelfCheckResult(items = emptyList(), completed = false)
            }
            _selfCheckResult.value = result
            _selfChecking.value = false
            // 同时刷新服务与电池状态
            serviceRefreshTick.tryEmit(Unit)
            refreshPowerState()
        }
    }

    // ---------------------------------------------------------------- 打卡点增删改

    // 说明：新增/编辑打卡点由独立的 AddEditPointActivity + PointEditViewModel 承担
    // （页面需内嵌高德 TextureMapView）。此处原有的 savePoint() 已无调用方，
    // 且它的「保存后刷新围栏」职责已下沉到 PointEditViewModel.refreshRunningMonitor()，
    // 保留会形成第二条保存路径，故移除。

    /** 删除打卡点（其打卡记录会被外键级联删除） */
    fun deletePoint(point: CheckinPoint) {
        viewModelScope.launch {
            runCatching { pointRepository.delete(point) }
                .onSuccess {
                    postMessage("已删除「${point.name}」及其打卡记录")
                    refreshGeofenceIfMonitoring()
                }
                .onFailure { postMessage("删除失败：${it.message}") }
        }
    }

    /** 启用 / 停用打卡点 */
    fun toggleEnabled(point: CheckinPoint) {
        viewModelScope.launch {
            val target = !point.enabled
            runCatching { pointRepository.setEnabled(point.id, target) }
                .onSuccess {
                    postMessage(
                        if (target) {
                            "已启用「${point.name}」"
                        } else {
                            "已停用「${point.name}」"
                        }
                    )
                    refreshGeofenceIfMonitoring()
                }
                .onFailure { postMessage("操作失败：${it.message}") }
        }
    }

    // 说明：记录侧的「清空 / 删除单条」已迁移到 RecordsViewModel
    // （见 ui/records/RecordsViewModel.kt）。此处原有的两个同名方法已无调用方，
    // 继续保留会形成两条并行的删除路径，容易在后续改动中产生行为不一致，故移除。

    // ---------------------------------------------------------------- 监测开关

    /**
     * 开始或停止后台监控。
     *
     * 权限校验由 Compose 侧完成（调用前已确保齐全），这里只负责启动/停止服务。
     */
    fun toggleMonitoring() {
        val context = getApplication<Application>()
        val target = !monitoring.value

        if (target) {
            CheckinForegroundService.start(context)
        } else {
            CheckinForegroundService.stop(context)
        }
        monitoring.value = target
        // 服务内部也会持久化，这里同步写一次以保证 UI 状态与磁盘一致
        viewModelScope.launch { preferencesStore.setMonitoringEnabled(target) }
    }

    /** 用户同意高德隐私政策（合规要求），并持久化 */
    fun agreePrivacy() {
        viewModelScope.launch {
            preferencesStore.setPrivacyAgreed(true)
            privacyAgreed.value = true
        }
    }

    /** 消费提示消息 */
    fun consumeMessage() {
        _messages.value = null
    }

    /** 投递一条提示消息（自动附加自增序号，保证相邻同文案也能各自触发一次 Snackbar） */
    private fun postMessage(text: String) {
        val seq = messageSeq + 1
        messageSeq = seq
        _messages.value = UiMessage(text = text, seq = seq)
    }

    /**
     * 数据被**整体替换**后（如从备份恢复）重新对齐运行时状态。
     *
     * 备份恢复会一次性替换全部打卡点与记录，而前台服务里注册的围栏仍指向
     * 恢复前的打卡点。若不重新注册，会出现「列表里有新点位、但收不到提醒」
     * 的静默失效 —— 这类问题在用户侧极难自查，必须在恢复后立即处理。
     */
    fun onExternalDataChanged() {
        refreshGeofenceIfMonitoring()
        refreshPowerState()
        serviceRefreshTick.tryEmit(Unit)
    }

    // ---------------------------------------------------------------- 内部

    /** 若监控处于开启状态，通知服务重新注册围栏（使用 ACTION_REFRESH，不重启服务） */
    private fun refreshGeofenceIfMonitoring() {
        if (monitoring.value) {
            CheckinForegroundService.refresh(getApplication())
        }
    }

    companion object {
        private const val TAG = "MainViewModel"

        /** 服务运行状态的轮询间隔：2 秒（仅在有 UI 订阅时运行，见 serviceRunningProbe） */
        private const val SERVICE_STATE_POLL_MS = 2_000L

        /** UI 取消订阅后，探针的空闲存活时间（覆盖旋转/短暂切后台等场景） */
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
