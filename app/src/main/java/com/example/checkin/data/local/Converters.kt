// 职责：Room 类型转换器，负责枚举与可空字段在数据库中的序列化
package com.example.checkin.data.local

import androidx.room.TypeConverter
import com.example.checkin.data.local.entity.CheckInSource

class Converters {

    /** 打卡来源：枚举 -> 字符串 */
    @TypeConverter
    fun fromCheckInSource(source: CheckInSource): String = source.name

    /** 打卡来源：字符串 -> 枚举，未知值降级为 MANUAL，保证老数据可读 */
    @TypeConverter
    fun toCheckInSource(value: String): CheckInSource =
        runCatching { CheckInSource.valueOf(value) }.getOrDefault(CheckInSource.MANUAL)
}
