// 职责：围栏状态机，维护每个打卡点的 OUTSIDE/NEAR/INSIDE/LEFT 状态并产出进入/离开事件
package com.example.checkin.geofence

import androidx.annotation.VisibleForTesting
import com.example.checkin.data.local.entity.CheckinPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 打卡点围栏状态。
 *
 * 状态流转（需求规定）：
 * ```
 *  OUTSIDE ──距离 <= radius──> INSIDE   （触发「进入」事件）
 *  OUTSIDE ──距离 <= NEAR 阈值──> NEAR  （接近预热，仅用于提高定位频率，不触发通知）
 *  NEAR    ──距离 <= radius──> INSIDE   （触发「进入」事件）
 *  INSIDE  ──距离 >  radius──> LEFT     （触发「离开」事件）
 *  LEFT    ──距离 <= radius──> INSIDE   （再次进入，重新触发「进入」）
 *  LEFT    ──距离 >  NEAR 阈值──> OUTSIDE
 * ```
 * [UNKNOWN] 仅作为「尚未判定的初始态」，首次判定会直接落到 OUTSIDE/NEAR/INSIDE，不产生事件。
 */
enum class FenceState {
    /** 尚未判定（初始态） */
    UNKNOWN,

    /** 远离：距离大于 NEAR 阈值 */
    OUTSIDE,

    /** 接近预热：已在 NEAR 阈值内但仍在打卡半径之外 */
    NEAR,

    /** 已进入打卡范围 */
    INSIDE,

    /** 刚离开打卡范围 */
    LEFT
}

/** 触发事件类型 */
enum class FenceEventType {
    /** 进入打卡范围 */
    ENTER,

    /** 离开打卡范围 */
    EXIT
}

/** 状态机产出的事件 */
data class FenceEvent(
    val checkPoint: CheckinPoint,
    val type: FenceEventType,
    /** 事件发生后的新状态（ENTER 对应 INSIDE，EXIT 对应 LEFT） */
    val newState: FenceState,
    val distanceMeters: Double,
    /** 触发时使用的定位快照 */
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

/** 某个打卡点的围栏运行时快照，供 UI 订阅展示 */
data class FenceRuntimeState(
    val pointId: Long,
    val state: FenceState = FenceState.UNKNOWN,
    /** 最近一次计算出的距离（米），未判定过为 null */
    val distanceMeters: Double? = null,
    /** 该打卡点生效的半径（米） */
    val radiusMeters: Int = 0,
    /** 最近一次状态变化的时间戳 */
    val updatedAt: Long = 0L
)

/**
 * 围栏状态机。
 *
 * 设计要点：
 * - **纯逻辑**：不持有 Context、不做 IO，所有输入是「打卡点列表 + 一个定位快照」，
 *   输出是事件列表，因此可以在 JVM 上完整单测；
 * - **状态用 StateFlow 暴露**：UI 可直接 `collect` 展示每个点的实时状态；
 * - **NEAR 只用于调频**：接近预热状态不产生任何通知，避免打扰用户；
 * - **防抖**：可选要求连续 [confirmSamples] 次判定一致才切换状态（默认 1，即立即响应）。
 *
 * @param nearRatio NEAR 阈值 = max(radius × nearRatio, radius + nearExtraMeters)
 * @param nearExtraMeters NEAR 阈值的额外余量（米）
 * @param confirmSamples 状态切换所需的连续一致判定次数
 * @param timeProvider 时间源，便于测试注入固定时钟
 */
class FenceStateMachine(
    private val nearRatio: Double = DEFAULT_NEAR_RATIO,
    private val nearExtraMeters: Double = DEFAULT_NEAR_EXTRA_METERS,
    private val confirmSamples: Int = 1,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    /** 各打卡点的运行时状态，Key 为打卡点 id */
    private val _states = MutableStateFlow<Map<Long, FenceRuntimeState>>(emptyMap())
    val states: StateFlow<Map<Long, FenceRuntimeState>> = _states.asStateFlow()

    /** 候选状态累计（仅当 confirmSamples > 1 时使用） */
    private val pending = mutableMapOf<Long, Pair<FenceState, Int>>()

    /**
     * 用一次定位快照推进所有打卡点的状态。
     *
     * @return 本次真正发生切换而产出的事件（可能为空）
     */
    fun evaluate(
        points: List<CheckinPoint>,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float = 0f,
        timestamp: Long = timeProvider()
    ): List<FenceEvent> {
        if (points.isEmpty()) {
            if (_states.value.isNotEmpty()) _states.value = emptyMap()
            return emptyList()
        }

        val events = mutableListOf<FenceEvent>()
        val aliveIds = points.map { it.id }.toSet()

        // 在**本地副本**上完成全部计算，循环结束后一次性写回 StateFlow。
        // 原实现每命中一个分支就 update 一次，每次 update 都要拷贝整个 Map 并发射一次，
        // 单次定位最多产生 2N 次发射（N = 打卡点数量），UI 侧 combine / LazyColumn 会跟着
        // 重组 N 次。改为「本地累积 + 单次写回」后，单次定位最多只发射一次。
        val next = LinkedHashMap(_states.value)

        // 清理已被删除的打卡点状态与候选状态，避免脏状态导致误报
        pending.keys.retainAll(aliveIds)
        next.keys.retainAll(aliveIds)

        for (point in points) {
            if (!point.enabled) {
                // 停用的点不参与判定，同时清掉其状态，避免再次启用时误报
                pending.remove(point.id)
                next.remove(point.id)
                continue
            }

            val distance = GeoUtils.distanceMeters(
                point.latitude, point.longitude, latitude, longitude
            )

            // 定位精度作为容差向外放宽，但最多放宽 MAX_TOLERANCE_METERS，避免精度极差时误判
            val tolerance = accuracyMeters.coerceIn(0f, MAX_TOLERANCE_METERS)
            val insideThreshold = point.radiusMeters + tolerance
            val nearThreshold = nearThresholdOf(point.radiusMeters)

            val current = next[point.id]
            val previous = current?.state ?: FenceState.UNKNOWN

            // 依据需求规则推导目标状态
            val target = when {
                distance <= insideThreshold -> FenceState.INSIDE
                previous == FenceState.INSIDE -> FenceState.LEFT
                distance <= nearThreshold -> FenceState.NEAR
                previous == FenceState.NEAR || previous == FenceState.LEFT -> FenceState.OUTSIDE
                else -> FenceState.OUTSIDE
            }

            // 首次判定：只建立基线，不产生事件（避免启动瞬间误报进入）
            if (previous == FenceState.UNKNOWN) {
                next[point.id] = FenceRuntimeState(
                    pointId = point.id,
                    state = target,
                    distanceMeters = distance,
                    radiusMeters = point.radiusMeters,
                    updatedAt = timestamp
                )
                continue
            }

            if (target == previous) {
                pending.remove(point.id)
                // 状态不变也要刷新距离，UI 需要展示实时距离
                next[point.id] = (current ?: FenceRuntimeState(point.id))
                    .copy(distanceMeters = distance, updatedAt = current?.updatedAt ?: timestamp)
                continue
            }

            // 需要防抖时累计候选次数
            if (confirmSamples > 1) {
                val (candidate, count) = pending[point.id] ?: (target to 0)
                val newCount = if (candidate == target) count + 1 else 1
                if (newCount < confirmSamples) {
                    pending[point.id] = target to newCount
                    // 尚未确认，仅刷新距离
                    next[point.id] = (current ?: FenceRuntimeState(point.id))
                        .copy(distanceMeters = distance)
                    continue
                }
            }
            pending.remove(point.id)

            next[point.id] = FenceRuntimeState(
                pointId = point.id,
                state = target,
                distanceMeters = distance,
                radiusMeters = point.radiusMeters,
                updatedAt = timestamp
            )

            // 只有 INSIDE / LEFT 才是需要通知用户的业务事件
            val eventType = when (target) {
                FenceState.INSIDE -> FenceEventType.ENTER
                FenceState.LEFT -> FenceEventType.EXIT
                else -> null
            }
            if (eventType != null) {
                events += FenceEvent(
                    checkPoint = point,
                    type = eventType,
                    newState = target,
                    distanceMeters = distance,
                    latitude = latitude,
                    longitude = longitude,
                    timestamp = timestamp
                )
            }
        }

        // 单次发射：内容未变化时不写回，避免无意义的 UI 重组
        if (next != _states.value) {
            _states.value = next
        }

        return events
    }

    /** 计算某半径对应的 NEAR 阈值（米） */
    fun nearThresholdOf(radiusMeters: Int): Double =
        maxOf(radiusMeters * nearRatio, radiusMeters + nearExtraMeters)

    /** 是否存在处于 NEAR 状态的打卡点（用于判定是否需要提高定位频率） */
    fun hasNearPoint(): Boolean =
        _states.value.values.any { it.state == FenceState.NEAR }

    /** 是否存在处于 INSIDE 状态的打卡点 */
    fun hasInsidePoint(): Boolean =
        _states.value.values.any { it.state == FenceState.INSIDE }

    /**
     * 取某个打卡点的当前状态。
     *
     * 仅用于单元测试直接断言内部状态；生产代码请订阅 [states]（UI 展示用）
     * 或使用 [hasNearPoint] / [hasInsidePoint] / [minDistance]（调频用）。
     */
    @VisibleForTesting
    fun stateOf(pointId: Long): FenceState =
        _states.value[pointId]?.state ?: FenceState.UNKNOWN

    /**
     * 取当前所有启用点中的最小距离（米）。
     *
     * 用于动态调频：全部远离时降到低频，有接近/进入的点时升到高频。
     */
    fun minDistance(): Double? =
        _states.value.values.mapNotNull { it.distanceMeters }.minOrNull()

    /**
     * 重置全部状态。
     *
     * 停止监控时由 [GeofenceManager.stop] 调用：否则再次开启监控时会残留
     * 上一次的 INSIDE/LEFT 状态，导致重新建立基线前就误发进入/离开事件。
     */
    fun reset() {
        pending.clear()
        _states.value = emptyMap()
    }

    companion object {
        /** NEAR 阈值默认倍数：半径的 1.5 倍 */
        const val DEFAULT_NEAR_RATIO = 1.5

        /** NEAR 阈值默认额外余量（米），保证小半径时也有足够预热距离 */
        const val DEFAULT_NEAR_EXTRA_METERS = 50.0

        /** 定位精度容差上限（米） */
        const val MAX_TOLERANCE_METERS = 50f
    }
}
