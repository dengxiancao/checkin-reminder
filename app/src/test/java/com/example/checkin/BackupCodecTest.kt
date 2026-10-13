// 职责：备份编解码与 JSON 解析单元测试，锁定格式兼容性与错误可读性
package com.example.checkin

import com.example.checkin.backup.BackupCodec
import com.example.checkin.backup.BackupPrefs
import com.example.checkin.backup.BackupSnapshot
import com.example.checkin.backup.Json
import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.data.local.entity.CheckinRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId

/**
 * 备份链路的用例。
 *
 * 备份恢复是唯一「一次操作影响全部数据」的功能，一旦解码出错，
 * 用户可能拿到一份看似成功、实则缺字段的备份。
 * 因此这里既测正常往返，也把**每一种失败都测成可读异常**而不是崩溃或静默丢数据。
 */
class BackupCodecTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // ---------------------------------------------------------------- 往返一致

    @Test
    fun `完整快照编解码后无损还原`() {
        val snapshot = BackupSnapshot(
            exportedAt = 1_750_000_000_000L,
            points = listOf(point(id = 7L, name = "公司"), point(id = 9L, name = "家")),
            records = listOf(
                record(id = 101L, pointId = 7L, note = "补记"),
                record(id = 102L, pointId = 9L, isEnter = false, source = CheckInSource.FENCE_EXIT)
            ),
            prefs = BackupPrefs(sound = false, vibrate = true, fullScreen = false)
        )

        val decoded = BackupCodec.decode(BackupCodec.encode(snapshot))

        assertEquals(snapshot.exportedAt, decoded.exportedAt)
        assertEquals(BackupCodec.SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(2, decoded.points.size)
        assertEquals(2, decoded.records.size)
        assertEquals(BackupPrefs(sound = false, vibrate = true, fullScreen = false), decoded.prefs)
        assertEquals(0, decoded.droppedRecords)
    }

    @Test
    fun `打卡点字段逐个还原`() {
        val original = CheckinPoint(
            id = 3L,
            name = "公司",
            latitude = 30.123456,
            longitude = 120.654321,
            radiusMeters = 300,
            enabled = false,
            createdAt = 1_700_000_000_000L,
            color = "#FF1B6C3A",
            notifyOnEnter = false,
            notifyOnExit = true,
            updatedAt = 1_700_000_111_000L
        )

        val decoded = BackupCodec.decode(
            BackupCodec.encode(BackupSnapshot(0L, points = listOf(original), records = emptyList()))
        ).points.single()

        assertEquals(original, decoded)
    }

    @Test
    fun `记录字段逐个还原含备注与进出方向`() {
        val original = CheckinRecord(
            id = 55L,
            pointId = 3L,
            pointName = "公司",
            latitude = 30.5,
            longitude = 120.5,
            time = 1_700_000_000_000L,
            source = CheckInSource.AUTO_CONFIRMED,
            isEnter = true,
            distanceMeters = 42.5,
            note = "忘记打卡后补记"
        )

        val decoded = BackupCodec.decode(
            BackupCodec.encode(
                BackupSnapshot(
                    0L,
                    points = listOf(point(id = 3L, name = "公司")),
                    records = listOf(original)
                )
            )
        ).records.single()

        assertEquals(original, decoded)
    }

    @Test
    fun `空备份可以正常往返`() {
        val decoded = BackupCodec.decode(
            BackupCodec.encode(BackupSnapshot(0L, points = emptyList(), records = emptyList()))
        )
        assertTrue(decoded.points.isEmpty())
        assertTrue(decoded.records.isEmpty())
    }

    // ---------------------------------------------------------------- 文本健壮性

    @Test
    fun `名称含引号与反斜杠可无损还原`() {
        val tricky = "公司\"总部\"\\分部"
        val decoded = BackupCodec.decode(
            BackupCodec.encode(
                BackupSnapshot(0L, points = listOf(point(id = 1L, name = tricky)), records = emptyList())
            )
        )
        assertEquals(tricky, decoded.points.single().name)
    }

    @Test
    fun `名称含换行与制表符可无损还原`() {
        val tricky = "第一行\n第二行\t带制表"
        val decoded = BackupCodec.decode(
            BackupCodec.encode(
                BackupSnapshot(0L, points = listOf(point(id = 1L, name = tricky)), records = emptyList())
            )
        )
        assertEquals(tricky, decoded.points.single().name)
    }

    @Test
    fun `名称含emoji可无损还原`() {
        val tricky = "公司🏢打卡"
        val decoded = BackupCodec.decode(
            BackupCodec.encode(
                BackupSnapshot(0L, points = listOf(point(id = 1L, name = tricky)), records = emptyList())
            )
        )
        assertEquals(tricky, decoded.points.single().name)
    }

    @Test
    fun `备注为空字符串时归一化为null`() {
        val decoded = BackupCodec.decode(
            BackupCodec.encode(
                BackupSnapshot(
                    0L,
                    points = listOf(point(id = 1L, name = "公司")),
                    records = listOf(record(id = 1L, pointId = 1L, note = ""))
                )
            )
        )
        assertNull(decoded.records.single().note)
    }

    // ---------------------------------------------------------------- 版本与格式校验

    @Test
    fun `版本高于当前支持时拒绝恢复`() {
        val json = """
            {"app":"com.example.checkin","schemaVersion":99,"exportedAt":0,
             "points":[],"records":[],"prefs":{}}
        """.trimIndent()

        try {
            BackupCodec.decode(json)
            fail("应当拒绝更高版本的备份")
        } catch (e: BackupCodec.UnsupportedVersionException) {
            assertEquals(99, e.found)
            assertTrue("错误信息要带上版本号，便于用户判断", e.message!!.contains("99"))
        }
    }

    @Test
    fun `损坏的JSON给出可读错误`() {
        try {
            BackupCodec.decode("{ this is not json ")
            fail("非法 JSON 应当抛可读异常")
        } catch (e: BackupCodec.MalformedBackupException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `非本应用的备份被拒绝`() {
        val json = """{"app":"com.other.app","schemaVersion":1,"points":[],"records":[]}"""
        try {
            BackupCodec.decode(json)
            fail("应拒绝其他应用的备份文件")
        } catch (e: BackupCodec.MalformedBackupException) {
            assertTrue(e.message!!.contains("com.other.app"))
        }
    }

    @Test
    fun `顶层不是对象时给出可读错误`() {
        try {
            BackupCodec.decode("[1,2,3]")
            fail("顶层为数组应当被拒绝")
        } catch (e: BackupCodec.MalformedBackupException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `打卡点缺少名称时给出可读错误`() {
        val json = """{"app":"com.example.checkin","schemaVersion":1,"points":[{"id":1}],"records":[]}"""
        try {
            BackupCodec.decode(json)
            fail("缺少 name 应当被拒绝")
        } catch (e: BackupCodec.MalformedBackupException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    // ---------------------------------------------------------------- 外键保护

    @Test
    fun `找不到对应打卡点的记录会被跳过并计数`() {
        val json = """
            {"app":"com.example.checkin","schemaVersion":1,"exportedAt":0,
             "points":[{"id":1,"name":"公司","latitude":30.0,"longitude":120.0,"radiusMeters":200,
                        "enabled":true,"createdAt":1,"updatedAt":1,"notifyOnEnter":true,"notifyOnExit":true}],
             "records":[
               {"id":10,"pointId":1,"pointName":"公司","latitude":30.0,"longitude":120.0,"time":1},
               {"id":11,"pointId":999,"pointName":"已经删掉的点","latitude":30.0,"longitude":120.0,"time":2}
             ]}
        """.trimIndent()

        val decoded = BackupCodec.decode(json)

        assertEquals(1, decoded.records.size)
        assertEquals(10L, decoded.records.single().id)
        assertEquals("被跳过的记录数要上报，让用户知道有数据没恢复", 1, decoded.droppedRecords)
    }

    // ---------------------------------------------------------------- 容错降级

    @Test
    fun `未知的来源枚举降级为手动打卡`() {
        val json = """
            {"app":"com.example.checkin","schemaVersion":1,
             "points":[{"id":1,"name":"公司","latitude":30.0,"longitude":120.0,"radiusMeters":200,
                        "enabled":true,"createdAt":1,"updatedAt":1,"notifyOnEnter":true,"notifyOnExit":true}],
             "records":[{"id":10,"pointId":1,"pointName":"公司","latitude":30.0,"longitude":120.0,
                         "time":1,"source":"SOME_FUTURE_SOURCE"}]}
        """.trimIndent()

        assertEquals(CheckInSource.MANUAL, BackupCodec.decode(json).records.single().source)
    }

    @Test
    fun `缺失可选字段时使用合理默认值`() {
        val json = """
            {"app":"com.example.checkin","schemaVersion":1,
             "points":[{"name":"公司","latitude":30.0,"longitude":120.0}],
             "records":[]}
        """.trimIndent()

        val point = BackupCodec.decode(json).points.single()
        assertEquals(200, point.radiusMeters)
        assertTrue(point.enabled)
        assertNull(point.color)
    }

    @Test
    fun `备份中越界的半径会被收敛到合法区间`() {
        val json = """
            {"app":"com.example.checkin","schemaVersion":1,
             "points":[{"id":1,"name":"公司","latitude":30.0,"longitude":120.0,"radiusMeters":99999}],
             "records":[]}
        """.trimIndent()

        assertEquals(500, BackupCodec.decode(json).points.single().radiusMeters)
    }

    @Test
    fun `建议文件名格式正确`() {
        val name = BackupCodec.suggestFileName(1_700_000_000_000L, zone)
        assertTrue("应以 checkin_backup_ 开头并以 .json 结尾", name.startsWith("checkin_backup_"))
        assertTrue(name.endsWith(".json"))
    }

    // ---------------------------------------------------------------- JSON 解析器

    @Test
    fun `解析嵌套对象与数组`() {
        val parsed = Json.parse("""{"a":{"b":[1,2,3]},"c":true,"d":null}""") as Map<*, *>
        val nested = parsed["a"] as Map<*, *>
        assertEquals(listOf(1.0, 2.0, 3.0), nested["b"])
        assertEquals(true, parsed["c"])
        assertNull(parsed["d"])
    }

    @Test
    fun `解析全部转义序列`() {
        val parsed = Json.parse("""{"s":"a\"b\\c\nd\te\rf\/g"}""") as Map<*, *>
        assertEquals("a\"b\\c\nd\te\rf/g", parsed["s"])
    }

    @Test
    fun `解析Unicode转义与代理对`() {
        val parsed = Json.parse("""{"s":"\u4e2d\u6587\uD83C\uDFE2"}""") as Map<*, *>
        assertEquals("中文🏢", parsed["s"])
    }

    @Test
    fun `解析负数与科学计数法`() {
        val parsed = Json.parse("""{"a":-12.5,"b":1e3,"c":0}""") as Map<*, *>
        assertEquals(-12.5, parsed["a"])
        assertEquals(1000.0, parsed["b"])
        assertEquals(0.0, parsed["c"])
    }

    @Test
    fun `解析空对象与空数组`() {
        assertEquals(emptyMap<String, Any?>(), Json.parse("{}"))
        assertEquals(emptyList<Any?>(), Json.parse("[]"))
    }

    @Test
    fun `允许键值之间有无空白`() {
        val parsed = Json.parse("{\n  \"a\" : 1 ,\n  \"b\" : \"x\"\n}") as Map<*, *>
        assertEquals(1.0, parsed["a"])
        assertEquals("x", parsed["b"])
    }

    @Test
    fun `拒绝未闭合的字符串`() {
        try {
            Json.parse("""{"a":"未闭合}""")
            fail("未闭合字符串应当报错")
        } catch (e: Json.JsonException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `拒绝尾随多余内容`() {
        try {
            Json.parse("""{"a":1} extra""")
            fail("多余内容应当报错")
        } catch (e: Json.JsonException) {
            assertTrue(e.message!!.contains("多余"))
        }
    }

    @Test
    fun `转义函数会输出合法的JSON字符串`() {
        val escaped = Json.escape("引号\" 反斜杠\\ 换行\n 制表\t")
        assertEquals("引号\\\" 反斜杠\\\\ 换行\\n 制表\\t", escaped)
        // 生成的片段必须能被自己解析回来
        assertEquals(
            "引号\" 反斜杠\\ 换行\n 制表\t",
            (Json.parse("{\"s\":\"$escaped\"}") as Map<*, *>)["s"]
        )
    }

    @Test
    fun `非有限浮点数降级为null而不是产出非法JSON`() {
        assertEquals("null", Json.number(Double.NaN))
        assertEquals("null", Json.number(Double.POSITIVE_INFINITY))
        assertEquals("42.5", Json.number(42.5))
    }

    // ---------------------------------------------------------------- 辅助

    private fun point(id: Long, name: String) = CheckinPoint(
        id = id,
        name = name,
        latitude = 30.0,
        longitude = 120.0,
        radiusMeters = 200,
        enabled = true,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L
    )

    private fun record(
        id: Long,
        pointId: Long,
        isEnter: Boolean? = true,
        source: CheckInSource = CheckInSource.MANUAL,
        note: String? = null
    ) = CheckinRecord(
        id = id,
        pointId = pointId,
        pointName = "公司",
        latitude = 30.0,
        longitude = 120.0,
        time = 1_700_000_000_000L,
        source = source,
        isEnter = isEnter,
        distanceMeters = null,
        note = note
    )
}
