// 职责：CSV 导出单元测试，锁定 BOM、转义与列顺序
package com.example.checkin

import com.example.checkin.backup.CsvExporter
import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * CSV 导出的用例。
 *
 * 这个功能有两个「看起来没问题、实际一定出问题」的点，所以专门钉死：
 * 1. **UTF-8 BOM**：不加 BOM 时 Excel / WPS 在中文 Windows 上按 GBK 解析，中文全乱码；
 * 2. **字段转义**：打卡点名叫「公司，总部」时如果不加引号，一行会被拆成两列，
 *    后面所有列整体错位 —— 用户打开表格会看到一列串到别的字段里。
 */
class CsvExporterTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private val headers =
        listOf("日期时间", "打卡点", "来源", "纬度", "经度", "距离(米)", "进出方向", "备注")

    private fun sourceLabel(source: CheckInSource): String = when (source) {
        CheckInSource.MANUAL -> "手动打卡"
        CheckInSource.AUTO_CONFIRMED -> "提醒后确认"
        CheckInSource.FENCE_EXIT -> "离开记录"
    }

    private fun directionLabel(isEnter: Boolean?): String = when (isEnter) {
        true -> "进入"
        false -> "离开"
        null -> "—"
    }

    private fun build(records: List<CheckinRecord>): String = CsvExporter.toCsv(
        records = records,
        headers = headers,
        sourceLabel = ::sourceLabel,
        directionLabel = ::directionLabel,
        zoneId = zone
    )

    // ---------------------------------------------------------------- BOM

    @Test
    fun `导出内容以UTF8_BOM开头`() {
        val csv = build(emptyList())
        assertTrue("必须以 BOM 开头，否则 Excel 打开中文乱码", csv.startsWith(CsvExporter.BOM))
        assertEquals('\uFEFF', csv[0])
    }

    @Test
    fun `可以关闭BOM用于纯数据消费方`() {
        val csv = CsvExporter.toCsv(
            records = emptyList(),
            headers = headers,
            sourceLabel = ::sourceLabel,
            directionLabel = ::directionLabel,
            zoneId = zone,
            includeBom = false
        )
        assertFalse(csv.startsWith(CsvExporter.BOM))
    }

    // ---------------------------------------------------------------- 结构

    @Test
    fun `第一行是表头`() {
        val csv = build(emptyList())
        val firstLine = csv.removePrefix(CsvExporter.BOM).lineSequence().first()
        assertEquals(headers.joinToString(","), firstLine)
    }

    @Test
    fun `没有记录时只有表头`() {
        val csv = build(emptyList())
        val lines = csv.removePrefix(CsvExporter.BOM).trim().lines().filter { it.isNotEmpty() }
        assertEquals(1, lines.size)
    }

    @Test
    fun `每条记录占一行且列数一致`() {
        val csv = build(
            listOf(
                record(at(2026, 8, 1, 9, 0), isEnter = true, source = CheckInSource.AUTO_CONFIRMED, distance = 42.5),
                record(at(2026, 8, 1, 18, 0), isEnter = false, source = CheckInSource.FENCE_EXIT, distance = 220.0)
            )
        )
        val lines = csv.removePrefix(CsvExporter.BOM).trim().lines().filter { it.isNotEmpty() }

        assertEquals(3, lines.size)
        lines.forEach { line ->
            assertEquals("每行必须是 8 列（新增备注列）", 8, splitTopLevel(line).size)
        }
    }

    @Test
    fun `使用CRLF换行以兼容Excel`() {
        val csv = build(listOf(record(at(2026, 8, 2, 9, 0))))
        assertTrue(csv.contains("\r\n"))
    }

    // ---------------------------------------------------------------- 内容

    @Test
    fun `时间按本地时区格式化`() {
        val csv = build(listOf(record(at(2026, 8, 3, 9, 5))))
        assertTrue("应包含 yyyy-MM-dd HH:mm:ss 形式的时间", csv.contains("2026-08-03 09:05:00"))
    }

    @Test
    fun `坐标保留六位小数`() {
        val csv = build(listOf(record(at(2026, 8, 3, 9, 5), lat = 30.123456789, lng = 120.987654321)))
        assertTrue(csv.contains("30.123457"))
        assertTrue(csv.contains("120.987654"))
    }

    @Test
    fun `手动打卡没有方向时输出占位符`() {
        val csv = build(listOf(record(at(2026, 8, 4, 9, 0), isEnter = null, source = CheckInSource.MANUAL)))
        assertTrue(csv.contains("—"))
    }

    @Test
    fun `距离为空时该列留空而不是写null`() {
        val csv = build(listOf(record(at(2026, 8, 4, 9, 0), distance = null)))
        assertFalse("不应把 null 字样写进文件", csv.contains("null"))
    }

    @Test
    fun `备注会写入最后一列`() {
        val csv = build(listOf(record(at(2026, 8, 5, 9, 0), note = "忘记打卡后补记")))
        assertTrue(csv.contains("忘记打卡后补记"))
    }

    // ---------------------------------------------------------------- 转义（最容易出错的点）

    @Test
    fun `含逗号的字段会被引号包裹`() {
        val csv = build(listOf(record(at(2026, 8, 6, 9, 0), pointName = "公司,总部")))
        assertTrue("含逗号的字段必须加引号", csv.contains("\"公司,总部\""))
    }

    @Test
    fun `含双引号的字段会翻倍转义`() {
        val csv = build(listOf(record(at(2026, 8, 6, 9, 0), pointName = "他说\"到了\"")))
        assertTrue(csv.contains("\"他说\"\"到了\"\"\""))
    }

    @Test
    fun `含换行的字段被引号包裹从而不破坏行结构`() {
        val csv = build(listOf(record(at(2026, 8, 6, 9, 0), pointName = "第一行\n第二行")))
        assertTrue(csv.contains("\"第一行\n第二行\""))
    }

    @Test
    fun `普通字段不会被无谓地加引号`() {
        assertEquals("公司", CsvExporter.escape("公司"))
        assertEquals("30.123456", CsvExporter.escape("30.123456"))
    }

    @Test
    fun `首尾空白会被保留通过引号`() {
        assertEquals("\" 公司 \"", CsvExporter.escape(" 公司 "))
    }

    @Test
    fun `转义后按顶层逗号切分列数依然正确`() {
        val csv = build(listOf(record(at(2026, 8, 7, 9, 0), pointName = "公司,总部", note = "a,b\"c")))
        val dataLine = csv.removePrefix(CsvExporter.BOM).trim().lines().last()
        assertEquals(8, splitTopLevel(dataLine).size)
    }

    // ---------------------------------------------------------------- 文件名

    @Test
    fun `建议文件名不含冒号等非法字符`() {
        val name = CsvExporter.suggestFileName(at(2026, 8, 8, 9, 30), zone)
        assertEquals("checkin_records_20260808_0930.csv", name)
        assertFalse(name.contains(":"))
    }

    // ---------------------------------------------------------------- 辅助

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    private fun record(
        time: Long,
        pointName: String = "公司",
        lat: Double = 30.0,
        lng: Double = 120.0,
        source: CheckInSource = CheckInSource.MANUAL,
        isEnter: Boolean? = null,
        distance: Double? = null,
        note: String? = null
    ) = CheckinRecord(
        pointId = 1L,
        pointName = pointName,
        latitude = lat,
        longitude = lng,
        time = time,
        source = source,
        isEnter = isEnter,
        distanceMeters = distance,
        note = note
    )

    /** 按 CSV 规则切分一行（忽略引号内的逗号），用于校验列数 */
    private fun splitTopLevel(line: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }

                c == ',' && !inQuotes -> {
                    result += sb.toString()
                    sb.clear()
                }

                else -> sb.append(c)
            }
            i++
        }
        result += sb.toString()
        return result
    }
}
