// 职责：动态定位频率调度器（远离降频 / 接近升频 + 迟滞 + 节流）的单元测试
package com.example.checkin

import com.example.checkin.geofence.LocationIntervalScheduler
import com.example.checkin.geofence.LocationIntervalTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态调频调度器测试：验证「全部远离 → 低频；有接近/进入 → 高频」。
 */
class LocationIntervalSchedulerTest {

    @Test
    fun `档位定义符合需求_远离30秒接近5秒`() {
        assertEquals(30_000L, LocationIntervalTier.FAR.intervalMs)
        assertEquals(5_000L, LocationIntervalTier.NEAR.intervalMs)
    }

    @Test
    fun `有接近或进入的点时切到高频`() {
        val scheduler = LocationIntervalScheduler()
        assertEquals(LocationIntervalTier.FAR, scheduler.currentTier)

        val changed = scheduler.evaluate(
            anyNearOrInside = true,
            minDistanceMeters = 250.0,
            nearestNearThreshold = 300.0,
            now = 100_000L
        )
        assertTrue("应切换到高频", changed)
        assertEquals(LocationIntervalTier.NEAR, scheduler.currentTier)
        assertEquals(5_000L, scheduler.currentTier.intervalMs)
    }

    @Test
    fun `全部远离时切回低频`() {
        val scheduler = LocationIntervalScheduler()
        scheduler.evaluate(true, 250.0, 300.0, now = 100_000L)   // 先升到高频
        assertEquals(LocationIntervalTier.NEAR, scheduler.currentTier)

        // 距离远超「NEAR 阈值 + 迟滞(100)」，且已过最小切换间隔
        val changed = scheduler.evaluate(
            anyNearOrInside = false,
            minDistanceMeters = 5_000.0,
            nearestNearThreshold = 300.0,
            now = 200_000L
        )
        assertTrue(changed)
        assertEquals(LocationIntervalTier.FAR, scheduler.currentTier)
        assertEquals(30_000L, scheduler.currentTier.intervalMs)
    }

    @Test
    fun `迟滞区间内保持档位避免抖动`() {
        val scheduler = LocationIntervalScheduler()
        scheduler.evaluate(true, 250.0, 300.0, now = 100_000L)   // 高频

        // 距离 350 米：虽已超过 NEAR 阈值 300，但未超过 300+100 迟滞 → 保持高频
        val changed = scheduler.evaluate(
            anyNearOrInside = false,
            minDistanceMeters = 350.0,
            nearestNearThreshold = 300.0,
            now = 200_000L
        )
        assertFalse("迟滞区间内不应切换", changed)
        assertEquals(LocationIntervalTier.NEAR, scheduler.currentTier)
    }

    @Test
    fun `最小切换间隔内不重复切换`() {
        val scheduler = LocationIntervalScheduler()
        scheduler.evaluate(true, 250.0, 300.0, now = 100_000L)   // 高频

        // 仅过 5 秒就要求降频：被节流拦住
        val changed = scheduler.evaluate(
            anyNearOrInside = false,
            minDistanceMeters = 9_000.0,
            nearestNearThreshold = 300.0,
            now = 105_000L
        )
        assertFalse("切换过于频繁应被节流", changed)
        assertEquals(LocationIntervalTier.NEAR, scheduler.currentTier)
    }

    @Test
    fun `没有打卡点时保持低频`() {
        val scheduler = LocationIntervalScheduler()
        val changed = scheduler.evaluate(
            anyNearOrInside = false,
            minDistanceMeters = null,
            nearestNearThreshold = null,
            now = 100_000L
        )
        assertFalse(changed)
        assertEquals(LocationIntervalTier.FAR, scheduler.currentTier)
    }

    @Test
    fun `档位相同时不重复切换`() {
        val scheduler = LocationIntervalScheduler()
        // 已经在 FAR，再次评估 FAR 不应报告切换
        val changed = scheduler.evaluate(
            anyNearOrInside = false,
            minDistanceMeters = 9_000.0,
            nearestNearThreshold = 300.0,
            now = 100_000L
        )
        assertFalse(changed)
    }

    @Test
    fun `reset回到低频与初始状态`() {
        val scheduler = LocationIntervalScheduler()
        scheduler.evaluate(true, 250.0, 300.0, now = 100_000L)
        assertEquals(LocationIntervalTier.NEAR, scheduler.currentTier)
        scheduler.reset()
        assertEquals(LocationIntervalTier.FAR, scheduler.currentTier)
    }
}
