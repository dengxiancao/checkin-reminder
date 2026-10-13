// 职责：进程内共享的围栏状态持有者，让前台服务与 UI 订阅同一份状态机数据
package com.example.checkin.geofence

import kotlinx.coroutines.flow.StateFlow

/**
 * 围栏状态的进程级共享入口。
 *
 * 前台服务与 UI 运行在**同一个进程**，因此这里用一个全局单例持有状态机：
 * - 服务（[GeofenceManager]）写入状态；
 * - UI（`MainViewModel`）通过 [states] 订阅，实时展示每个打卡点是
 *   OUTSIDE / NEAR / INSIDE / LEFT。
 *
 * 之所以不让 UI 绑定 Service（bindService）来取状态：绑定会增加
 * 生命周期复杂度（连接失败/断连重连），而本项目服务生命周期长于界面，
 * 用进程内单例 + StateFlow 更简单可靠。
 *
 * 注意：进程被杀后状态会重置为 UNKNOWN，下一次定位回调会重新建立基线。
 */
object FenceMonitor {

    /** 全局唯一的状态机实例 */
    val stateMachine: FenceStateMachine = FenceStateMachine()

    /** 各打卡点的围栏状态（Key = 打卡点 id） */
    val states: StateFlow<Map<Long, FenceRuntimeState>> = stateMachine.states
}
