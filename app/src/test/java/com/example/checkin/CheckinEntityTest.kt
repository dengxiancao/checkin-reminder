// 职责：打卡点/打卡记录实体契约的单元测试，锁定需求规定的默认值与枚举语义
package com.example.checkin

import com.example.checkin.data.local.Converters
import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.data.local.entity.CheckinRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些用例锁定「打卡点管理」的需求契约：
 * 默认半径 200、半径档位 100/200/300/500、来源枚举语义等。
 * 一旦有人改动这些默认值，测试会立刻失败。
 */
class CheckinEntityTest {

    // ---------------------------------------------------------------- 打卡点默认值

    @Test
    fun `新建打卡点默认半径为200米`() {
        val point = CheckinPoint(name = "公司", latitude = 39.9, longitude = 116.4)
        assertEquals("默认半径必须为 200 米", 200, point.radiusMeters)
        assertEquals(200, CheckinPoint.DEFAULT_RADIUS)
    }

    @Test
    fun `新建打卡点默认启用且没有颜色`() {
        val point = CheckinPoint(name = "公司", latitude = 39.9, longitude = 116.4)
        assertTrue("默认应为启用状态", point.enabled)
        assertNull("颜色为可选字段，默认应为 null", point.color)
    }

    @Test
    fun `新建打卡点默认开启进出提醒`() {
        val point = CheckinPoint(name = "公司", latitude = 39.9, longitude = 116.4)
        assertTrue(point.notifyOnEnter)
        assertTrue(point.notifyOnExit)
    }

    @Test
    fun `新建打卡点会自动填充创建时间`() {
        val before = System.currentTimeMillis()
        val point = CheckinPoint(name = "公司", latitude = 39.9, longitude = 116.4)
        val after = System.currentTimeMillis()
        assertTrue("createdAt 应落在构造时间窗口内", point.createdAt in before..after)
    }

    // ---------------------------------------------------------------- 半径档位与收敛

    @Test
    fun `半径档位正好是100_200_300_500`() {
        assertEquals(listOf(100, 200, 300, 500), CheckinPoint.RADIUS_PRESETS)
    }

    @Test
    fun `半径下限为100上限为500`() {
        assertEquals(100, CheckinPoint.MIN_RADIUS)
        assertEquals(500, CheckinPoint.MAX_RADIUS)
        assertEquals(100, CheckinPoint.clampRadius(0))
        assertEquals(100, CheckinPoint.clampRadius(99))
        assertEquals(500, CheckinPoint.clampRadius(501))
        assertEquals(500, CheckinPoint.clampRadius(Int.MAX_VALUE))
    }

    @Test
    fun `合法半径原样保留`() {
        CheckinPoint.RADIUS_PRESETS.forEach { preset ->
            assertEquals(preset, CheckinPoint.clampRadius(preset))
        }
    }

    // ---------------------------------------------------------------- 打卡来源

    @Test
    fun `打卡来源包含手动、提醒后确认与离开三种`() {
        val names = CheckInSource.entries.map { it.name }
        assertEquals(listOf("MANUAL", "AUTO_CONFIRMED", "FENCE_EXIT"), names)
    }

    @Test
    fun `来源枚举经转换器序列化后可无损还原`() {
        val converters = Converters()
        CheckInSource.entries.forEach { source ->
            assertEquals(
                "枚举 $source 经 字符串 往返后应保持不变",
                source,
                converters.toCheckInSource(converters.fromCheckInSource(source))
            )
        }
    }

    @Test
    fun `未知来源字符串降级为手动打卡`() {
        assertEquals(CheckInSource.MANUAL, Converters().toCheckInSource("SOME_FUTURE_VALUE"))
    }

    // ---------------------------------------------------------------- 打卡记录默认值

    @Test
    fun `新建记录默认为手动打卡`() {
        val record = CheckinRecord(
            pointId = 1L,
            pointName = "公司",
            latitude = 39.9,
            longitude = 116.4
        )
        assertEquals(CheckInSource.MANUAL, record.source)
    }

    @Test
    fun `新建记录自动填充打卡时间`() {
        val before = System.currentTimeMillis()
        val record = CheckinRecord(
            pointId = 1L,
            pointName = "公司",
            latitude = 39.9,
            longitude = 116.4
        )
        val after = System.currentTimeMillis()
        assertTrue("time 应落在构造时间窗口内", record.time in before..after)
    }

    @Test
    fun `围栏扩展字段默认为空`() {
        val record = CheckinRecord(
            pointId = 1L,
            pointName = "公司",
            latitude = 39.9,
            longitude = 116.4
        )
        // 手动打卡没有进出概念，也没有距离
        assertNull(record.isEnter)
        assertNull(record.distanceMeters)
    }

    @Test
    fun `围栏产生的记录可以带上进出与距离`() {
        val record = CheckinRecord(
            pointId = 1L,
            pointName = "公司",
            latitude = 39.9,
            longitude = 116.4,
            source = CheckInSource.AUTO_CONFIRMED,
            isEnter = true,
            distanceMeters = 42.5
        )
        assertEquals(CheckInSource.AUTO_CONFIRMED, record.source)
        assertEquals(true, record.isEnter)
        assertEquals(42.5, record.distanceMeters!!, 0.001)
        assertFalse("提醒后确认不等于手动打卡", record.source == CheckInSource.MANUAL)
    }
}
