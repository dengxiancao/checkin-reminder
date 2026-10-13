// 职责：打卡记录实体（Room 表 checkin_record），记录时间、坐标与打卡来源
package com.example.checkin.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 打卡来源。
 *
 * - [MANUAL]：用户在 App 内手动打卡；
 * - [AUTO_CONFIRMED]：系统检测到**进入范围**后推送提醒产生的记录，
 *   用户点击确认打卡时会把该记录就地改写为 [MANUAL]（即「待确认」状态）；
 * - [FENCE_EXIT]：系统检测到**离开范围**产生的记录，仅用于轨迹追溯，
 *   **不参与**「待确认打卡」，因此不会被后续打卡就地改写。
 *
 * 说明：这里只保留枚举语义，**不承载界面文案**。界面文案由 UI 层映射到
 * strings.xml（见 RecordsScreen），避免 data 层硬编码中文导致无法本地化。
 */
enum class CheckInSource {
    /** 用户在 App 内手动打卡 */
    MANUAL,

    /** 检测到进入范围并提醒后，用户确认完成的打卡 */
    AUTO_CONFIRMED,

    /** 检测到离开范围（用于追溯进出配对，不参与待确认打卡） */
    FENCE_EXIT
}

/**
 * 打卡记录。
 *
 * 表名/字段严格对应需求：
 * id / pointId / pointName / latitude / longitude / time / source
 *
 * 外键级联删除：删除打卡点时其记录一并清理。
 * [pointName] 是打卡时的名称快照，即使打卡点改名，历史仍能正确展示。
 */
@Entity(
    tableName = "checkin_record",
    foreignKeys = [
        ForeignKey(
            entity = CheckinPoint::class,
            parentColumns = ["id"],
            childColumns = ["pointId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pointId"), Index("time")]
)
data class CheckinRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** 关联的打卡点 id */
    @ColumnInfo(name = "pointId")
    val pointId: Long,

    /** 打卡点名称快照 */
    @ColumnInfo(name = "pointName")
    val pointName: String,

    /** 打卡时纬度 */
    @ColumnInfo(name = "latitude")
    val latitude: Double,

    /** 打卡时经度 */
    @ColumnInfo(name = "longitude")
    val longitude: Double,

    /** 打卡时间戳（毫秒） */
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),

    /** 打卡来源：手动 / 自动提醒后确认 */
    @ColumnInfo(name = "source")
    val source: CheckInSource = CheckInSource.MANUAL,

    // ---------------- 业务扩展字段（用于围栏事件追溯） ----------------

    /** 触发类型：进入 = true / 离开 = false，手动打卡为 null */
    @ColumnInfo(name = "is_enter")
    val isEnter: Boolean? = null,

    /** 触发时与打卡点中心的距离（米），手动打卡可为 null */
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double? = null,

    /**
     * 备注（用户事后补充，如「忘记打卡，午休补记」「出差在外」）。
     *
     * 可为空：历史数据与自动产生的围栏事件通常没有备注。
     * 列用可空类型是**向后兼容的关键** —— Room 的自动迁移可以直接
     * `ALTER TABLE ... ADD COLUMN note TEXT` 而无需回填默认值。
     */
    @ColumnInfo(name = "note")
    val note: String? = null
)
