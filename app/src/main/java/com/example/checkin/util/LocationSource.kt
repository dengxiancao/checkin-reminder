// 职责：定位来源枚举与高德 locationType 映射，对外只暴露 gps / network 等可读字符串
package com.example.checkin.util

import com.amap.api.location.AMapLocation

/**
 * 定位来源。
 *
 * 高德 `AMapLocation.getLocationType()` 会把结果分成十几种细分类型，
 * 业务上只需要区分「卫星定位」与「网络定位」两类，因此收敛为：
 * - [GPS]：卫星定位（含北斗），精度最高
 * - [NETWORK]：网络定位（WiFi / 基站 / 高德自研），室内可用但精度较低
 * - [CACHE]：上次定位缓存 / 离线定位结果
 * - [UNKNOWN]：无法识别
 */
enum class LocationSource(val displayName: String, val isSatellite: Boolean) {
    /** 卫星定位（gps / 北斗） */
    GPS("gps", true),

    /** 网络定位（wifi / 基站 / 高德融合） */
    NETWORK("network", false),

    /** 缓存或离线定位结果，时效性较差 */
    CACHE("cache", false),

    /** 未知来源 */
    UNKNOWN("unknown", false);

    companion object {
        /**
         * 把高德 locationType 映射为 [LocationSource]。
         *
         * 该函数为纯函数，便于 JVM 单测覆盖所有分支。
         */
        fun fromAmapLocationType(locationType: Int): LocationSource = when (locationType) {
            AMapLocation.LOCATION_TYPE_GPS -> GPS

            AMapLocation.LOCATION_TYPE_WIFI,
            AMapLocation.LOCATION_TYPE_CELL,
            AMapLocation.LOCATION_TYPE_AMAP,
            AMapLocation.LOCATION_TYPE_NETWORK,
            AMapLocation.LOCATION_TYPE_COARSE_LOCATION -> NETWORK

            AMapLocation.LOCATION_TYPE_FAST,
            AMapLocation.LOCATION_TYPE_FIX_CACHE,
            AMapLocation.LOCATION_TYPE_LAST_LOCATION_CACHE,
            AMapLocation.LOCATION_TYPE_OFFLINE -> CACHE

            // LOCATION_TYPE_SAME_REQ 表示与上一次结果相同，来源无从判断
            else -> UNKNOWN
        }
    }
}
