// 职责：打卡确认页 ViewModel，获取当前位置并完成打卡落库
package com.example.checkin.ui.confirm

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.data.repository.CheckinPointRepository
import com.example.checkin.data.repository.CheckinRecordRepository
import com.example.checkin.data.repository.ServiceLocator
import com.example.checkin.geofence.GeoUtils
import com.example.checkin.notify.Notifier
import com.example.checkin.util.LocationOutcome
import com.example.checkin.util.Locator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 打卡确认页 UI 状态 */
data class CheckinConfirmUiState(
    /** 是否加载中（读取打卡点 + 首次定位） */
    val loading: Boolean = true,
    /** 目标打卡点；为 null 表示已删除或不存在 */
    val point: CheckinPoint? = null,
    /** 是否为「进入」触发（否则为离开触发） */
    val isEnter: Boolean = true,
    /** 当前与打卡点的距离（米），未取到为 null */
    val currentDistanceMeters: Double? = null,
    /** 定位是否进行中 */
    val locating: Boolean = false,
    /** 定位失败提示 */
    val locationError: String? = null,
    /** 是否正在打卡（落库中） */
    val submitting: Boolean = false,
    /** 打卡成功后的信息 */
    val success: CheckinSuccess? = null,
    /** 通用错误提示 */
    val error: String? = null
)

/** 打卡成功信息 */
data class CheckinSuccess(
    val pointName: String,
    val time: Long,
    val distanceMeters: Double?
)

/**
 * 打卡确认 ViewModel。
 *
 * 流程：
 * 1. 加载打卡点并**自动获取一次当前位置**，算出实时距离（失败也可继续打卡）；
 * 2. 用户点「立即打卡」→ 用当前位置调用 [CheckinRecordRepository.checkIn] 落库；
 * 3. 成功后发送打卡成功通知并把页面切到成功状态。
 */
class CheckinConfirmViewModel(application: Application) : AndroidViewModel(application) {

    private val pointRepository: CheckinPointRepository =
        ServiceLocator.checkinPointRepository(application)

    private val recordRepository: CheckinRecordRepository =
        ServiceLocator.checkinRecordRepository(application)

    /** 每次进入页面新建一个 Locator，onCleared 时释放 */
    private val locator = Locator(application)

    private val _uiState = MutableStateFlow(CheckinConfirmUiState())
    val uiState: StateFlow<CheckinConfirmUiState> = _uiState.asStateFlow()

    /** 进入页面：加载打卡点 + 定位 */
    fun load(pointId: Long, isEnter: Boolean, initialDistance: Double?) {
        _uiState.update {
            it.copy(
                isEnter = isEnter,
                currentDistanceMeters = initialDistance,
                loading = true
            )
        }

        viewModelScope.launch {
            val point = runCatching { pointRepository.getById(pointId) }.getOrNull()
            _uiState.update { it.copy(point = point, loading = false) }

            if (point == null) {
                Log.w(TAG, "打卡点 $pointId 不存在或已删除")
                return@launch
            }
            // 自动刷新一次实时距离
            refreshLocation()
        }
    }

    /** 获取当前位置并更新距离（供「立即打卡」与刷新按钮共用） */
    fun refreshLocation() {
        val point = _uiState.value.point ?: return
        _uiState.update { it.copy(locating = true, locationError = null) }

        locator.requestSingleLocation(timeoutMs = LOCATE_TIMEOUT_MS) { outcome ->
            when (outcome) {
                is LocationOutcome.Success -> {
                    val snapshot = outcome.location
                    val distance = GeoUtils.distanceMeters(
                        point.latitude, point.longitude,
                        snapshot.latitude, snapshot.longitude
                    )
                    _uiState.update {
                        it.copy(
                            locating = false,
                            currentDistanceMeters = distance,
                            locationError = null
                        )
                    }
                    Log.i(TAG, "当前位置已获取，距离 ${point.name} 约 ${distance.toInt()} 米")
                }

                is LocationOutcome.Failure -> {
                    _uiState.update {
                        it.copy(locating = false, locationError = outcome.error.message)
                    }
                    Log.w(TAG, "定位失败：${outcome.error.errorCode} ${outcome.error.message}")
                }
            }
        }
    }

    /**
     * 立即打卡。
     *
     * 会先取当前位置再落库：确保记录里存的是**打卡时刻的真实坐标**，
     * 而不是触发围栏时的旧坐标。
     */
    fun checkIn() {
        val state = _uiState.value
        val point = state.point ?: return
        if (state.submitting) return

        _uiState.update { it.copy(submitting = true, error = null, locationError = null) }

        locator.requestSingleLocation(timeoutMs = LOCATE_TIMEOUT_MS) { outcome ->
            if (outcome is LocationOutcome.Failure) {
                // 定位失败不阻断打卡：位置信息是辅助信息，不应该让用户打不了卡
                Log.w(TAG, "打卡时定位失败，仍尝试落库：${outcome.error.message}")
                _uiState.update { it.copy(locationError = outcome.error.message) }
                submitCheckIn(point, latitude = point.latitude, longitude = point.longitude)
                return@requestSingleLocation
            }

            val snapshot = (outcome as LocationOutcome.Success).location
            submitCheckIn(point, snapshot.latitude, snapshot.longitude)
        }
    }

    /** 真正落库 */
    private fun submitCheckIn(point: CheckinPoint, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val distance = GeoUtils.distanceMeters(
                point.latitude, point.longitude, latitude, longitude
            )
            runCatching {
                recordRepository.checkIn(
                    point = point,
                    latitude = latitude,
                    longitude = longitude,
                    distanceMeters = distance
                )
            }.onSuccess { recordId ->
                val success = CheckinSuccess(
                    pointName = point.name,
                    time = System.currentTimeMillis(),
                    distanceMeters = distance
                )
                _uiState.update {
                    it.copy(
                        submitting = false,
                        success = success,
                        currentDistanceMeters = distance
                    )
                }
                // 打卡成功通知
                Notifier.notifyCheckinSuccess(getApplication(), point.name)
                Log.i(
                    TAG,
                    "打卡成功：${point.name}，记录 id=$recordId，距离 ${distance.toInt()} 米"
                )
            }.onFailure { e ->
                Log.e(TAG, "打卡落库失败", e)
                _uiState.update {
                    it.copy(submitting = false, error = "打卡失败：${e.message}")
                }
            }
        }
    }

    /** 清除错误提示 */
    fun clearError() = _uiState.update { it.copy(error = null) }

    override fun onCleared() {
        super.onCleared()
        // 页面销毁时释放定位，避免后台继续耗电
        locator.stopLocation()
    }

    companion object {
        private const val TAG = "CheckinConfirmVM"

        /** 打卡确认页的定位超时：10 秒 */
        private const val LOCATE_TIMEOUT_MS = 10_000L
    }
}
