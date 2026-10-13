// 职责：月历网格构建（纯函数，无 Android 依赖）
package com.example.checkin.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历网格构建器。
 *
 * 只负责把「某年某月 + 统计数据」转换成可渲染的格子列表，
 * 网格布局（7 列）与配色留给 UI —— 这样单测可以直接断言格子状态，
 * 不需要拉起 Compose。
 */
object MonthCalendarModel {

    /**
     * 构建一个月历网格。
     *
     * @param yearMonth     目标月份
     * @param stats         [StatsCalculator.calculate] 的输出
     * @param today         用于标记「今天」的日期
     * @param firstDayOfWeek 每周起始日，默认周一（中国大陆习惯）
     */
    fun build(
        yearMonth: YearMonth,
        stats: Map<LocalDate, List<DailyPointStat>>,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY
    ): CalendarMonth {
        val first = yearMonth.atDay(1)
        val length = yearMonth.lengthOfMonth()

        val leadingBlanks = ((first.dayOfWeek.value - firstDayOfWeek.value) + 7) % 7

        val cells = (1..length).map { dayOfMonth ->
            val date = yearMonth.atDay(dayOfMonth)
            val forDay = stats[date].orEmpty()
            DayCell(
                date = date,
                status = StatsCalculator.statusOf(forDay),
                recordCount = forDay.sumOf { it.eventCount },
                dwellMinutes = forDay.sumOf { it.dwellMinutes },
                isToday = date == today
            )
        }

        return CalendarMonth(
            yearMonth = yearMonth,
            leadingBlanks = leadingBlanks,
            cells = cells
        )
    }
}
