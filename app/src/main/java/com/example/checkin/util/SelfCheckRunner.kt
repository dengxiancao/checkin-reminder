// 职责：保活自检，逐项检查前台服务、权限、电池优化与打卡点状态并给出结论
package com.example.checkin.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.checkin.R
import com.example.checkin.data.repository.CheckinPointRepository
import com.example.checkin.notify.Notifier
import com.example.checkin.service.CheckinForegroundService

/** 自检项结果 */
data class CheckItem(
    /** 标题资源 id */
    val titleRes: Int,
    /** 是否通过 */
    val passed: Boolean,
    /**
     * 未通过时的提示资源 id。
     * 为 null 表示通过（或该项无需提示）。
     */
    val hintRes: Int?,
    /**
     * 该项是否**无法自动检测**（仅能引导用户手动确认）。
     *
     * 例如厂商 ROM 的「自启动」开关没有任何公开 API，只能标为 go 手动确认，
     * 不能谎报「通过」。
     */
    val manualOnly: Boolean = false
)

/** 自检汇总结果 */
data class SelfCheckResult(
    val items: List<CheckItem>,
    /** 是否已运行过（用于区分初始态与「全部通过」） */
    val completed: Boolean = true
) {
    val passedCount: Int get() = items.count { it.passed }
    val failedCount: Int get() = items.count { !it.passed && !it.manualOnly }

    /** 关键项是否全部通过（不把 manualOnly 计入失败） */
    val allCriticalPassed: Boolean get() = failedCount == 0
}

/**
 * 保活自检执行器。
 *
 * 检查项与「哪些能真正查到」的边界：
 * | 项目 | 能否自动检测 |
 * | --- | --- |
 * | 前台服务是否在运行 | ✅ 进程内共享状态 |
 * | 定位权限（含后台） | ✅ checkSelfPermission |
 * | 通知权限 | ✅ checkSelfPermission / areNotificationsEnabled |
 * | 全屏提醒权限（Android 14+） | ✅ 反射调用 NotificationManager.canUseFullScreenIntent |
 * | 电池优化是否已关闭 | ✅ PowerManager.isIgnoringBatteryOptimizations |
 * | 精确定时权限 | ✅ AlarmManager.canScheduleExactAlarms |
 * | 是否有启用中的打卡点 | ✅ Room 查询 |
 * | 厂商「自启动」开关 | ❌ 无公开 API，只能引导手动确认 |
 */
object SelfCheckRunner {

    /**
     * 执行自检。
     *
     * @param pointRepository 用于检查是否存在启用中的打卡点
     */
    suspend fun run(
        context: Context,
        pointRepository: CheckinPointRepository
    ): SelfCheckResult {
        val items = mutableListOf<CheckItem>()

        // 1) 前台服务是否在运行
        val serviceRunning = CheckinForegroundService.isRunning()
        items += CheckItem(
            titleRes = R.string.check_service,
            passed = serviceRunning,
            hintRes = if (serviceRunning) null else R.string.check_service_hint
        )

        // 2) 定位权限（前台 + 后台）
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 10 以下没有独立的后台定位权限，随前台权限一起授予
            fine || coarse
        }
        val locationOk = (fine || coarse) && background
        items += CheckItem(
            titleRes = R.string.check_location,
            passed = locationOk,
            hintRes = if (locationOk) null else R.string.check_location_hint
        )

        // 3) 通知权限
        val notificationOk = Notifier.hasNotificationPermission(context)
        items += CheckItem(
            titleRes = R.string.check_notification,
            passed = notificationOk,
            hintRes = if (notificationOk) null else R.string.check_notification_hint
        )

        // 4) 全屏提醒权限（Android 14+：非闹钟/通话类应用默认被撤销，需用户手动授予）
        //    未持有时 setFullScreenIntent 会被系统静默降级为普通横幅通知。
        val fullScreenOk = Notifier.canUseFullScreenIntent(context)
        items += CheckItem(
            titleRes = R.string.check_fullscreen,
            passed = fullScreenOk,
            hintRes = if (fullScreenOk) null else R.string.check_fullscreen_hint,
            // 只能在系统设置里授权，属于「需用户手动处理」而非自动化失败
            manualOnly = true
        )

        // 5) 电池优化
        val batteryOk = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        items += CheckItem(
            titleRes = R.string.check_battery,
            passed = batteryOk,
            hintRes = if (batteryOk) null else R.string.check_battery_hint
        )

        // 6) 精确定时权限（可选，未通过不影响提醒，只影响自恢复速度）
        val alarmOk = BatteryOptimizationHelper.canScheduleExactAlarms(context)
        items += CheckItem(
            titleRes = R.string.check_alarm,
            // 该项不通过不算「关键失败」，故用 manualOnly 语义之外单独处理：
            // 这里把 passed 设为真实值，但通过 hint 说明影响有限
            passed = alarmOk,
            hintRes = if (alarmOk) null else R.string.check_alarm_hint,
            manualOnly = true
        )

        // 7) 是否存在启用中的打卡点
        val enabledPoints = runCatching { pointRepository.getEnabledPoints() }
            .getOrDefault(emptyList())
        val pointsOk = enabledPoints.isNotEmpty()
        items += CheckItem(
            titleRes = R.string.check_points,
            passed = pointsOk,
            hintRes = if (pointsOk) null else R.string.check_points_hint
        )

        // 8) 厂商自启动开关：无法检测，只能引导
        items += CheckItem(
            titleRes = R.string.check_rom,
            passed = false,
            hintRes = R.string.check_rom_hint,
            manualOnly = true
        )

        return SelfCheckResult(items = items, completed = true)
    }
}
