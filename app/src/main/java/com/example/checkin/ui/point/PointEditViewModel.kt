// 职责：新增/编辑打卡点 ViewModel，管理名称、选点坐标、半径档位与保存校验
package com.example.checkin.ui.point

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.data.repository.CheckinPointRepository
import com.example.checkin.data.repository.ServiceLocator
import com.example.checkin.service.CheckinForegroundService
import com.example.checkin.util.PreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 新增/编辑页 UI 状态 */
data class PointEditUiState(
    /** 编辑中的打卡点 id，0 表示新增 */
    val pointId: Long = 0L,
    val name: String = "",
    /** 纬度，0.0 表示尚未选点 */
    val latitude: Double = 0.0,
    /** 经度，0.0 表示尚未选点 */
    val longitude: Double = 0.0,
    /** 逆地理地址（选点后可带回） */
    val address: String? = null,
    /** 半径（米） */
    val radiusMeters: Int = CheckinPoint.DEFAULT_RADIUS,
    val enabled: Boolean = true,
    /** 进入范围是否提醒 */
    val notifyOnEnter: Boolean = true,
    /** 离开范围是否提醒 */
    val notifyOnExit: Boolean = true,
    /** 可选颜色（ARGB 十六进制字符串） */
    val color: String? = null,
    /** 创建时间（编辑模式需原样保留，新增时为 0 表示用当前时间） */
    val createdAt: Long = 0L,
    /** 数据是否已加载完成（编辑模式需要先读库） */
    val loaded: Boolean = false,
    /** 校验错误提示 */
    val error: String? = null,
    /** 是否已保存成功（用于关闭页面） */
    val saved: Boolean = false
) {
    /** 是否已经选好坐标 */
    val hasLocation: Boolean get() = latitude != 0.0 || longitude != 0.0

    /** 页面标题：新增 / 编辑 */
    val isNew: Boolean get() = pointId == 0L
}

/**
 * 新增/编辑打卡点 ViewModel。
 *
 * 通过 [AddEditPointActivity.EXTRA_POINT_ID] 传入 id 进入编辑模式，
 * 坐标为 (0,0) 视为未选点（GCJ-02 下中国境内不会出现该坐标，作为哨兵值安全）。
 */
class PointEditViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: CheckinPointRepository =
        ServiceLocator.checkinPointRepository(application)

    private val _uiState = MutableStateFlow(PointEditUiState())
    val uiState: StateFlow<PointEditUiState> = _uiState.asStateFlow()

    /** 加载已有打卡点（编辑模式）；pointId 为 0 时直接进入新增模式 */
    fun load(pointId: Long) {
        if (pointId <= 0L) {
            _uiState.update { it.copy(pointId = 0L, loaded = true) }
            return
        }
        viewModelScope.launch {
            val point = runCatching { repository.getById(pointId) }.getOrNull()
            if (point == null) {
                _uiState.update {
                    it.copy(loaded = true, error = "打卡点不存在或已被删除")
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    pointId = point.id,
                    name = point.name,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    radiusMeters = point.radiusMeters,
                    enabled = point.enabled,
                    notifyOnEnter = point.notifyOnEnter,
                    notifyOnExit = point.notifyOnExit,
                    color = point.color,
                    // 原样保留创建时间，避免编辑后被改写
                    createdAt = point.createdAt,
                    loaded = true
                )
            }
        }
    }

    // ---------------------------------------------------------------- 表单字段

    fun setName(value: String) = _uiState.update { it.copy(name = value, error = null) }

    fun setEnabled(value: Boolean) = _uiState.update { it.copy(enabled = value) }

    fun setNotifyOnEnter(value: Boolean) = _uiState.update { it.copy(notifyOnEnter = value) }

    fun setNotifyOnExit(value: Boolean) = _uiState.update { it.copy(notifyOnExit = value) }

    fun setColor(value: String?) = _uiState.update { it.copy(color = value) }

    /** 设置半径（自动收敛到 100~500 米） */
    fun setRadius(radiusMeters: Int) = _uiState.update {
        it.copy(radiusMeters = CheckinPoint.clampRadius(radiusMeters), error = null)
    }

    /** 地图选点/点击落标记后回调坐标 */
    fun setLocation(latitude: Double, longitude: Double, address: String? = null) =
        _uiState.update {
            it.copy(latitude = latitude, longitude = longitude, address = address, error = null)
        }

    // ---------------------------------------------------------------- 保存

    /**
     * 保存打卡点。
     *
     * 校验：名称非空、必须已选点。
     * 通过后写入 Room，并把 [PointEditUiState.saved] 置为 true 由界面关闭页面。
     */
    fun save() {
        val state = _uiState.value
        when {
            state.name.isBlank() -> {
                _uiState.update { it.copy(error = "请填写打卡点名称") }
                return
            }

            !state.hasLocation -> {
                _uiState.update { it.copy(error = "请在地图上点选打卡点位置") }
                return
            }
        }

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val point = CheckinPoint(
                id = state.pointId,
                name = state.name.trim(),
                latitude = state.latitude,
                longitude = state.longitude,
                radiusMeters = CheckinPoint.clampRadius(state.radiusMeters),
                enabled = state.enabled,
                // 新增时用当前时间，编辑时保留原创建时间
                createdAt = if (state.createdAt > 0L) state.createdAt else now,
                color = state.color,
                notifyOnEnter = state.notifyOnEnter,
                notifyOnExit = state.notifyOnExit,
                updatedAt = now
            )

            runCatching { repository.save(point) }
                .onSuccess { newId ->
                    // 顺序很重要：必须先完成围栏刷新，再置 saved。
                    // 置 saved 会让界面立刻 finish()，Activity 销毁时 viewModelScope 被取消，
                    // 若此时刷新协程尚未跑完就会被取消 —— 表现为「有时刷新、有时不刷新」，
                    // 属于最难排查的一类偶发问题。onSuccess 是 inline，可直接调用挂起函数。
                    refreshRunningMonitor()
                    _uiState.update {
                        it.copy(
                            pointId = if (newId > 0L) newId else it.pointId,
                            saved = true,
                            error = null
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "保存失败：${e.message}") }
                }
        }
    }

    /**
     * 让正在运行的监控重新载入打卡点。
     *
     * ⚠️ 这是必须的：本页是**独立 Activity**，保存后前台服务里缓存的打卡点快照仍是旧值。
     * 若不做刷新，用户会遇到「改了位置/半径，但监控仍按旧位置判定」的静默失效 ——
     * 界面显示新坐标，实际提醒却按旧坐标触发，用户几乎无法自查。
     *
     * 只在「用户期望监控开启」时才发刷新：`ACTION_REFRESH` 在服务未运行时会被
     * 提升为「完整启动监控」，否则仅仅编辑一个打卡点就会把监控偷偷打开。
     */
    private suspend fun refreshRunningMonitor() {
        val context = getApplication<Application>()
        val monitoringEnabled = runCatching {
            PreferencesStore.get(context).readMonitoringEnabled()
        }.onFailure {
            Log.w(TAG, "读取监控开关失败，跳过围栏刷新", it)
        }.getOrDefault(false)

        if (!monitoringEnabled) return

        runCatching { CheckinForegroundService.refresh(context) }
            .onFailure { Log.w(TAG, "通知服务刷新围栏失败", it) }
    }

    /** 清除错误提示 */
    fun clearError() = _uiState.update { it.copy(error = null) }

    private companion object {
        const val TAG = "PointEditVM"
    }
}
