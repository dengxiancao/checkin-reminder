// 职责：通知去重器与打卡落库语义的单元测试（防重复提醒）
package com.example.checkin

import com.example.checkin.geofence.FenceState
import com.example.checkin.notify.NotificationDeduper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖需求 5：同一打卡点在同一状态下只提醒一次，离开后再次进入允许重新提醒。
 */
class NotificationDeduperTest {

    @Test
    fun `首次进入应放行通知`() {
        val deduper = NotificationDeduper()
        assertTrue(deduper.shouldNotify(1L, FenceState.INSIDE))
    }

    @Test
    fun `连续停留在INSIDE只提醒一次`() {
        val deduper = NotificationDeduper()
        assertTrue("第一次进入应提醒", deduper.shouldNotify(1L, FenceState.INSIDE))

        // 模拟用户在范围内持续停留：状态机不会重复产出事件，
        // 但即使被重复调用，去重器也必须拦住
        repeat(5) {
            assertFalse("同一 INSIDE 状态不应重复提醒", deduper.shouldNotify(1L, FenceState.INSIDE))
        }
    }

    @Test
    fun `离开后再次进入可以重新提醒`() {
        val deduper = NotificationDeduper()
        assertTrue(deduper.shouldNotify(1L, FenceState.INSIDE))   // 进入
        assertTrue(deduper.shouldNotify(1L, FenceState.LEFT))     // 离开
        assertTrue("LEFT 之后再次进入应重新提醒", deduper.shouldNotify(1L, FenceState.INSIDE))
    }

    @Test
    fun `连续LEFT只提醒一次`() {
        val deduper = NotificationDeduper()
        assertTrue(deduper.shouldNotify(1L, FenceState.LEFT))
        assertFalse(deduper.shouldNotify(1L, FenceState.LEFT))
    }

    @Test
    fun `多个打卡点各自独立去重`() {
        val deduper = NotificationDeduper()
        assertTrue(deduper.shouldNotify(1L, FenceState.INSIDE))
        // 点 2 是首次进入，不应被点 1 的记录影响
        assertTrue(deduper.shouldNotify(2L, FenceState.INSIDE))
        // 点 1 再次进入才被拦截
        assertFalse(deduper.shouldNotify(1L, FenceState.INSIDE))
    }

    @Test
    fun `完整进出循环每次都提醒`() {
        val deduper = NotificationDeduper()
        repeat(3) { round ->
            assertTrue("第 ${round + 1} 轮进入应提醒", deduper.shouldNotify(7L, FenceState.INSIDE))
            assertTrue("第 ${round + 1} 轮离开应提醒", deduper.shouldNotify(7L, FenceState.LEFT))
        }
    }

    @Test
    fun `可查询最近一次已提醒状态`() {
        val deduper = NotificationDeduper()
        assertNull(deduper.lastNotifiedState(1L))
        deduper.shouldNotify(1L, FenceState.INSIDE)
        assertEquals(FenceState.INSIDE, deduper.lastNotifiedState(1L))
    }

    @Test
    fun `forget后重新提醒`() {
        val deduper = NotificationDeduper()
        deduper.shouldNotify(1L, FenceState.INSIDE)
        assertFalse(deduper.shouldNotify(1L, FenceState.INSIDE))

        deduper.forget(1L)
        assertTrue("忘记记录后应可再次提醒", deduper.shouldNotify(1L, FenceState.INSIDE))
    }

    @Test
    fun `retainOnly清理已删除打卡点`() {
        val deduper = NotificationDeduper()
        deduper.shouldNotify(1L, FenceState.INSIDE)
        deduper.shouldNotify(2L, FenceState.INSIDE)

        deduper.retainOnly(setOf(1L))
        assertNull("被删除的点应被清理", deduper.lastNotifiedState(2L))
        assertEquals(FenceState.INSIDE, deduper.lastNotifiedState(1L))
    }

    @Test
    fun `clear清空全部记录`() {
        val deduper = NotificationDeduper()
        deduper.shouldNotify(1L, FenceState.INSIDE)
        deduper.clear()
        assertNull(deduper.lastNotifiedState(1L))
        assertTrue("清空后应重新提醒", deduper.shouldNotify(1L, FenceState.INSIDE))
    }

    // ---------------------------------------------------------------- 与状态机联动

    @Test
    fun `状态机配合去重器实现停留期间零重复提醒`() {
        // 完整模拟一段轨迹：远处 → 接近 → 进入 → 在范围内走动 → 离开
        val sm = com.example.checkin.geofence.FenceStateMachine()
        val deduper = NotificationDeduper()
        val point = com.example.checkin.data.local.entity.CheckinPoint(
            id = 1L, name = "公司",
            latitude = 39.909187, longitude = 116.397451,
            radiusMeters = 200
        )
        val points = listOf(point)

        fun push(meters: Double): Int {
            val lat = 39.909187 + meters / 111_195.0
            return sm.evaluate(points, lat, 116.397451, 0f, timestamp = 1_000L)
                .count { deduper.shouldNotify(it.checkPoint.id, it.newState) }
        }

        assertEquals("远处不提醒", 0, push(2000.0))
        assertEquals("接近(NEAR)不产生事件", 0, push(260.0))
        assertEquals("进入应提醒一次", 1, push(100.0))
        assertEquals("范围内走动不重复提醒", 0, push(150.0))
        assertEquals("仍在范围内不重复提醒", 0, push(50.0))
        assertEquals("离开应提醒一次", 1, push(1000.0))
        assertEquals("离开后继续远离不重复", 0, push(3000.0))
        assertEquals("再次进入应重新提醒", 1, push(100.0))
    }
}
