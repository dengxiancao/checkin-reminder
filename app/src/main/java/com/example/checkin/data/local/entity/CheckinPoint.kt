// 职责：打卡点实体（Room 表 checkin_point），保存名称、坐标、半径、启用状态与颜色
package com.example.checkin.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 打卡点。
 *
 * 表名/字段严格对应需求：
 * id / name / latitude / longitude / radiusMeters / enabled / createdAt / color
 *
 * 另有两个**业务扩展字段**（延续既有围栏提醒功能，不影响上述必需字段）：
 * [notifyOnEnter] / [notifyOnExit] 用于单独控制进出范围时是否提醒。
 *
 * @param radiusMeters 围栏半径（米），默认 [DEFAULT_RADIUS]，允许 [MIN_RADIUS]~[MAX_RADIUS]
 * @param enabled 为 false 时围栏判定会跳过该点，不产生任何提醒
 * @param color 可选的自定义颜色（ARGB 字符串，如 "#FF1B6C3A"），用于列表与地图标记区分
 */
@Entity(tableName = "checkin_point")
data class CheckinPoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 打卡点名称，例如「公司」「家门口」 */
    @ColumnInfo(name = "name")
    val name: String,

    /** 纬度（GCJ-02 火星坐标系，与高德 SDK 一致） */
    @ColumnInfo(name = "latitude")
    val latitude: Double,

    /** 经度（GCJ-02） */
    @ColumnInfo(name = "longitude")
    val longitude: Double,

    /** 打卡范围半径，单位米，默认 200 */
    @ColumnInfo(name = "radiusMeters", defaultValue = "200")
    val radiusMeters: Int = DEFAULT_RADIUS,

    /** 是否启用该打卡点 */
    @ColumnInfo(name = "enabled", defaultValue = "1")
    val enabled: Boolean = true,

    /** 创建时间戳（毫秒） */
    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    /** 可选颜色（ARGB 十六进制字符串），为 null 时 UI 使用主题色 */
    @ColumnInfo(name = "color")
    val color: String? = null,

    // ---------------- 业务扩展字段 ----------------

    /** 进入范围时是否提醒 */
    @ColumnInfo(name = "notify_on_enter", defaultValue = "1")
    val notifyOnEnter: Boolean = true,

    /** 离开范围时是否提醒 */
    @ColumnInfo(name = "notify_on_exit", defaultValue = "1")
    val notifyOnExit: Boolean = true,

    /** 最近一次更新时间戳（毫秒） */
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 默认半径（米） */
        const val DEFAULT_RADIUS = 200

        /** 半径下限（米） */
        const val MIN_RADIUS = 100

        /** 半径上限（米）：需求提供 500 米档位，故上限取 500 */
        const val MAX_RADIUS = 500

        /** 界面提供的常用半径档位（米） */
        val RADIUS_PRESETS = listOf(100, 200, 300, 500)

        /** 将任意半径收敛到合法区间，避免脏数据导致围栏失效 */
        fun clampRadius(radius: Int): Int = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)
    }
}
