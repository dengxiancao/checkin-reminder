// 职责：打卡记录聚合计算（纯函数，无 Android 依赖，可被 JVM 单测完整覆盖）
package com.example.checkin.stats

import com.example.checkin.data.local.entity.CheckinRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 打卡记录聚合器。
 *
 * 为什么把聚合放在 Kotlin 而不是 SQL：
 * 本场景真正的难点是**区间配对**（进入→离开）与**跨午夜切分**，
 * SQL 表达力不足且难以测试；纯函数可以用构造数据把边界情形一条条钉死。
 *
 * 计算规则（对齐同类成熟产品）：
 * 1. 一次「在岗区间」= 一条进入事件（`isEnter == true`）到其后最近一条离开事件（`isEnter == false`）的配对；
 * 2. 连续多条进入事件只认第一条（去重），孤立离开事件（无对应进入）不参与配对；
 * 3. **跨午夜**按自然日切分，各自计入所属日期；
 * 4. 同一自然日内的多段区间**取并集**，重叠部分不重复计算；
 * 5. **未闭合**的区间（仍在岗）实时计入 `now`，`closed = false`；
 * 6. 纯手动打卡（`isEnter == null`）计入当日打卡次数，但**不参与**区间配对。
 */
object StatsCalculator {

    /**
     * 把记录聚合为「日期 -> 该日各打卡点的统计」。
     *
     * @param records 全部打卡记录（顺序无关，内部会按时间排序）
     * @param zoneId  时区，默认系统时区；跨时区/夏令时场景由它决定「自然日」边界
     * @param now     计算「仍在岗」时长所用的当前时刻，默认取系统时间（测试可注入）
     */
    fun calculate(
        records: List<CheckinRecord>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis()
    ): Map<LocalDate, List<DailyPointStat>> {
        if (records.isEmpty()) return emptyMap()

        val perPoint = buildIntervals(records)

        // (日期, 打卡点id) -> 当日累计器
        val accumulators = LinkedHashMap<Long, MutableMap<LocalDate, DayAccumulator>>()

        perPoint.forEach { point ->
            val accByDay = accumulators.getOrPut(point.pointId) { LinkedHashMap() }

            // ---- 区间：切分到自然日并累计 ----
            point.intervals.forEach interval@{ interval ->
                val endMillis = interval.end ?: now
                // 退化区间（结束不晚于开始，例如时钟回拨造成的脏数据）直接跳过，
                // 否则会在 while 里累计出负数或零长段落
                if (endMillis <= interval.start) return@interval

                val startDay = dayOf(interval.start, zoneId)
                val endDay = dayOf(endMillis, zoneId)

                var day = startDay
                while (!day.isAfter(endDay)) {
                    val dayStart = startOfDay(day, zoneId)
                    val dayEnd = startOfDay(day.plusDays(1), zoneId)

                    val segStart = maxOf(interval.start, dayStart)
                    val segEnd = minOf(endMillis, dayEnd)

                    val addsSegment = segEnd > segStart
                    // 事件计数按**原始发生时间**归属，不按裁剪后的时间。
                    // 离开事件用 `end - 1ms` 归属：恰好落在 00:00:00.000 的离开
                    // 应算作「前一天」的结束，而不是「新一天」的开始。
                    val countsEnter = startDay == day
                    val exitDay = interval.end?.let { dayOf(it - 1L, zoneId) }
                    val countsExit = exitDay == day

                    if (addsSegment || countsEnter || countsExit) {
                        val acc = accByDay.getOrPut(day) {
                            DayAccumulator(point.pointId, point.pointName)
                        }
                        if (addsSegment) {
                            acc.segments += segStart to segEnd
                            if (interval.end == null) acc.openOverlap = true
                        }
                        if (countsEnter) acc.enterCount++
                        if (countsExit) acc.exitCount++
                    }

                    day = day.plusDays(1)
                }
            }

            // ---- 纯手动打卡：只计数 ----
            point.manualTimes.forEach { t ->
                val day = dayOf(t, zoneId)
                val acc = accByDay.getOrPut(day) { DayAccumulator(point.pointId, point.pointName) }
                acc.manualCount++
            }
        }

        return accumulators
            .flatMap { (pointId, byDay) ->
                byDay.map { (day, acc) -> day to acc.toStat(day, pointId) }
            }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
    }

    /** 汇总一段日期区间的统计（含连续天数与逐日在岗分钟） */
    fun summarize(
        stats: Map<LocalDate, List<DailyPointStat>>,
        start: LocalDate,
        end: LocalDate
    ): RangeSummary {
        if (start.isAfter(end)) return RangeSummary.empty(start, end)

        var totalDwell = 0
        var activeDays = 0
        var enter = 0
        var exit = 0
        var manual = 0
        val perDay = ArrayList<Pair<LocalDate, Int>>()

        var day = start
        while (!day.isAfter(end)) {
            val forDay = stats[day].orEmpty()
            val minutes = forDay.sumOf { it.dwellMinutes }
            perDay += day to minutes
            totalDwell += minutes
            if (forDay.isNotEmpty()) activeDays++
            enter += forDay.sumOf { it.enterCount }
            exit += forDay.sumOf { it.exitCount }
            manual += forDay.sumOf { it.manualCount }
            day = day.plusDays(1)
        }

        val activeDates = stats.keys.filter { !it.isBefore(start) && !it.isAfter(end) }
            .filter { !stats[it].isNullOrEmpty() }
            .toSortedSet()

        return RangeSummary(
            start = start,
            end = end,
            activeDays = activeDays,
            totalDwellMinutes = totalDwell,
            averageDwellMinutes = if (activeDays > 0) totalDwell / activeDays else 0,
            maxStreak = maxStreak(activeDates),
            currentStreak = currentStreak(activeDates, end),
            enterCount = enter,
            exitCount = exit,
            manualCount = manual,
            perDayMinutes = perDay
        )
    }

    /**
     * 把一天内多个打卡点的统计归并为一个日历状态。
     *
     * 优先级：仍在岗 > 多段 > 单段完成 > 无记录。
     */
    fun statusOf(stats: List<DailyPointStat>): DayStatus = when {
        stats.isEmpty() -> DayStatus.NO_RECORD
        stats.any { !it.closed } -> DayStatus.ENTERED_ONLY
        stats.sumOf { it.segments } >= 2 -> DayStatus.MULTI_SEGMENT
        else -> DayStatus.COMPLETED
    }

    // ---------------------------------------------------------------- 内部实现

    /** 某打卡点配对后的区间与手动打卡时间 */
    private data class PointSeries(
        val pointId: Long,
        val pointName: String,
        val intervals: List<Interval>,
        val manualTimes: List<Long>
    )

    private data class Interval(val start: Long, val end: Long?)

    private class DayAccumulator(val pointId: Long, val pointName: String) {
        val segments = ArrayList<Pair<Long, Long>>()
        var enterCount = 0
        var exitCount = 0
        var manualCount = 0
        var openOverlap = false

        fun toStat(day: LocalDate, pointId: Long): DailyPointStat {
            val union = unionMinutes(segments)
            val first = segments.minOfOrNull { it.first }
            val last = segments.maxOfOrNull { it.second }
            return DailyPointStat(
                day = day,
                pointId = pointId,
                pointName = pointName,
                firstEnterAt = first,
                lastExitAt = if (openOverlap) null else last,
                dwellMinutes = union,
                segments = segments.size,
                enterCount = enterCount,
                exitCount = exitCount,
                manualCount = manualCount,
                closed = !openOverlap
            )
        }
    }

    /** 逐点配对出在岗区间，并分离手动打卡时间 */
    private fun buildIntervals(records: List<CheckinRecord>): List<PointSeries> =
        records.groupBy { it.pointId }.map { (pointId, list) ->
            val sorted = list.sortedBy { it.time }
            val intervals = ArrayList<Interval>()
            val manualTimes = ArrayList<Long>()
            var openStart: Long? = null

            sorted.forEach { record ->
                when (record.isEnter) {
                    true -> if (openStart == null) openStart = record.time

                    false -> {
                        val start = openStart
                        if (start != null) {
                            intervals += Interval(start, record.time)
                            openStart = null
                        }
                        // 孤立离开事件（无对应进入）直接忽略，避免造出负数区间
                    }

                    null -> manualTimes += record.time
                }
            }
            openStart?.let { intervals += Interval(it, null) }

            PointSeries(
                pointId = pointId,
                pointName = sorted.lastOrNull()?.pointName.orEmpty(),
                intervals = intervals,
                manualTimes = manualTimes
            )
        }

    /**
     * 多段区间取并集后的总分钟数。
     *
     * 先按开始时间排序，再线性合并重叠段——与「日历上涂色不重复计数」的直觉一致。
     */
    private fun unionMinutes(segments: List<Pair<Long, Long>>): Int {
        if (segments.isEmpty()) return 0
        val sorted = segments.sortedBy { it.first }
        var totalMillis = 0L
        var curStart = sorted.first().first
        var curEnd = sorted.first().second

        sorted.drop(1).forEach { (s, e) ->
            if (s <= curEnd) {
                if (e > curEnd) curEnd = e
            } else {
                totalMillis += curEnd - curStart
                curStart = s
                curEnd = e
            }
        }
        totalMillis += curEnd - curStart

        // 向上取整到分钟：1 秒的在岗也记为 1 分钟，避免「明明打过卡却显示 0 分钟」
        return ((totalMillis + 59_999L) / 60_000L).toInt()
    }

    /** 最长连续打卡天数 */
    private fun maxStreak(dates: Iterable<LocalDate>): Int {
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        dates.forEach { d ->
            run = if (prev != null && prev!!.plusDays(1) == d) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return best
    }

    /** 自 end 向前回推的连续打卡天数（今天没打卡则从昨天起算，符合用户直觉） */
    private fun currentStreak(dates: Set<LocalDate>, end: LocalDate): Int {
        if (dates.isEmpty()) return 0
        var cursor = if (dates.contains(end)) end else end.minusDays(1)
        var streak = 0
        while (dates.contains(cursor)) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    private fun dayOf(millis: Long, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()

    private fun startOfDay(day: LocalDate, zoneId: ZoneId): Long =
        day.atStartOfDay(zoneId).toInstant().toEpochMilli()
}
