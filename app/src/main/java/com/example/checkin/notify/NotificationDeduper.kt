// 职责：通知去重器，保证同一打卡点在同一状态下只提醒一次，状态变化后放行
package com.example.checkin.notify

import androidx.annotation.VisibleForTesting
import com.example.checkin.geofence.FenceState
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知去重器。
 *
 * 需求 5：**同一打卡点在 INSIDE 状态下，进入提醒只发一次**；离开后再进入才允许再次提醒。
 *
 * 实现方式：记录每个打卡点「最近一次已提醒过的状态」，
 * 只有当前状态与该记录不同时才放行通知：
 * ```
 *  INSIDE  → 放行（记为 INSIDE）     再次 INSIDE → 拦截
 *  LEFT    → 放行（记为 LEFT）       再次 LEFT   → 拦截
 *  INSIDE  → 放行（状态由 LEFT 变回 INSIDE）
 * ```
 *
 * 与 [com.example.checkin.geofence.FenceStateMachine] 的关系：
 * 状态机本身已经保证「同一 INSIDE 期间只产出一次 ENTER 事件」，
 * 本类是在通知发送侧再做一层**独立防线**，即使状态机将来被改成重复产出事件，
 * 也不会对用户造成重复轰炸。
 *
 * 纯逻辑、线程安全，便于单测。
 */
class NotificationDeduper {

    /** 打卡点 id -> 最近一次已提醒的状态 */
    private val lastNotified = ConcurrentHashMap<Long, FenceState>()

    /**
     * 判断是否应发送通知，并在放行时记录该状态。
     *
     * @param pointId 打卡点 id
     * @param state 当前围栏状态（应为 INSIDE 或 LEFT）
     * @return true 表示应发送；false 表示与上次提醒状态相同，应拦截
     */
    fun shouldNotify(pointId: Long, state: FenceState): Boolean {
        val previous = lastNotified[pointId]
        if (previous == state) return false
        lastNotified[pointId] = state
        return true
    }

    /**
     * 查询某打卡点最近一次已提醒的状态（未提醒过返回 null）。
     *
     * 仅供单元测试断言使用；生产代码不依赖去重器的内部记录。
     */
    @VisibleForTesting
    fun lastNotifiedState(pointId: Long): FenceState? = lastNotified[pointId]

    /**
     * 打卡点被删除时清理其记录，避免长期占用内存。
     *
     * 当前生产链路尚未调用（[com.example.checkin.geofence.GeofenceManager] 在
     * 「全部远离」时也会整体 [clear]），保留给后续按点增量清理的场景。
     */
    fun forget(pointId: Long) {
        lastNotified.remove(pointId)
    }

    /**
     * 仅保留这些打卡点的记录（刷新围栏时调用）。
     *
     * 目前仅由单元测试覆盖，生产链路改用 [clear] 整体清空。
     */
    @VisibleForTesting
    fun retainOnly(pointIds: Set<Long>) {
        lastNotified.keys.retainAll(pointIds)
    }

    /** 清空全部记录（停止监控时调用） */
    fun clear() {
        lastNotified.clear()
    }
}
