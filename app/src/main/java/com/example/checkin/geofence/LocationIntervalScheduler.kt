// 职责：动态定位频率调度器，全部远离时降频、有围栏接近/进入时升频，兼顾实时性与省电
package com.example.checkin.geofence

/**
 * 定位频率档位。
 *
 * 借鉴 AlertSpot 的做法：不追求恒定高频，而是在「有无接近的围栏」之间切换档位。
 */
enum class LocationIntervalTier(val intervalMs: Long) {
    /** 全部打卡点都远离：低频，明显省电 */
    FAR(30_000L),

    /** 有打卡点处于 NEAR / INSIDE：高频，保证进出判定及时 */
    NEAR(5_000L)
}

/**
 * 动态省电调度器。
 *
 * 判定规则：
 * - 只要**存在** NEAR 或 INSIDE 的打卡点 → 进入 [LocationIntervalTier.NEAR]（5 秒）；
 * - 所有打卡点距离都超过「NEAR 阈值 + 迟滞余量」→ 回到 [LocationIntervalTier.FAR]（30 秒）。
 *
 * 迟滞（hysteresis）用于避免在阈值附近来回抖动导致频率反复切换：
 * 进入 NEAR 档用 `nearThreshold`，退出 NEAR 档要求超过 `nearThreshold + hysteresis`。
 *
 * 该类是纯逻辑，便于单测；真正的频率应用由前台服务负责（需要重启定位客户端生效）。
 *
 * @param hysteresisMeters 退出高频档所需的额外距离余量（米）
 * @param minSwitchIntervalMs 两次档位切换的最小间隔，防止频繁重启定位客户端
 */
class LocationIntervalScheduler(
    private val hysteresisMeters: Double = DEFAULT_HYSTERESIS_METERS,
    private val minSwitchIntervalMs: Long = DEFAULT_MIN_SWITCH_INTERVAL_MS
) {

    /** 当前档位 */
    @Volatile
    var currentTier: LocationIntervalTier = LocationIntervalTier.FAR
        private set

    /** 上次切换档位的时间戳（用于节流） */
    private var lastSwitchAt: Long = Long.MIN_VALUE

    /** 调度原因，便于日志观察 */
    @Volatile
    var lastReason: String = "初始化"
        private set

    /**
     * 根据当前所有打卡点的状态与最小距离，计算目标档位。
     *
     * @param anyNearOrInside 是否存在 NEAR / INSIDE 的打卡点
     * @param minDistanceMeters 到最近打卡点的距离（米），无打卡点为 null
     * @param nearestNearThreshold 最近打卡点的 NEAR 阈值（米），无打卡点为 null
     * @param now 当前时间戳（毫秒），便于测试注入
     * @return 本次是否需要切换档位（true 表示调用方应重启定位以生效）
     */
    fun evaluate(
        anyNearOrInside: Boolean,
        minDistanceMeters: Double?,
        nearestNearThreshold: Double?,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        // 没有任何打卡点：保持低频即可
        if (minDistanceMeters == null || nearestNearThreshold == null) {
            return switchTo(LocationIntervalTier.FAR, now) { "无启用中的打卡点" }
        }

        val target = when {
            // 有接近/进入的点，必须高频
            anyNearOrInside -> LocationIntervalTier.NEAR

            // 距离已超过「NEAR 阈值 + 迟滞」，认为确实远离
            minDistanceMeters > nearestNearThreshold + hysteresisMeters ->
                LocationIntervalTier.FAR

            // 处于迟滞区间内：保持当前档位，避免抖动
            else -> currentTier
        }

        val reason = when (target) {
            LocationIntervalTier.NEAR -> "存在接近/进入的打卡点"
            LocationIntervalTier.FAR -> "全部打卡点远离（最近 %.0f 米）".format(minDistanceMeters)
        }

        return switchTo(target, now) { reason }
    }

    /** 强制回到初始档位（停止监控时调用） */
    fun reset() {
        currentTier = LocationIntervalTier.FAR
        lastSwitchAt = Long.MIN_VALUE
        lastReason = "已重置"
    }

    /**
     * 尝试切换档位。
     *
     * @param reasonProvider 仅在真要切换时才求值，避免无谓的字符串格式化
     * @return true 表示档位发生变化，调用方需要让新的间隔生效
     */
    private inline fun switchTo(
        target: LocationIntervalTier,
        now: Long,
        reasonProvider: () -> String
    ): Boolean {
        if (target == currentTier) return false

        // 节流：距离上次切换太近则暂不切换，避免频繁重启定位客户端
        if (lastSwitchAt != Long.MIN_VALUE && now - lastSwitchAt < minSwitchIntervalMs) {
            return false
        }

        currentTier = target
        lastSwitchAt = now
        lastReason = reasonProvider()
        return true
    }

    companion object {
        /** 退出高频档所需的额外余量（米） */
        const val DEFAULT_HYSTERESIS_METERS = 100.0

        /** 两次档位切换的最小间隔：20 秒 */
        const val DEFAULT_MIN_SWITCH_INTERVAL_MS = 20_000L
    }
}
