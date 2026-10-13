// 职责：打卡聚合计算单元测试，锁定区间配对、跨日切分与并集规则
package com.example.checkin

import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinRecord
import com.example.checkin.stats.DayStatus
import com.example.checkin.stats.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [StatsCalculator] 的边界用例。
 *
 * 统计是打卡类产品的**核心价值交付点**：用户判断「这个软件有没有用」，
 * 靠的就是「我这个月去了几次、待了多久」。因此这里的规则必须被钉死：
 * 跨午夜、多段重叠、未闭合、孤立事件，任何一种算错都会让用户对数据失去信任。
 *
 * 固定使用 Asia/Shanghai 时区，让断言与运行机器的时区无关。
 */
class StatsCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // ---------------------------------------------------------------- 正常配对

    @Test
    fun `单段完整区间按分钟累计`() {
        val records = listOf(
            enter(at(2026, 3, 2, 9, 0)),
            exit(at(2026, 3, 2, 18, 0))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 2, 20, 0))
        val day = stats[LocalDate.of(2026, 3, 2)]!!

        assertEquals(1, day.size)
        assertEquals("09:00→18:00 应为 540 分钟", 540, day[0].dwellMinutes)
        assertEquals(1, day[0].segments)
        assertEquals(1, day[0].enterCount)
        assertEquals(1, day[0].exitCount)
        assertTrue(day[0].closed)
    }

    // ---------------------------------------------------------------- 跨午夜切分

    @Test
    fun `跨午夜区间按自然日切分`() {
        // 3/1 22:00 进入，3/2 06:00 离开
        val records = listOf(
            enter(at(2026, 3, 1, 22, 0)),
            exit(at(2026, 3, 2, 6, 0))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 2, 8, 0))

        assertEquals("3/1 应只算 22:00→24:00", 120, stats[LocalDate.of(2026, 3, 1)]!![0].dwellMinutes)
        assertEquals("3/2 应只算 00:00→06:00", 360, stats[LocalDate.of(2026, 3, 2)]!![0].dwellMinutes)
    }

    @Test
    fun `跨日区间的事件归属落在各自发生的那一天`() {
        val records = listOf(
            enter(at(2026, 3, 1, 22, 0)),
            exit(at(2026, 3, 2, 6, 0))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 2, 8, 0))

        assertEquals("进入事件算 3/1", 1, stats[LocalDate.of(2026, 3, 1)]!![0].enterCount)
        assertEquals("离开事件算 3/2", 1, stats[LocalDate.of(2026, 3, 2)]!![0].exitCount)
    }

    // ---------------------------------------------------------------- 多段与并集

    @Test
    fun `同日多次进出按顺序配对应产生多段`() {
        // 09:00-12:00（180 分钟）与 13:00-15:00（120 分钟），共 300 分钟
        val records = listOf(
            enter(at(2026, 3, 3, 9, 0)),
            exit(at(2026, 3, 3, 12, 0)),
            enter(at(2026, 3, 3, 13, 0)),
            exit(at(2026, 3, 3, 15, 0))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 3, 20, 0))
        val day = stats[LocalDate.of(2026, 3, 3)]!![0]

        assertEquals(300, day.dwellMinutes)
        assertEquals("两次进入应识别为两段（多次进出）", 2, day.segments)
        assertEquals(2, day.enterCount)
        assertEquals(2, day.exitCount)
        assertEquals(DayStatus.MULTI_SEGMENT, StatsCalculator.statusOf(listOf(day)))
    }

    @Test
    fun `乱序传入的记录会先按时间排序再配对`() {
        // 故意打乱顺序：合并逻辑依赖「排序后线性扫描」，排序缺失会让区间算错
        val records = listOf(
            exit(at(2026, 3, 12, 18, 0)),
            enter(at(2026, 3, 12, 9, 0))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 12, 20, 0))
        assertEquals(540, stats[LocalDate.of(2026, 3, 12)]!![0].dwellMinutes)
    }

    @Test
    fun `同一打卡点的区间在时间轴上天然不重叠`() {
        // 配对规则是「一次进入由其后最近的离开闭合」，因此第 N 段结束必然
        // 早于第 N+1 段开始 —— 并集合并逻辑（unionMinutes）只作为防御性代码存在，
        // 这里验证即使输入中揉了更早的闲散记录，也不会算出超过真实跨度的时长。
        val records = listOf(
            enter(at(2026, 3, 13, 9, 0)),
            exit(at(2026, 3, 13, 10, 0)),
            enter(at(2026, 3, 13, 11, 0)),
            exit(at(2026, 3, 13, 12, 0))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 13, 20, 0))
        val day = stats[LocalDate.of(2026, 3, 13)]!![0]

        assertEquals("两段各 60 分钟，总跨度 3 小时但有效在岗仅 2 小时", 120, day.dwellMinutes)
    }

    @Test
    fun `不重叠的多段区间时长直接相加`() {
        val records = listOf(
            enter(at(2026, 3, 4, 8, 0)),
            exit(at(2026, 3, 4, 9, 0)),
            enter(at(2026, 3, 4, 17, 0)),
            exit(at(2026, 3, 4, 18, 0))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 4, 20, 0))
        assertEquals(120, stats[LocalDate.of(2026, 3, 4)]!![0].dwellMinutes)
    }

    // ---------------------------------------------------------------- 未闭合

    @Test
    fun `未闭合区间按当前时间实时计入`() {
        val records = listOf(enter(at(2026, 3, 5, 9, 0)))

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 5, 12, 0))
        val day = stats[LocalDate.of(2026, 3, 5)]!![0]

        assertEquals("09:00→12:00 应为 180 分钟", 180, day.dwellMinutes)
        assertFalse("仍在岗必须标记为未闭合", day.closed)
        assertNull("未闭合时不应有离开时间", day.lastExitAt)
        assertEquals(DayStatus.ENTERED_ONLY, StatsCalculator.statusOf(listOf(day)))
    }

    @Test
    fun `未闭合区间跨日后在新的一天继续累计`() {
        val records = listOf(enter(at(2026, 3, 5, 22, 0)))

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 6, 2, 0))

        assertEquals(120, stats[LocalDate.of(2026, 3, 5)]!![0].dwellMinutes)
        assertEquals(120, stats[LocalDate.of(2026, 3, 6)]!![0].dwellMinutes)
        assertFalse(stats[LocalDate.of(2026, 3, 6)]!![0].closed)
    }

    // ---------------------------------------------------------------- 异常数据

    @Test
    fun `孤立的离开事件不产生任何区间`() {
        val records = listOf(exit(at(2026, 3, 7, 18, 0)))

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 7, 20, 0))
        assertTrue("没有进入就没有在岗时长", stats.isEmpty())
    }

    @Test
    fun `连续两次进入只认第一次`() {
        val records = listOf(
            enter(at(2026, 3, 8, 9, 0)),
            enter(at(2026, 3, 8, 10, 0)),
            exit(at(2026, 3, 8, 18, 0))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 8, 20, 0))
        val day = stats[LocalDate.of(2026, 3, 8)]!![0]

        assertEquals("应从第一次进入起算", 540, day.dwellMinutes)
        assertEquals(1, day.segments)
    }

    @Test
    fun `不足一分钟的区间向上取整为一分钟`() {
        val records = listOf(
            enter(at(2026, 3, 9, 9, 0)),
            exit(at(2026, 3, 9, 9, 0).let { it + 1_000L }) // 仅停留 1 秒
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 9, 20, 0))
        assertEquals("宁可显示 1 分钟，也不能显示 0 分钟", 1, stats[LocalDate.of(2026, 3, 9)]!![0].dwellMinutes)
    }

    // ---------------------------------------------------------------- 手动打卡

    @Test
    fun `手动打卡计入次数但不参与区间配对`() {
        val records = listOf(
            manual(at(2026, 3, 10, 9, 30)),
            manual(at(2026, 3, 10, 18, 30))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 10, 20, 0))
        val day = stats[LocalDate.of(2026, 3, 10)]!![0]

        assertEquals(2, day.manualCount)
        assertEquals(0, day.dwellMinutes)
        assertEquals(0, day.enterCount)
        assertEquals(DayStatus.COMPLETED, StatsCalculator.statusOf(listOf(day)))
    }

    // ---------------------------------------------------------------- 多打卡点

    @Test
    fun `多个打卡点分别统计`() {
        val records = listOf(
            enter(at(2026, 3, 11, 9, 0), pointId = 1L, pointName = "公司"),
            exit(at(2026, 3, 11, 12, 0), pointId = 1L, pointName = "公司"),
            enter(at(2026, 3, 11, 14, 0), pointId = 2L, pointName = "家"),
            exit(at(2026, 3, 11, 18, 0), pointId = 2L, pointName = "家")
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 11, 20, 0))
        val day = stats[LocalDate.of(2026, 3, 11)]!!

        assertEquals(2, day.size)
        assertEquals(180, day.first { it.pointId == 1L }.dwellMinutes)
        assertEquals(240, day.first { it.pointId == 2L }.dwellMinutes)
        assertEquals("同一天两个点各一段，不应被判为多次进出", 2, day.sumOf { it.segments })
    }

    // ---------------------------------------------------------------- 汇总

    @Test
    fun `汇总统计天数与累计时长`() {
        val records = listOf(
            enter(at(2026, 3, 2, 9, 0)), exit(at(2026, 3, 2, 17, 0)),   // 480
            enter(at(2026, 3, 3, 9, 0)), exit(at(2026, 3, 3, 18, 0))    // 540
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 3, 3, 20, 0))
        val summary = StatsCalculator.summarize(
            stats,
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 3)
        )

        assertEquals(2, summary.activeDays)
        assertEquals(1020, summary.totalDwellMinutes)
        assertEquals(510, summary.averageDwellMinutes)
        assertEquals(2, summary.maxStreak)
        assertEquals(2, summary.currentStreak)
        assertEquals(540, summary.peakMinutes)
        assertEquals(3, summary.perDayMinutes.size)
    }

    @Test
    fun `连续天数在中间断档时正确分段`() {
        val records = listOf(
            enter(at(2026, 4, 1, 9, 0)), exit(at(2026, 4, 1, 10, 0)),
            enter(at(2026, 4, 2, 9, 0)), exit(at(2026, 4, 2, 10, 0)),
            // 4/3 缺失
            enter(at(2026, 4, 4, 9, 0)), exit(at(2026, 4, 4, 10, 0))
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 4, 5, 0, 0))
        val summary = StatsCalculator.summarize(
            stats,
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 5)
        )

        assertEquals(2, summary.maxStreak)
        assertEquals("4/5 无记录，应从 4/4 起算 1 天", 1, summary.currentStreak)
    }

    @Test
    fun `没有任何记录时汇总全为零`() {
        val summary = StatsCalculator.summarize(
            emptyMap(),
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31)
        )

        assertEquals(0, summary.activeDays)
        assertEquals(0, summary.totalDwellMinutes)
        assertEquals(0, summary.maxStreak)
        assertEquals(31, summary.perDayMinutes.size)
    }

    // ---------------------------------------------------------------- 日历状态归并

    @Test
    fun `日历状态在无记录时为NO_RECORD`() {
        assertEquals(DayStatus.NO_RECORD, StatsCalculator.statusOf(emptyList()))
    }

    @Test
    fun `仍有一点在岗时整日状态为ENTERED_ONLY`() {
        val records = listOf(
            enter(at(2026, 6, 1, 9, 0), pointId = 1L),
            exit(at(2026, 6, 1, 10, 0), pointId = 1L),
            enter(at(2026, 6, 1, 14, 0), pointId = 2L)
        )

        val stats = StatsCalculator.calculate(records, zone, now = at(2026, 6, 1, 16, 0))
        val day = stats[LocalDate.of(2026, 6, 1)]!!

        assertEquals("只要有一点未闭合就优先提示仍在范围", DayStatus.ENTERED_ONLY, StatsCalculator.statusOf(day))
    }

    // ---------------------------------------------------------------- 辅助

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    private fun enter(at: Long, pointId: Long = 1L, pointName: String = "公司") = CheckinRecord(
        pointId = pointId,
        pointName = pointName,
        latitude = 30.0,
        longitude = 120.0,
        time = at,
        source = CheckInSource.AUTO_CONFIRMED,
        isEnter = true
    )

    private fun exit(at: Long, pointId: Long = 1L, pointName: String = "公司") = CheckinRecord(
        pointId = pointId,
        pointName = pointName,
        latitude = 30.0,
        longitude = 120.0,
        time = at,
        source = CheckInSource.FENCE_EXIT,
        isEnter = false
    )

    private fun manual(at: Long, pointId: Long = 1L, pointName: String = "公司") = CheckinRecord(
        pointId = pointId,
        pointName = pointName,
        latitude = 30.0,
        longitude = 120.0,
        time = at,
        source = CheckInSource.MANUAL,
        isEnter = null
    )
}
