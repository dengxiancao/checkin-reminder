// 职责：服务看活器，用 AlarmManager 周期性检查并拉起前台服务，提升被系统杀死后的恢复率
package com.example.checkin.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 前台服务看活器。
 *
 * 背景：`START_STICKY` 只保证「系统愿意时」重建服务，国产 ROM 在清理后台后
 * 往往直接冻结进程，连 `onTaskRemoved` 都不一定回调。因此在
 * `onTaskRemoved` 里额外用 [AlarmManager] 排一个定时任务，
 * 由 [com.example.checkin.service.WatchdogReceiver] 到点检查服务是否活着，
 * 不在就重新拉起。
 *
 * 精度取舍：
 * - Android 12+ 需要 `SCHEDULE_EXACT_ALARM` 才能用精确闹钟，
 *   未授权或调用失败时自动降级为 `setAndAllowWhileIdle`（不精确但能穿透 Doze）；
 * - 故意**不用** `setAlarmClock`：它会常驻一个闹钟图标，用户可见，体验很差。
 */
object ServiceWatchdog {

    private const val TAG = "ServiceWatchdog"

    /** 看活间隔：15 分钟 */
    const val INTERVAL_MS = 15 * 60 * 1000L

    private const val REQUEST_CODE = 9001

    /**
     * 排下一次看活任务。
     *
     * 应在以下时机调用：
     * - 服务成功进入前台后（建立周期性看活）；
     * - `onTaskRemoved`（被划掉后台后尽快自恢复，这里用较短延迟）。
     *
     * @param delayMs 首次触发延迟，默认一个完整间隔
     */
    fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            Log.w(TAG, "AlarmManager 不可用，跳过看活调度")
            return
        }

        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(MIN_DELAY_MS)
        val pendingIntent = buildPendingIntent(context)

        val scheduled = runCatching {
            // 优先精确闹钟：Doze 下也能准时唤醒
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    BatteryOptimizationHelper.canScheduleExactAlarms(context)
                ) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    // 未获得精确闹钟授权：降级为不精确但可穿透 Doze 的闹钟
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            true
        }.getOrElse {
            Log.w(TAG, "排看活任务失败，降级为非精确闹钟：${it.message}")
            runCatching {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }.isSuccess
        }

        Log.i(TAG, "看活任务已排定：${delayMs / 1000}秒后触发，成功=$scheduled")
    }

    /** 取消看活任务（用户主动停止监控时调用） */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return
        runCatching { alarmManager.cancel(buildPendingIntent(context)) }
        Log.i(TAG, "看活任务已取消")
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, com.example.checkin.service.WatchdogReceiver::class.java).apply {
            action = com.example.checkin.service.WatchdogReceiver.ACTION_WATCHDOG
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 最小延迟，避免被划掉后立刻触发造成抖动 */
    private const val MIN_DELAY_MS = 5_000L
}
