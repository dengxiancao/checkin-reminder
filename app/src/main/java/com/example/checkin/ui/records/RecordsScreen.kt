// 职责：打卡记录页 —— 时间轴 / 日历 / 统计三视图，支持删除、撤销与编辑
package com.example.checkin.ui.records

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.checkin.R
import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinRecord
import com.example.checkin.stats.DailyPointStat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 时间格式化器（线程安全、静态复用） */
internal val RECORD_DATETIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

internal val RECORD_TIME_ONLY_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA)

internal val RECORD_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE", Locale.CHINA)

internal fun zone(): ZoneId = ZoneId.systemDefault()

internal fun LocalDate.toMillisAtStartOfDay(zoneId: ZoneId = zone()): Long =
    atStartOfDay(zoneId).toInstant().toEpochMilli()

internal fun formatDateTime(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(zone()).format(RECORD_DATETIME_FMT)

internal fun formatTimeOnly(timestamp: Long): String =
    Instant.ofEpochMilli(timestamp).atZone(zone()).format(RECORD_TIME_ONLY_FMT)

internal fun formatDate(date: LocalDate): String = date.format(RECORD_DATE_FMT)

/** 在岗时长文案：小于 60 分钟只显示分钟，否则显示「X 小时 Y 分」 */
@Composable
internal fun formatDuration(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.records_duration_zero)
    minutes < 60 -> stringResource(R.string.records_duration_minutes, minutes)
    else -> stringResource(R.string.records_duration_hm, minutes / 60, minutes % 60)
}

/** 打卡来源文案（data 层不承载中文，统一在 UI 映射资源） */
@Composable
internal fun sourceLabel(source: CheckInSource): String = stringResource(
    when (source) {
        CheckInSource.MANUAL -> R.string.records_source_manual
        CheckInSource.AUTO_CONFIRMED -> R.string.records_source_auto_confirmed
        CheckInSource.FENCE_EXIT -> R.string.records_source_fence_exit
    }
)

/**
 * 打卡记录页。
 *
 * 三个视图共用一个顶部栏：
 * - **时间轴**：按日期分组的流水，支持侧滑删除、点击编辑；
 * - **日历**：月历色块，一眼看出哪天打过卡；
 * - **统计**：日/周/月的打卡天数、在岗时长、连续天数与自绘柱状图。
 *
 * 状态全部由 [RecordsViewModel] 持有，本函数只做渲染与事件上报。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordsScreen(
    records: List<CheckinRecord>,
    dailyStats: Map<LocalDate, List<DailyPointStat>>,
    view: RecordsView,
    statsRange: StatsRange,
    onSelectView: (RecordsView) -> Unit,
    onSelectStatsRange: (StatsRange) -> Unit,
    onDelete: (CheckinRecord) -> Unit,
    onEdit: (CheckinRecord) -> Unit,
    onClearRequest: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.records_title)) },
                actions = {
                    IconButton(onClick = onClearRequest, enabled = records.isNotEmpty()) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            contentDescription = stringResource(R.string.records_clear)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ViewSwitcher(
                selected = view,
                onSelect = onSelectView,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            when (view) {
                RecordsView.TIMELINE -> TimelineSection(
                    records = records,
                    dailyStats = dailyStats,
                    onDelete = onDelete,
                    onEdit = onEdit
                )

                RecordsView.CALENDAR -> CalendarSection(
                    records = records,
                    dailyStats = dailyStats,
                    onEdit = onEdit
                )

                RecordsView.STATS -> StatsSection(
                    dailyStats = dailyStats,
                    range = statsRange,
                    onSelectRange = onSelectStatsRange
                )
            }
        }
    }
}

/** 顶部视图切换：时间轴 / 日历 / 统计 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSwitcher(
    selected: RecordsView,
    onSelect: (RecordsView) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        RecordsView.TIMELINE to stringResource(R.string.records_view_timeline),
        RecordsView.CALENDAR to stringResource(R.string.records_view_calendar),
        RecordsView.STATS to stringResource(R.string.records_view_stats)
    )

    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size)
            ) {
                Text(label)
            }
        }
    }
}

// ---------------------------------------------------------------- 时间轴

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TimelineSection(
    records: List<CheckinRecord>,
    dailyStats: Map<LocalDate, List<DailyPointStat>>,
    onDelete: (CheckinRecord) -> Unit,
    onEdit: (CheckinRecord) -> Unit
) {
    if (records.isEmpty()) {
        EmptyRecordsHint()
    } else {
        val zoneId = remember { zone() }
        // 按自然日分组并降序：记录页的时间轴语义就是「最近的在最上面」
        val groups: List<Pair<LocalDate, List<CheckinRecord>>> = remember(records) {
            records
                .groupBy { Instant.ofEpochMilli(it.time).atZone(zoneId).toLocalDate() }
                .entries
                .sortedByDescending { it.key }
                .map { it.key to it.value }
        }

        val listState = rememberLazyListState()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            groups.forEach { (date, dayRecords) ->
                stickyHeader(key = "header-$date") {
                    DayHeader(
                        date = date,
                        records = dayRecords,
                        stats = dailyStats[date].orEmpty()
                    )
                }

                items(items = dayRecords, key = { it.id }) { record ->
                    SwipeableRecordRow(
                        record = record,
                        onDelete = { onDelete(record) },
                        onEdit = { onEdit(record) }
                    )
                }
            }
        }
    }
}

/** 时间轴的日期分组标题：日期 + 当日汇总 */
@Composable
private fun DayHeader(
    date: LocalDate,
    records: List<CheckinRecord>,
    stats: List<DailyPointStat>
) {
    val dwell = stats.sumOf { it.dwellMinutes }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDate(date),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.records_group_count, records.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (dwell > 0) {
            Text(
                text = stringResource(
                    R.string.records_day_summary,
                    formatDuration(dwell),
                    records.size
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 可侧滑删除的记录行。
 *
 * 删除后由 ViewModel 弹出带「撤销」的 Snackbar，
 * 因此这里不需要在本地维护「已删除」的中间态 —— 记录会随 Room 的 Flow 立即消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRecordRow(
    record: CheckinRecord,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.records_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        RecordCard(record = record, onEdit = onEdit)
    }
}

/** 单条记录卡片 */
@Composable
private fun RecordCard(record: CheckinRecord, onEdit: () -> Unit) {
    val isEnter = record.isEnter

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (isEnter) {
                        true -> Icons.AutoMirrored.Filled.Login
                        false -> Icons.AutoMirrored.Filled.Logout
                        null -> Icons.Filled.Place
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = when (isEnter) {
                        true -> MaterialTheme.colorScheme.primary
                        false -> MaterialTheme.colorScheme.tertiary
                        null -> MaterialTheme.colorScheme.secondary
                    }
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = record.pointName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = sourceLabel(record.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (record.source == CheckInSource.MANUAL) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
                Spacer(Modifier.width(4.dp))
                // 编辑入口保留为显式按钮：侧滑是「快」，编辑是「准」，两者不冲突
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.action_edit),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = formatDateTime(record.time),
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = String.format(
                    Locale.CHINA,
                    stringResource(R.string.records_coordinate_format),
                    record.latitude,
                    record.longitude
                ),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            record.distanceMeters?.let { distance ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.records_distance_format, distance.toInt()) +
                        when (isEnter) {
                            true -> " · " + stringResource(R.string.records_enter)
                            false -> " · " + stringResource(R.string.records_exit)
                            null -> ""
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 备注：用户补记的上下文，是「记录可编辑」最常被使用的字段
            record.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = stringResource(R.string.records_note_label) + "：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 空状态：图标 + 标题 + 说明，替代原来的一行纯文字 */
@Composable
private fun EmptyRecordsHint() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.records_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
