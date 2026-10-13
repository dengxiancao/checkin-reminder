// 职责：记录页「日历」视图 —— 月历色块 + 当日明细
package com.example.checkin.ui.records

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.checkin.R
import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinRecord
import com.example.checkin.stats.DailyPointStat
import com.example.checkin.stats.DayCell
import com.example.checkin.stats.DayStatus
import com.example.checkin.stats.MonthCalendarModel
import java.time.LocalDate
import java.time.YearMonth

/**
 * 日历视图。
 *
 * 设计取舍：
 * - **不用 LazyVerticalGrid**：月历最多 42 格、结构固定，用 `Column + Row` 手动分块
 *   比引入懒加载网格更简单，也避免嵌套滚动冲突（外层已有 LazyColumn 展示当日明细）；
 * - **状态不只靠颜色**：格子底部用「小圆点数量」表示当日区间段数，
 *   并给每个格子加 `contentDescription`，满足色盲与读屏场景。
 */
@Composable
internal fun CalendarSection(
    records: List<CheckinRecord>,
    dailyStats: Map<LocalDate, List<DailyPointStat>>,
    onEdit: (CheckinRecord) -> Unit
) {
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDay by remember { mutableStateOf(today) }

    val calendar = remember(month, dailyStats, today) {
        MonthCalendarModel.build(month, dailyStats, today)
    }

    val dayRecords = remember(records, selectedDay) {
        records
            .filter { instantToDate(it.time) == selectedDay }
            .sortedBy { it.time }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // ---------------- 月份切换 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = stringResource(R.string.records_calendar_prev)
                )
            }
            Text(
                text = stringResource(R.string.records_month_label, month.year, month.monthValue),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.records_calendar_next)
                )
            }
        }

        if (month != YearMonth.from(today)) {
            TextButton(
                onClick = {
                    month = YearMonth.from(today)
                    selectedDay = today
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.records_calendar_today))
            }
        }

        WeekdayHeader()

        CalendarGrid(
            calendar = calendar,
            selectedDay = selectedDay,
            onSelectDay = { selectedDay = it }
        )

        Legend()

        Spacer(Modifier.height(8.dp))

        // ---------------- 当日明细 ----------------
        Text(
            text = stringResource(R.string.records_calendar_detail_title) + " · " + formatDate(selectedDay),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(4.dp))

        if (dayRecords.isEmpty()) {
            Text(
                text = stringResource(R.string.records_calendar_detail_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items = dayRecords, key = { it.id }) { record ->
                    CompactRecordRow(record = record, onClick = { onEdit(record) })
                }
            }
        }
    }
}

/** 星期表头（周一起始，与 MonthCalendarModel 的默认值一致） */
@Composable
private fun WeekdayHeader() {
    val labels = listOf(
        R.string.records_weekday_mon,
        R.string.records_weekday_tue,
        R.string.records_weekday_wed,
        R.string.records_weekday_thu,
        R.string.records_weekday_fri,
        R.string.records_weekday_sat,
        R.string.records_weekday_sun
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        labels.forEach { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 月历网格：前导空位 + 日期格子，按 7 列分行 */
@Composable
private fun CalendarGrid(
    calendar: com.example.checkin.stats.CalendarMonth,
    selectedDay: LocalDate,
    onSelectDay: (LocalDate) -> Unit
) {
    val cells: List<DayCell?> = remember(calendar) {
        List(calendar.leadingBlanks) { null } + calendar.cells
    }
    val rows = cells.chunked(7)

    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    Box(modifier = Modifier.weight(1f)) {
                        if (cell != null) {
                            DayBox(
                                cell = cell,
                                selected = cell.date == selectedDay,
                                onClick = { onSelectDay(cell.date) }
                            )
                        } else {
                            Spacer(Modifier.aspectRatio(1f))
                        }
                    }
                }
                // 补满一周，避免最后一行格子被拉伸
                repeat(7 - week.size) {
                    Box(modifier = Modifier.weight(1f)) { Spacer(Modifier.aspectRatio(1f)) }
                }
            }
        }
    }
}

/** 单个日期格子 */
@Composable
private fun DayBox(
    cell: DayCell,
    selected: Boolean,
    onClick: () -> Unit
) {
    val container = when (cell.status) {
        DayStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        DayStatus.MULTI_SEGMENT -> MaterialTheme.colorScheme.primaryContainer
        DayStatus.ENTERED_ONLY -> MaterialTheme.colorScheme.tertiaryContainer
        DayStatus.NO_RECORD -> Color.Transparent
    }
    val contentColor = when (cell.status) {
        DayStatus.COMPLETED -> MaterialTheme.colorScheme.onPrimary
        DayStatus.MULTI_SEGMENT -> MaterialTheme.colorScheme.onPrimaryContainer
        DayStatus.ENTERED_ONLY -> MaterialTheme.colorScheme.onTertiaryContainer
        DayStatus.NO_RECORD -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // 读屏与色盲用户依赖这段文字，而不是色块本身
    val statusText = stringResource(
        when (cell.status) {
            DayStatus.COMPLETED -> R.string.records_legend_completed
            DayStatus.MULTI_SEGMENT -> R.string.records_legend_multi_segment
            DayStatus.ENTERED_ONLY -> R.string.records_legend_entered_only
            DayStatus.NO_RECORD -> R.string.records_legend_no_record
        }
    )

    val border = when {
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        cell.isToday -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        else -> null
    }

    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .background(container, RoundedCornerShape(10.dp))
            .then(
                if (border != null) {
                    Modifier.border(border, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${cell.date.dayOfMonth} 日，$statusText，" +
                    "${cell.recordCount} 条记录"
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = cell.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (cell.isToday) FontWeight.Bold else FontWeight.Normal,
                color = contentColor
            )
            // 段数用圆点表达：单段 1 点、多次进出 3 点；无记录不画点。
            // 这样即使完全忽略颜色，也能分辨「有打卡 / 多次进出 / 没打卡」。
            if (cell.status != DayStatus.NO_RECORD) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    repeat(if (cell.status == DayStatus.MULTI_SEGMENT) 3 else 1) {
                        Dot(contentColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .size(3.dp)
            .background(color, CircleShape)
    )
}

/** 图例：颜色 + 文字双编码，避免只靠颜色传达状态 */
@Composable
private fun Legend() {
    val items = listOf(
        MaterialTheme.colorScheme.primary to R.string.records_legend_completed,
        MaterialTheme.colorScheme.primaryContainer to R.string.records_legend_multi_segment,
        MaterialTheme.colorScheme.tertiaryContainer to R.string.records_legend_entered_only
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { (color, labelRes) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color, RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 精简记录行：用于日历下方的当日明细，点击进入编辑 */
@Composable
private fun CompactRecordRow(record: CheckinRecord, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (record.isEnter) {
                    true -> Icons.AutoMirrored.Filled.Login
                    false -> Icons.AutoMirrored.Filled.Logout
                    null -> Icons.Filled.Place
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTimeOnly(record.time),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = record.pointName,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = sourceLabel(record.source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun instantToDate(millis: Long): LocalDate =
    java.time.Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()
