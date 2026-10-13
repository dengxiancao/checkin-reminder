// 职责：记录页「统计」视图 —— 日/周/月指标卡与自绘在岗时长柱状图
package com.example.checkin.ui.records

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.checkin.R
import com.example.checkin.stats.DailyPointStat
import com.example.checkin.stats.RangeSummary
import com.example.checkin.stats.StatsCalculator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 统计视图。
 *
 * 图表用 Compose `Canvas` **自绘**而不是引入图表库：
 * 需求只有「一根柱 = 一天的在岗时长」，第三方库带来的体积与供应链风险
 * 远大于它节省的代码量。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsSection(
    dailyStats: Map<LocalDate, List<DailyPointStat>>,
    range: StatsRange,
    onSelectRange: (StatsRange) -> Unit
) {
    val today = remember { LocalDate.now() }
    val (start, end) = remember(range, today) { rangeBounds(range, today) }

    val summary = remember(dailyStats, start, end) {
        StatsCalculator.summarize(dailyStats, start, end)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                StatsRange.DAY to stringResource(R.string.records_stats_day),
                StatsRange.WEEK to stringResource(R.string.records_stats_week),
                StatsRange.MONTH to stringResource(R.string.records_stats_month)
            )
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = range == value,
                    onClick = { onSelectRange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size)
                ) {
                    Text(label)
                }
            }
        }

        Text(
            text = stringResource(
                when (range) {
                    StatsRange.DAY -> R.string.records_stats_scope_day
                    StatsRange.WEEK -> R.string.records_stats_scope_week
                    StatsRange.MONTH -> R.string.records_stats_scope_month
                }
            ) + " · " + formatDate(start) + " ~ " + formatDate(end),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        MetricGrid(summary)

        EventSummaryCard(summary)

        if (range != StatsRange.DAY) {
            ChartCard(summary)
        }
    }
}

/** 四个核心指标，用 2×2 网格排布，避免长文案被挤压 */
@Composable
private fun MetricGrid(summary: RangeSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(
                title = stringResource(R.string.records_stats_active_days),
                value = stringResource(R.string.records_stats_active_days_value, summary.activeDays),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = stringResource(R.string.records_stats_total_dwell),
                value = formatDuration(summary.totalDwellMinutes),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(
                title = stringResource(R.string.records_stats_avg_dwell),
                value = formatDuration(summary.averageDwellMinutes),
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                title = stringResource(R.string.records_stats_streak),
                value = stringResource(R.string.records_stats_streak_value, summary.maxStreak),
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(
                title = stringResource(R.string.records_stats_current_streak),
                value = stringResource(R.string.records_stats_streak_value, summary.currentStreak),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 事件次数细分（进入 / 离开 / 手动），用于核对记录完整性 */
@Composable
private fun EventSummaryCard(summary: RangeSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = stringResource(
                R.string.records_stats_events,
                summary.enterCount,
                summary.exitCount,
                summary.manualCount
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * 每日在岗时长柱状图。
 *
 * 归一化到区间内的峰值：这样即使只有一次打卡，柱子也能占满高度、看得见。
 */
@Composable
private fun ChartCard(summary: RangeSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.records_stats_chart_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))

            if (summary.activeDays == 0) {
                Text(
                    text = stringResource(R.string.records_stats_chart_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                )
            } else {
                val barColor = MaterialTheme.colorScheme.primary
                val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                ) {
                    val data = summary.perDayMinutes
                    if (data.isEmpty()) return@Canvas

                    val peak = summary.peakMinutes.coerceAtLeast(1)
                    val slot = size.width / data.size
                    val barWidth = (slot * 0.6f).coerceAtLeast(2f)
                    val radius = CornerRadius(barWidth / 3f, barWidth / 3f)

                    data.forEachIndexed { index, (_, minutes) ->
                        val left = index * slot + (slot - barWidth) / 2f

                        // 轨道：即使当天为 0 也画一条很浅的底，形成「时间轴」的连续感
                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset(left, 0f),
                            size = Size(barWidth, size.height),
                            cornerRadius = radius
                        )

                        if (minutes > 0) {
                            val ratio = minutes.toFloat() / peak
                            val barHeight = size.height * ratio.coerceIn(0.06f, 1f)
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(left, size.height - barHeight),
                                size = Size(barWidth, barHeight),
                                cornerRadius = radius
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatShortDate(summary.start),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(
                            R.string.records_stats_chart_peak,
                            formatDuration(summary.peakMinutes)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatShortDate(summary.end),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 把统计粒度换算成日期区间（周以周一为起点，月以 1 号为起点） */
private fun rangeBounds(range: StatsRange, today: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
    StatsRange.DAY -> today to today
    StatsRange.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
    StatsRange.MONTH -> today.withDayOfMonth(1) to today
}

private fun formatShortDate(date: LocalDate): String =
    "%d/%d".format(date.monthValue, date.dayOfMonth)
