// 职责：Debug 页 ViewModel，持有 Locator 并暴露单次/连续定位状态与回调记录
package com.example.checkin.ui.debug

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.checkin.geofence.LocationSnapshot
import com.example.checkin.util.LocationError
import com.example.checkin.util.LocationOutcome
import com.example.checkin.util.Locator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 一次定位回调的记录（用于验证「按间隔持续回调 / 停止后不再回调」） */
data class LocationLogEntry(
    val sequence: Int,
    val success: Boolean,
    /** 回调接收时刻 */
    val receivedAt: Long,
    /** 与上一次回调的间隔（毫秒），首次为 null */
    val gapMs: Long?,
    val summary: String
)

/** Debug 页 UI 状态 */
data class DebugUiState(
    /** 是否正在定位 */
    val isLocating: Boolean = false,
    /** 是否为连续定位模式 */
    val isContinuous: Boolean = false,
    /** 最近一次成功定位 */
    val lastLocation: LocationSnapshot? = null,
    /** 最近一次错误 */
    val lastError: LocationError? = null,
    /** 连续定位间隔配置（毫秒），可在界面调整 */
    val intervalMs: Long = Locator.DEFAULT_INTERVAL_MS,
    /** 单次定位超时配置（毫秒） */
    val timeoutMs: Long = Locator.DEFAULT_TIMEOUT_MS,
    /** 回调日志，最新在前 */
    val logs: List<LocationLogEntry> = emptyList(),
    /** 已收到的回调次数 */
    val callbackCount: Int = 0
)

/**
 * Debug 页状态管理。
 *
 * 之所以放在 ViewModel 里持有 [Locator]：屏幕旋转等配置变更时定位不会中断，
 * 且 onCleared 能可靠地释放定位资源。
 */
class DebugViewModel(application: Application) : AndroidViewModel(application) {

    private val locator = Locator(application)

    private val _uiState = MutableStateFlow(DebugUiState())
    val uiState: StateFlow<DebugUiState> = _uiState.asStateFlow()

    private var sequence = 0
    private var lastCallbackAt: Long? = null

    // ---------------------------------------------------------------- 单次定位

    /** 启动单次定位（按当前配置的超时时间） */
    fun requestSingle() {
        val timeout = _uiState.value.timeoutMs
        _uiState.update { it.copy(isLocating = true, isContinuous = false, lastError = null) }

        locator.requestSingleLocation(timeoutMs = timeout) { outcome ->
            handleOutcome(outcome, isContinuousMode = false)
        }
    }

    // ---------------------------------------------------------------- 连续定位

    /** 开始连续定位（按当前配置的间隔） */
    fun startContinuous() {
        val state = _uiState.value
        resetLog()
        _uiState.update {
            it.copy(isLocating = true, isContinuous = true, lastError = null)
        }

        locator.startContinuousLocation(
            intervalMs = state.intervalMs,
            httpTimeOutMs = state.timeoutMs
        ) { outcome ->
            handleOutcome(outcome, isContinuousMode = true)
        }
    }

    /** 停止定位 */
    fun stop() {
        locator.stopLocation()
        _uiState.update { it.copy(isLocating = false, isContinuous = false) }
        // 说明：本页日志属于**开发诊断信息**（含错误码/坐标/精度等运行期数值），
        // 与面向用户的界面文案性质不同，按惯例不进 strings.xml（避免引入无意义的
        // 本地化负担）。面向用户的界面文案已全部资源化。
        appendLog(
            success = true,
            summary = "已停止定位（此后不应再收到回调）"
        )
    }

    // ---------------------------------------------------------------- 配置

    /** 修改连续定位间隔 */
    fun setInterval(intervalMs: Long) {
        _uiState.update { it.copy(intervalMs = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)) }
    }

    /** 修改单次定位超时 */
    fun setTimeout(timeoutMs: Long) {
        _uiState.update { it.copy(timeoutMs = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)) }
    }

    /** 清空回调日志 */
    fun clearLog() = resetLog()

    // ---------------------------------------------------------------- 内部

    /** 统一处理定位结果 */
    private fun handleOutcome(outcome: LocationOutcome, isContinuousMode: Boolean) {
        when (outcome) {
            is LocationOutcome.Success -> {
                val snapshot = outcome.location
                _uiState.update {
                    it.copy(
                        lastLocation = snapshot,
                        lastError = null,
                        // 单次定位拿到结果后自动结束
                        isLocating = if (isContinuousMode) it.isLocating else false
                    )
                }
                appendLog(
                    success = true,
                    summary = String.format(
                        Locale.CHINA,
                        "lat/lng = %.6f, %.6f  精度 %.1f m  来源 %s",
                        snapshot.latitude,
                        snapshot.longitude,
                        snapshot.accuracy,
                        snapshot.sourceName
                    )
                )
            }

            is LocationOutcome.Failure -> {
                _uiState.update {
                    it.copy(
                        lastError = outcome.error,
                        isLocating = if (isContinuousMode) it.isLocating else false
                    )
                }
                appendLog(
                    success = false,
                    summary = "错误码 ${outcome.error.errorCode}：${outcome.error.message}"
                )
                Log.e(TAG, "定位失败：${outcome.error}")
            }
        }
    }

    /** 追加一条回调日志并计算与上次回调的间隔 */
    private fun appendLog(success: Boolean, summary: String) {
        val now = System.currentTimeMillis()
        val gap = lastCallbackAt?.let { now - it }
        lastCallbackAt = now
        sequence += 1

        val entry = LocationLogEntry(
            sequence = sequence,
            success = success,
            receivedAt = now,
            gapMs = gap,
            summary = summary
        )

        _uiState.update { state ->
            state.copy(
                callbackCount = state.callbackCount + 1,
                // 最多保留 60 条，避免长跑内存增长
                logs = (listOf(entry) + state.logs).take(MAX_LOG_SIZE)
            )
        }
    }

    /** 重置日志与计数 */
    private fun resetLog() {
        sequence = 0
        lastCallbackAt = null
        _uiState.update { it.copy(logs = emptyList(), callbackCount = 0) }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 销毁时务必释放定位，防止后台持续耗电
        locator.stopLocation()
    }

    companion object {
        private const val TAG = "DebugViewModel"

        /** 日志上限 */
        private const val MAX_LOG_SIZE = 60

        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_INTERVAL_MS = 30_000L
        const val MIN_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 60_000L

        /** 时间格式化（用于日志展示，静态复用线程安全的 DateTimeFormatter） */
        private val TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.CHINA)

        /** 时间格式化（用于日志展示） */
        fun formatTime(timestamp: Long): String =
            Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(TIME_FMT)
    }
}
