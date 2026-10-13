// 职责：围栏状态机（OUTSIDE/NEAR/INSIDE/LEFT）的单元测试
package com.example.checkin

import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.geofence.FenceEventType
import com.example.checkin.geofence.FenceState
import com.example.checkin.geofence.FenceStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖需求中的核心状态流转规则：
 * - 距离 <= radius 且之前是 OUTSIDE/NEAR → INSIDE，触发「进入」
 * - 距离 > radius 且之前是 INSIDE → LEFT，触发「离开」
 * - 接近（NEAR）只用于提高定位频率，不产生通知
 *
 * 动态调频调度器的用例已拆分至同包的 `LocationIntervalSchedulerTest`。
 */
class FenceStateMachineTest {

    private val centerLat = 39.909187
    private val centerLon = 116.397451

    private fun point(id: Long = 1L, radius: Int = 200, enabled: Boolean = true) = CheckinPoint(
        id = id,
        name = "测试点$id",
        latitude = centerLat,
        longitude = centerLon,
        radiusMeters = radius,
        enabled = enabled
    )

    /** 构造距中心指定米数（正北）的坐标 */
    private fun northOf(meters: Double): Pair<Double, Double> =
        (centerLat + meters / 111_195.0) to centerLon

    /** 推进一次判定 */
    private fun FenceStateMachine.push(
        points: List<CheckinPoint>,
        meters: Double,
        accuracy: Float = 0f
    ) = northOf(meters).let { (lat, lon) ->
        evaluate(points, lat, lon, accuracy, timestamp = 1_000L)
    }

    // ---------------------------------------------------------------- 基线

    @Test
    fun `首次判定只建立基线不产生事件`() {
        val sm = FenceStateMachine()
        // 第一次就在范围内，也不应触发「进入」（避免启动瞬间误报）
        val events = sm.push(listOf(point()), 50.0)
        assertTrue("首次判定不应产生事件", events.isEmpty())
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))
    }

    @Test
    fun `远离时首次判定为OUTSIDE`() {
        val sm = FenceStateMachine()
        sm.push(listOf(point()), 2000.0)
        assertEquals(FenceState.OUTSIDE, sm.stateOf(1L))
    }

    // ---------------------------------------------------------------- 进入

    @Test
    fun `从OUTSIDE进入半径内触发进入事件`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))

        sm.push(p, 1000.0)                       // 基线：OUTSIDE
        assertEquals(FenceState.OUTSIDE, sm.stateOf(1L))

        val events = sm.push(p, 150.0)           // 进入 200 米内
        assertEquals(1, events.size)
        assertEquals(FenceEventType.ENTER, events.first().type)
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))
        assertEquals(150.0, events.first().distanceMeters, 5.0)
    }

    @Test
    fun `从NEAR进入半径内触发进入事件`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))

        sm.push(p, 1000.0)                       // OUTSIDE
        sm.push(p, 260.0)                        // 200 < 260 <= 300(NEAR阈值) → NEAR
        assertEquals(FenceState.NEAR, sm.stateOf(1L))

        val events = sm.push(p, 100.0)           // 进入
        assertEquals(1, events.size)
        assertEquals(FenceEventType.ENTER, events.first().type)
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))
    }

    @Test
    fun `进入事件不会重复触发`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))
        sm.push(p, 1000.0)
        sm.push(p, 150.0)                        // 第一次进入

        // 持续在范围内移动，不应再次触发
        assertTrue(sm.push(p, 120.0).isEmpty())
        assertTrue(sm.push(p, 180.0).isEmpty())
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))
    }

    // ---------------------------------------------------------------- 离开

    @Test
    fun `从INSIDE走出半径触发离开事件`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))

        sm.push(p, 100.0)                        // 基线 INSIDE
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))

        val events = sm.push(p, 400.0)           // 超出 200 米
        assertEquals(1, events.size)
        assertEquals(FenceEventType.EXIT, events.first().type)
        assertEquals(FenceState.LEFT, sm.stateOf(1L))
    }

    @Test
    fun `离开后再次进入会重新触发进入`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))

        sm.push(p, 100.0)                        // INSIDE
        sm.push(p, 400.0)                        // LEFT（离开事件）
        assertEquals(FenceState.LEFT, sm.stateOf(1L))

        val events = sm.push(p, 150.0)           // 再次进入
        assertEquals(1, events.size)
        assertEquals(FenceEventType.ENTER, events.first().type)
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))
    }

    @Test
    fun `离开后继续远离不重复触发`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))
        sm.push(p, 100.0)
        sm.push(p, 400.0)                        // LEFT

        assertTrue("已在 LEFT 状态，不应重复触发离开", sm.push(p, 800.0).isEmpty())
        assertTrue(sm.push(p, 1500.0).isEmpty())
    }

    // ---------------------------------------------------------------- NEAR 只调频不通知

    @Test
    fun `接近时进入NEAR状态但不产生事件`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))

        sm.push(p, 1000.0)                       // OUTSIDE
        val events = sm.push(p, 250.0)           // 200 < 250 <= 300 → NEAR
        assertTrue("NEAR 不应产生通知事件", events.isEmpty())
        assertEquals(FenceState.NEAR, sm.stateOf(1L))
        assertTrue("NEAR 应被识别为接近", sm.hasNearPoint())
    }

    @Test
    fun `NEAR阈值按半径1_5倍且至少加50米`() {
        val sm = FenceStateMachine()
        // 半径 200 → max(300, 250) = 300
        assertEquals(300.0, sm.nearThresholdOf(200), 0.001)
        // 半径 100 → max(150, 150) = 150
        assertEquals(150.0, sm.nearThresholdOf(100), 0.001)
        // 半径 500 → max(750, 550) = 750
        assertEquals(750.0, sm.nearThresholdOf(500), 0.001)
    }

    @Test
    fun `从NEAR退回远处状态变为OUTSIDE`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))
        sm.push(p, 1000.0)
        sm.push(p, 250.0)                        // NEAR
        sm.push(p, 900.0)                        // 退出 NEAR
        assertEquals(FenceState.OUTSIDE, sm.stateOf(1L))
    }

    // ---------------------------------------------------------------- 边界与容差

    @Test
    fun `恰好在半径上判定为进入`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))
        sm.push(p, 1000.0)
        val events = sm.push(p, 199.0)           // 略小于 200
        assertEquals(1, events.size)
        assertEquals(FenceEventType.ENTER, events.first().type)
    }

    @Test
    fun `定位精度可放宽进入判定但不超过50米`() {
        val sm = FenceStateMachine()
        val p = listOf(point(radius = 200))
        sm.push(p, 1000.0)

        // 距离 230 米，精度 200 米：容差封顶 50 → 阈值 250 → 应判定进入
        val events = sm.push(p, 230.0, accuracy = 200f)
        assertEquals(1, events.size)
        assertEquals(FenceEventType.ENTER, events.first().type)
    }

    // ---------------------------------------------------------------- 多点与停用

    @Test
    fun `多个打卡点各自独立判定`() {
        val sm = FenceStateMachine()
        val a = point(id = 1L, radius = 200)
        val b = CheckinPoint(
            id = 2L, name = "远处的点",
            latitude = centerLat + 5000.0 / 111_195.0, longitude = centerLon,
            radiusMeters = 200
        )
        sm.push(listOf(a, b), 1000.0)

        // 点 1 在 1000 米外 → OUTSIDE；点 2 距离约 4000 米 → OUTSIDE
        assertEquals(FenceState.OUTSIDE, sm.stateOf(1L))
        assertEquals(FenceState.OUTSIDE, sm.stateOf(2L))

        // 只有点 1 进入范围
        val events = sm.push(listOf(a, b), 100.0)
        assertEquals(1, events.size)
        assertEquals(1L, events.first().checkPoint.id)
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))
        assertEquals(FenceState.OUTSIDE, sm.stateOf(2L))
    }

    @Test
    fun `停用的打卡点不参与判定`() {
        val sm = FenceStateMachine()
        val disabled = point(enabled = false)
        val events = sm.push(listOf(disabled), 50.0)
        assertTrue(events.isEmpty())
        assertEquals(FenceState.UNKNOWN, sm.stateOf(1L))
    }

    @Test
    fun `重新启用打卡点后只在状态真正变化时产生事件`() {
        val sm = FenceStateMachine()
        val enabled = point(id = 1L, radius = 200, enabled = true)
        val disabled = point(id = 1L, radius = 200, enabled = false)

        // 1) 先在范围内建立 INSIDE 状态
        sm.push(listOf(enabled), 100.0)
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))

        // 2) 停用：状态被清理，回到 UNKNOWN
        sm.push(listOf(disabled), 100.0)
        assertEquals(FenceState.UNKNOWN, sm.stateOf(1L))

        // 3) 重新启用且仍在范围内：停机期间状态已丢失，第一次判定只重建基线，
        //    不会凭空补发一条「进入」通知（避免误报）
        val first = sm.push(listOf(enabled), 100.0)
        assertTrue("重新启用后首次判定不应补发进入事件", first.isEmpty())
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))

        // 4) 之后离开 → 正常触发离开事件
        val exit = sm.push(listOf(enabled), 900.0)
        assertEquals(1, exit.size)
        assertEquals(FenceEventType.EXIT, exit.first().type)
    }

    @Test
    fun `离开后重新启用并发起新进入可正常提醒`() {
        val sm = FenceStateMachine()
        val enabled = point(id = 1L, radius = 200)
        val disabled = point(id = 1L, radius = 200, enabled = false)

        // 停用状态下处于范围外
        sm.push(listOf(disabled), 5000.0)
        assertEquals(FenceState.UNKNOWN, sm.stateOf(1L))

        // 重新启用（仍在范围外）→ 基线 OUTSIDE
        sm.push(listOf(enabled), 5000.0)
        assertEquals(FenceState.OUTSIDE, sm.stateOf(1L))

        // 进入范围 → 应正常触发进入
        val enter = sm.push(listOf(enabled), 100.0)
        assertEquals(1, enter.size)
        assertEquals(FenceEventType.ENTER, enter.first().type)
    }

    @Test
    fun `删除打卡点后状态被清理`() {
        val sm = FenceStateMachine()
        sm.push(listOf(point(id = 1L)), 100.0)
        assertTrue(sm.states.value.containsKey(1L))

        sm.push(listOf(point(id = 2L)), 100.0)
        assertFalse("已删除的点状态应被清理", sm.states.value.containsKey(1L))
    }

    // ---------------------------------------------------------------- StateFlow 与辅助

    @Test
    fun `状态通过StateFlow暴露给UI`() {
        val sm = FenceStateMachine()
        assertTrue("初始应为空", sm.states.value.isEmpty())

        sm.push(listOf(point()), 100.0)
        val snapshot = sm.states.value[1L]
        assertEquals(FenceState.INSIDE, snapshot?.state)
        assertEquals(200, snapshot?.radiusMeters)
        assertTrue((snapshot?.distanceMeters ?: Double.MAX_VALUE) < 200)
    }

    @Test
    fun `minDistance返回最近的打卡点距离`() {
        val sm = FenceStateMachine()
        val near = point(id = 1L)
        val far = CheckinPoint(
            id = 2L, name = "远点",
            latitude = centerLat + 3000.0 / 111_195.0, longitude = centerLon,
            radiusMeters = 200
        )
        sm.push(listOf(near, far), 500.0)
        val min = sm.minDistance()
        assertEquals(500.0, min!!, 20.0)
    }

    @Test
    fun `reset清空全部状态`() {
        val sm = FenceStateMachine()
        sm.push(listOf(point()), 100.0)
        assertTrue(sm.states.value.isNotEmpty())
        sm.reset()
        assertTrue(sm.states.value.isEmpty())
        assertEquals(FenceState.UNKNOWN, sm.stateOf(1L))
    }

    @Test
    fun `防抖模式下需连续多次一致才切换`() {
        val sm = FenceStateMachine(confirmSamples = 2)
        val p = listOf(point(radius = 200))
        sm.push(p, 1000.0)                       // OUTSIDE 基线

        assertTrue("第一次进入不应立即触发", sm.push(p, 100.0).isEmpty())
        assertEquals(FenceState.OUTSIDE, sm.stateOf(1L))

        val events = sm.push(p, 100.0)           // 第二次一致 → 切换
        assertEquals(1, events.size)
        assertEquals(FenceState.INSIDE, sm.stateOf(1L))
    }
}
