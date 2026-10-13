// 职责：统计领域模型（纯 Kotlin，无 Android 依赖，便于 JVM 单测）
package com.example.checkin.stats

import java.time.LocalDate
import java.time.YearMonth

/**
 * 某一天、某个打卡点的聚合结果。
 *
 * 所有时间字段都是**当日内被裁剪过的**时间戳（毫秒）：
 * 例如一段 22:00→次日 06:00 的在岗区间，在「今天」这一行里
 * [firstEnterAt] 是今天 00:00，[lastExitAt] 是今天 06:00，
 * 这样跨午夜的区间在两天的明细里读起来都自然。
 */
data class DailyPointStat(
    val day: LocalDate,
    val pointId: Long,
    val pointName: String,
    /** 当日首次进入时间（毫秒，已裁剪到当日） */
    val firstEnterAt: Long?,
    /** 当日最后离开时间（毫秒，已裁剪到当日）；仍在岗为 null */
    val lastExitAt: Long?,
    /** 当日在岗时长（分钟，多段取**并集**，重叠不重复计算） */
    val dwellMinutes: Int,
    /** 当日区间段数（并集后的段数） */
    val segments: Int,
    /** 当日「进入」事件数（按事件的原始发生时间归属） */
    val enterCount: Int,
    /** 当日「离开」事件数 */
    val exitCount: Int,
    /** 当日纯手动打卡次数（无进出上下文，不参与区间配对） */
    val manualCount: Int,
    /** 当日区间是否全部闭合；仍有一段在岗时为 false */
    val closed: Boolean
) {
    /** 当日事件总数（进入 + 离开 + 手动） */
    val eventCount: Int get() = enterCount + exitCount + manualCount
}

/**
 * 日历格子的状态。
 *
 * 刻意用**语义枚举**而不是颜色：颜色由 UI 按主题映射，
 * 数据层不承载视觉信息，这样换主题/接入深色模式都不需要改这里。
 */
enum class DayStatus {
    /** 当天无任何记录 */
    NO_RECORD,

    /** 当天有记录但区间未闭合（仍在岗） */
    ENTERED_ONLY,

    /** 当天完成了一段完整在岗区间 */
    COMPLETED,

    /** 当天有多段在岗区间（多次进出） */
    MULTI_SEGMENT
}

/** 日历单元格 */
data class DayCell(
    val date: LocalDate,
    val status: DayStatus,
    /** 当天记录条数 */
    val recordCount: Int,
    /** 当天累计在岗分钟 */
    val dwellMinutes: Int,
    val isToday: Boolean
)

/** 一个月的日历网格：前导空位 + 日期格子 */
data class CalendarMonth(
    val yearMonth: YearMonth,
    /** 首行需要留空的格子数（由每周起始日决定） */
    val leadingBlanks: Int,
    val cells: List<DayCell>
)

/**
 * 一段日期区间的汇总，用于统计页。
 *
 * @param perDayMinutes 逐日累计在岗分钟，用于自绘柱状图（按日期升序）
 */
data class RangeSummary(
    val start: LocalDate,
    val end: LocalDate,
    /** 区间内有记录的天数 */
    val activeDays: Int,
    /** 区间内累计在岗分钟 */
    val totalDwellMinutes: Int,
    /** 按「有记录的天数」平均的在岗分钟 */
    val averageDwellMinutes: Int,
    /** 最长连续打卡天数 */
    val maxStreak: Int,
    /** 当前连续打卡天数（自 end 向前回推） */
    val currentStreak: Int,
    val enterCount: Int,
    val exitCount: Int,
    val manualCount: Int,
    val perDayMinutes: List<Pair<LocalDate, Int>>
) {
    /** 区间内单日最长在岗分钟（柱状图归一化用） */
    val peakMinutes: Int get() = perDayMinutes.maxOfOrNull { it.second } ?: 0

    companion object {
        /** 空汇总（无数据时的安全初值） */
        fun empty(start: LocalDate, end: LocalDate): RangeSummary = RangeSummary(
            start = start,
            end = end,
            activeDays = 0,
            totalDwellMinutes = 0,
            averageDwellMinutes = 0,
            maxStreak = 0,
            currentStreak = 0,
            enterCount = 0,
            exitCount = 0,
            manualCount = 0,
            perDayMinutes = emptyList()
        )
    }
}
