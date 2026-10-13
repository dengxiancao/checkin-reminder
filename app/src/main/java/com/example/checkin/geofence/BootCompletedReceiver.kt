// 职责：开机广播接收器，设备重启/应用更新后读取已保存的打卡点并自动恢复围栏与前台服务
package com.example.checkin.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.checkin.data.repository.ServiceLocator
import com.example.checkin.service.CheckinForegroundService
import com.example.checkin.util.PreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 监听 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED。
 *
 * 开机后系统会清空所有内存态，必须在此重新拉起前台定位服务并重新“注册”围栏
 * （即从 Room 读取启用中的打卡点 + 恢复上次判定状态）。
 *
 * 注意：BroadcastReceiver.onReceive 有 10 秒执行上限，因此耗时操作全部丢到协程里，
 * 并且只做「启动服务」这一件轻量事——真正的围栏注册由服务自身完成，避免 ANR。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // 只处理我们关心的三个动作
        if (action !in HANDLED_ACTIONS) {
            Log.d(TAG, "忽略无关广播：$action")
            return
        }

        Log.i(TAG, "收到 $action，准备恢复围栏监听")

        val appContext = context.applicationContext
        // goAsync 延长广播生命周期，保证协程能在进程被回收前完成
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1) 先检查用户上次是否开着监控：关闭状态不应在开机后被莫名其妙拉起
                val monitoringEnabled = runCatching {
                    PreferencesStore(appContext).readMonitoringEnabled()
                }.onFailure { Log.e(TAG, "读取监控开关失败", it) }.getOrDefault(false)

                if (!monitoringEnabled) {
                    Log.i(TAG, "上次退出时监控为关闭状态，开机后不自动启动")
                    return@launch
                }

                // 2) 再确认确实有启用中的打卡点，避免无意义地常驻一个前台服务
                val pointRepository = ServiceLocator.checkinPointRepository(appContext)
                val points = runCatching { pointRepository.getEnabledPoints() }
                    .onFailure { Log.e(TAG, "读取打卡点失败", it) }
                    .getOrDefault(emptyList())

                if (points.isEmpty()) {
                    Log.i(TAG, "没有启用中的打卡点，开机后不启动监控服务")
                    return@launch
                }

                Log.i(TAG, "监控开关为开启且发现 ${points.size} 个打卡点，启动前台监控服务")
                CheckinForegroundService.start(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "开机恢复围栏失败", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
