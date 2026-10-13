// 职责：月历网格构建单元测试，锁定前导空位、格子数量与状态映射
package com.example.checkin

import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinRecord
import com.example.checkin.stats.DayStatus
import com.example.checkin.stats.MonthCalendarModel
import com.example.checkin.stats.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * 月历网格的用例。
 *
 * 重点验证两件容易出错的事：
 * 1. **前导空位**：不同月份首日落在不同星期，算错会导致整个月错位一天；
 * 2. **周起始日可配置**：切换「周一开始 / 周日开始」时错位量必须同步变化。
 */
class MonthCalendarModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `首日为周一时没有前导空位`() {
        // 2026-06-01 是星期一
        val calendar = MonthCalendarModel.build(
            YearMonth.of(2026, 6),
            emptyMap(),
            LocalDate.of(2026, 6, 10)
        )
        assertEquals(0, calendar.leadingBlanks)
    }

    @Test
    fun `首日为周日时周一起始需要留六个空位`() {
        // 2026-11-01 是星期日
        val calendar = MonthCalendarModel.build(
            YearMonth.of(2026, 11),
            emptyMap(),
            LocalDate.of(2026, 11, 10)
        )
        assertEquals(6, calendar.leadingBlanks)
    }

    @Test
    fun `首日为周四时周一起始留三个空位`() {
        // 2026-01-01 是星期四
        val calendar = MonthCalendarModel.build(
            YearMonth.of(2026, 1),
            emptyMap(),
            LocalDate.of(2026, 1, 15)
        )
        assertEquals(3, calendar.leadingBlanks)
    }

    @Test
    fun `切换周起始日为周日时错位量同步变化`() {
        val sundayFirst = MonthCalendarModel.build(
            YearMonth.of(2026, 11),
            emptyMap(),
            LocalDate.of(2026, 11, 10),
            firstDayOfWeek = DayOfWeek.SUNDAY
        )
        assertEquals("11/1 就是周日，因此不需要空位", 0, sundayFirst.leadingBlanks)
    }

    @Test
    fun `格子数量等于当月天数`() {
        assertEquals(
            28,
            MonthCalendarModel.build(YearMonth.of(2026, 2), emptyMap(), LocalDate.of(2026, 2, 1)).cells.size
        )
        assertEquals(
            31,
            MonthCalendarModel.build(YearMonth.of(2026, 3), emptyMap(), LocalDate.of(2026, 3, 1)).cells.size
        )
        assertEquals(
            30,
            MonthCalendarModel.build(YearMonth.of(2026, 6), emptyMap(), LocalDate.of(2026, 6, 1)).cells.size
        )
    }

    @Test
    fun `今天被正确标记且只有一天`() {
        val today = LocalDate.of(2026, 3, 17)
        val calendar = MonthCalendarModel.build(YearMonth.of(2026, 3), emptyMap(), today)

        val todayCells = calendar.cells.filter { it.isToday }
        assertEquals(1, todayCells.size)
        assertEquals(today, todayCells[0].date)
    }

    @Test
    fun `跨月的今天不会误标到本月`() {
        val calendar = MonthCalendarModel.build(
            YearMonth.of(2026, 3),
            emptyMap(),
            LocalDate.of(2026, 4, 17)
        )
        assertTrue("4 月的今天不应出现在 3 月网格里", calendar.cells.none { it.isToday })
    }

    @Test
    fun `无记录的月份所有格子都是NO_RECORD`() {
        val calendar = MonthCalendarModel.build(
            YearMonth.of(2026, 5),
            emptyMap(),
            LocalDate.of(2026, 5, 1)
        )
        assertTrue(calendar.cells.all { it.status == DayStatus.NO_RECORD })
        assertTrue(calendar.cells.all { it.recordCount == 0 })
    }

    @Test
    fun `有完整区间的日期显示为COMPLETED`() {
        val records = listOf(
            enter(at(2026, 7, 8, 9, 0)),
            exit(at(2026, 7, 8, 18, 0))
        )
        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 7, 8, 20, 0))

        val calendar = MonthCalendarModel.build(YearMonth.of(2026, 7), stats, LocalDate.of(2026, 7, 8))
        val cell = calendar.cells.first { it.date == LocalDate.of(2026, 7, 8) }

        assertEquals(DayStatus.COMPLETED, cell.status)
        assertEquals(2, cell.recordCount)
        assertEquals(540, cell.dwellMinutes)
    }

    @Test
    fun `仍在岗的日期显示为ENTERED_ONLY`() {
        val records = listOf(enter(at(2026, 7, 9, 9, 0)))
        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 7, 9, 12, 0))

        val calendar = MonthCalendarModel.build(YearMonth.of(2026, 7), stats, LocalDate.of(2026, 7, 9))
        val cell = calendar.cells.first { it.date == LocalDate.of(2026, 7, 9) }

        assertEquals(DayStatus.ENTERED_ONLY, cell.status)
    }

    @Test
    fun `多次进出的日期显示为MULTI_SEGMENT`() {
        val records = listOf(
            enter(at(2026, 7, 10, 8, 0)),
            exit(at(2026, 7, 10, 9, 0)),
            enter(at(2026, 7, 10, 17, 0)),
            exit(at(2026, 7, 10, 18, 0))
        )
        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 7, 10, 20, 0))

        val calendar = MonthCalendarModel.build(YearMonth.of(2026, 7), stats, LocalDate.of(2026, 7, 10))
        val cell = calendar.cells.first { it.date == LocalDate.of(2026, 7, 10) }

        assertEquals(DayStatus.MULTI_SEGMENT, cell.status)
    }

    // ---------------------------------------------------------------- 辅助

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    private fun enter(at: Long) = CheckinRecord(
        pointId = 1L,
        pointName = "公司",
        latitude = 30.0,
        longitude = 120.0,
        time = at,
        source = CheckInSource.AUTO_CONFIRMED,
        isEnter = true
    )

    private fun exit(at: Long) = CheckinRecord(
        pointId = 1L,
        pointName = "公司",
        latitude = 30.0,
        longitude = 120.0,
        time = at,
        source = CheckInSource.FENCE_EXIT,
        isEnter = false
    )
}
