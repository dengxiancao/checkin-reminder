// 职责：地理计算工具，纯 Kotlin 实现球面距离与围栏判定，便于单元测试
package com.example.checkin.geofence

import androidx.annotation.VisibleForTesting
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 地球平均半径（米） */
private const val EARTH_RADIUS_METERS = 6_371_008.8

object GeoUtils {

    /**
     * Haversine 公式计算两点间球面距离，单位米。
     *
     * 这里自己实现而不用 `AMapUtils.calculateLineDistance`：后者只存在于**地图 SDK**
     * （map3d）中，而距离判定属于定位链路，让围栏逻辑依赖地图 SDK 会引入不必要的耦合，
     * 且无法在纯 JVM 单测中验证。自实现后 [GeofenceEvaluator] / 打卡确认页
     * 都可独立于地图模块工作。
     */
    fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)

        val h = sin(dLat / 2).let { it * it } +
            cos(radLat1) * cos(radLat2) * sin(dLon / 2).let { it * it }

        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    /**
     * 判断坐标是否落在以 ([centerLat], [centerLon]) 为圆心、[radiusMeters] 为半径的圆内。
     *
     * [accuracyMeters] 为当前定位精度，判定时向半径外扩一点，减少定位漂移造成的抖动。
     */
    fun isInside(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Int,
        currentLat: Double,
        currentLon: Double,
        accuracyMeters: Float = 0f
    ): Boolean {
        val distance = distanceMeters(centerLat, centerLon, currentLat, currentLon)
        // 精度作为容差，但最多放宽 50 米，避免精度极差时误判为已到达
        val tolerance = accuracyMeters.coerceIn(0f, 50f)
        return distance <= radiusMeters + tolerance
    }
}
