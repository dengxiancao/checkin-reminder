// 职责：看活广播接收器，被闹钟唤醒后检查监控是否应开启并拉起前台服务
package com.example.checkin.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.checkin.data.repository.ServiceLocator
import com.example.checkin.util.PreferencesStore
import com.example.checkin.util.ServiceWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 服务看活接收器。
 *
 * 由 [ServiceWatchdog] 排定的 AlarmManager 任务唤醒，做三件事：
 * 1. 检查用户是否仍开着监控（关了就不再拉起，避免强拉用户不想要的服务）；
 * 2. 检查是否还有启用中的打卡点（没有就无需常驻前台服务）；
 * 3. 拉起 [CheckinForegroundService] 并重新排下一次看活。
 *
 * 广播不会唤醒已停止的 App（force-stop 后系统不允许接收任何广播），
 * 这是 Android 的安全边界，任何 App 都无法绕过。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG) return
        Log.i(TAG, "看活任务触发，检查监控状态")

        val appContext = context.applicationContext
        // goAsync 延长广播生命周期，保证协程能在进程被回收前跑完
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = PreferencesStore.get(appContext)

                // 1) 用户是否仍开启监控
                if (!store.readMonitoringEnabled()) {
                    Log.i(TAG, "监控开关为关闭，取消看活任务")
                    ServiceWatchdog.cancel(appContext)
                    return@launch
                }

                // 2) 是否还有启用中的打卡点
                val points = runCatching {
                    ServiceLocator.checkinPointRepository(appContext).getEnabledPoints()
                }.onFailure {
                    Log.e(TAG, "读取打卡点失败", it)
                }.getOrDefault(emptyList())

                if (points.isEmpty()) {
                    Log.i(TAG, "没有启用中的打卡点，取消看活任务")
                    ServiceWatchdog.cancel(appContext)
                    return@launch
                }

                // 3) 拉起服务（重复 start 是幂等的，服务内部会判断是否已在运行）
                Log.i(TAG, "拉起前台监控服务（${points.size} 个打卡点）")
                CheckinForegroundService.start(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "看活处理失败", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WatchdogReceiver"

        /** 看活动作 */
        const val ACTION_WATCHDOG = "com.example.checkin.action.WATCHDOG"
    }
}
