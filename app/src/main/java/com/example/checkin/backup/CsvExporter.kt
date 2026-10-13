// 职责：打卡记录 CSV 导出（纯函数，无 Android 依赖）
package com.example.checkin.backup

import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CSV 导出器。
 *
 * 两个必须踩对的细节：
 * 1. **UTF-8 BOM**：Excel / WPS 在 Windows 上默认按本地代码页（GBK）解析 CSV，
 *    不加 BOM 时中文列名与打卡点名会变成乱码。加一个 `\uFEFF` 即可被正确识别。
 * 2. **字段转义**：打卡点名可能包含逗号、引号或换行，必须按 RFC 4180 加引号
 *    并把内部引号翻倍，否则一行会被拆成多列、后续列全部错位。
 *
 * 这里刻意把「表头文案」「来源文案」「方向文案」做成参数传入，
 * 使本类是纯函数、可被 JVM 单测完整覆盖，同时文案仍由 UI 层从 strings.xml 提供。
 */
object CsvExporter {

    /** UTF-8 BOM，必须写在文件最前面 */
    const val BOM: String = "\uFEFF"

    private const val CRLF = "\r\n"

    private val DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    /**
     * 生成 CSV 文本。
     *
     * @param records      待导出记录（建议按时间倒序传入）
     * @param headers      表头文案
     * @param sourceLabel  打卡来源 -> 文案
     * @param directionLabel 进出方向 -> 文案（`true` 进入 / `false` 离开 / `null` 无方向）
     * @param includeBom   是否在开头写入 UTF-8 BOM（导出文件应为 true）
     */
    fun toCsv(
        records: List<CheckinRecord>,
        headers: List<String>,
        sourceLabel: (CheckInSource) -> String,
        directionLabel: (Boolean?) -> String,
        zoneId: ZoneId = ZoneId.systemDefault(),
        includeBom: Boolean = true
    ): String {
        val sb = StringBuilder()
        if (includeBom) sb.append(BOM)

        sb.append(headers.joinToString(separator = ",", transform = ::escape)).append(CRLF)

        records.forEach { record ->
            sb.append(
                listOf(
                    formatTime(record.time, zoneId),
                    record.pointName,
                    sourceLabel(record.source),
                    formatCoordinate(record.latitude),
                    formatCoordinate(record.longitude),
                    record.distanceMeters?.let { it.toInt().toString() }.orEmpty(),
                    directionLabel(record.isEnter),
                    record.note.orEmpty()
                ).joinToString(separator = ",", transform = ::escape)
            ).append(CRLF)
        }

        return sb.toString()
    }

    /** 由时间戳生成建议文件名（避免使用 ':' 等文件系统非法字符） */
    fun suggestFileName(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm", Locale.CHINA)
        val stamp = Instant.ofEpochMilli(timestamp).atZone(zoneId).format(fmt)
        return "checkin_records_$stamp.csv"
    }

    private fun formatTime(timestamp: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).format(DATE_FMT)

    /** 坐标保留 6 位小数（约 0.1 米精度），与界面展示一致 */
    private fun formatCoordinate(value: Double): String = String.format(Locale.US, "%.6f", value)

    /**
     * RFC 4180 转义。
     *
     * 只有当字段包含逗号、引号、CR/LF 或首尾空白时才加引号，
     * 保证普通字段保持可读、特殊字段不被破坏。
     */
    internal fun escape(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            field != field.trim()
        if (!needsQuote) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}
