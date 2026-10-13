// 职责：通知统一出口，提供打卡提醒三类通知 + 前台服务常驻通知 + 通知/全屏提醒权限判定
package com.example.checkin.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.checkin.R
import com.example.checkin.ui.MainActivity
import com.example.checkin.ui.confirm.CheckinConfirmActivity
import com.example.checkin.util.PreferencesStore

/**
 * 通知统一出口（Notifier）。
 *
 * 本类由原 `Notifier` 与 `NotificationHelper` 合并而来，统一管理：
 * - 打卡提醒渠道（**IMPORTANCE_HIGH**）与前台服务渠道（IMPORTANCE_LOW）；
 * - 通知 id 基数与常驻通知 id；
 * - 通知/全屏提醒权限判定与授权引导。
 *
 * 合并原因：此前两个类各自维护渠道 id、通知 id 与权限判定，渠道 id 甚至不一致
 * （`NOTIFICATION_CHANNEL_CHECKIN` vs `channel_check_in_v1`），是「改了一处忘了另一处」
 * 类缺陷的温床。现在只保留**一个**通知出口。
 *
 * 三类打卡通知：
 * - [notifyEnter]：进入打卡范围，文案「XX 已进入打卡范围（距离 X 米），请打卡」
 * - [notifyLeave]：离开打卡范围，文案「XX 已离开打卡范围，别忘打卡」
 * - [notifyCheckinSuccess]：打卡成功确认
 *
 * 共同要求：
 * - 走 [CHANNEL_CHECKIN] 渠道（**IMPORTANCE_HIGH**），否则锁屏不会横幅/响铃；
 * - 震动 + 默认提示音；
 * - `setVisibility(PUBLIC)` 锁屏可见；
 * - 点击进入**打卡确认页** [CheckinConfirmActivity]，以便一步完成打卡。
 */
object Notifier {

    private const val TAG = "Notifier"

    /**
     * 打卡提醒渠道 id。
     *
     * 渠道重要性创建后**不可修改**，因此改动重要性时必须换新 id（这里带版本后缀）。
     */
    const val CHANNEL_CHECKIN = "NOTIFICATION_CHANNEL_CHECKIN"

    /** 前台定位服务常驻通知渠道（低优先级，静默） */
    const val CHANNEL_SERVICE = "channel_service_v1"

    /** 前台服务常驻通知 id */
    const val NOTIFICATION_ID_FOREGROUND = 1000

    /** 进入/离开提醒的通知 id 基数 */
    private const val ID_ENTER_BASE = 3000
    private const val ID_LEAVE_BASE = 4000

    /** 打卡成功通知 id */
    private const val ID_CHECKIN_SUCCESS = 5000

    // ------------------------------------------------------------------ 渠道

    /**
     * 创建全部通知渠道。
     *
     * 必须在 Application.onCreate 中调用，且要在任何通知发出之前。
     * - 打卡提醒渠道（IMPORTANCE_HIGH）：锁屏横幅 + 声音 + 震动；
     * - 前台服务渠道（IMPORTANCE_LOW）：静默常驻。
     */
    fun createChannels(context: Context) {
        createCheckinChannel(context)
        createServiceChannel(context)
    }

    /** 创建打卡提醒渠道（IMPORTANCE_HIGH） */
    private fun createCheckinChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            CHANNEL_CHECKIN,
            context.getString(R.string.channel_check_in_name),
            // 高优先级：锁屏横幅 + 声音 + 震动
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_check_in_desc)
            enableLights(true)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            // 锁屏上显示完整内容
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        manager.createNotificationChannel(channel)
    }

    /** 创建前台服务常驻通知渠道（IMPORTANCE_LOW，静默） */
    private fun createServiceChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_service_desc)
            setShowBadge(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------ 进入

    /**
     * 发送「进入打卡范围」提醒。
     *
     * @param pointId 打卡点 id
     * @param pointName 打卡点名称
     * @param distanceMeters 触发时与打卡点的距离（米）
     */
    fun notifyEnter(
        context: Context,
        pointId: Long,
        pointName: String,
        distanceMeters: Double
    ) {
        val title = context.getString(R.string.notify_enter_title, pointName)
        val text = context.getString(R.string.notify_enter_text_new, distanceMeters.toInt())
        post(
            context = context,
            notificationId = ID_ENTER_BASE + pointId.toInt(),
            pointId = pointId,
            isEnter = true,
            distanceMeters = distanceMeters,
            title = title,
            text = text
        )
    }

    // ------------------------------------------------------------------ 离开

    /**
     * 发送「离开打卡范围」提醒。
     *
     * @param distanceMeters 触发时的距离，用于文案补充（可为 null）
     */
    fun notifyLeave(
        context: Context,
        pointId: Long,
        pointName: String,
        distanceMeters: Double? = null
    ) {
        val title = context.getString(R.string.notify_leave_title, pointName)
        val text = context.getString(R.string.notify_leave_text_new)
        post(
            context = context,
            notificationId = ID_LEAVE_BASE + pointId.toInt(),
            pointId = pointId,
            isEnter = false,
            distanceMeters = distanceMeters,
            title = title,
            text = text
        )
    }

    // ------------------------------------------------------------------ 打卡成功

    /** 发送打卡成功确认通知 */
    fun notifyCheckinSuccess(context: Context, pointName: String) {
        if (!hasNotificationPermission(context)) {
            Log.w(TAG, "未授予 POST_NOTIFICATIONS 权限，跳过打卡成功通知")
            return
        }

        val title = context.getString(R.string.notify_checkin_success_title)
        val text = context.getString(R.string.notify_checkin_success_text, pointName)

        val notification = NotificationCompat.Builder(context, CHANNEL_CHECKIN)
            .setSmallIcon(R.drawable.ic_notification_checkin)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(ID_CHECKIN_SUCCESS, notification)
        }.onFailure { Log.e(TAG, "发送打卡成功通知失败", it) }
    }

    /** 取消某个打卡点的进入/离开提醒 */
    fun cancel(context: Context, pointId: Long) {
        runCatching {
            NotificationManagerCompat.from(context).apply {
                cancel(ID_ENTER_BASE + pointId.toInt())
                cancel(ID_LEAVE_BASE + pointId.toInt())
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 构建并发送进入/离开提醒 */
    private fun post(
        context: Context,
        notificationId: Int,
        pointId: Long,
        isEnter: Boolean,
        distanceMeters: Double?,
        title: String,
        text: String
    ) {
        if (!hasNotificationPermission(context)) {
            // Android 13+ 未授权时必须静默跳过，否则 notify 会抛 SecurityException
            Log.w(TAG, "未授予 POST_NOTIFICATIONS 权限，跳过${if (isEnter) "进入" else "离开"}提醒")
            return
        }

        // 读取用户在设置页选择的提醒方式（进程级缓存，同步读取）
        val prefs = PreferencesStore.get(context).notifyPreferences

        // 点击通知 → 打卡确认页
        val contentIntent = buildConfirmPendingIntent(
            context = context,
            pointId = pointId,
            isEnter = isEnter,
            distanceMeters = distanceMeters,
            requestCode = notificationId
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_CHECKIN)
            .setSmallIcon(R.drawable.ic_notification_checkin)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            // 高优先级 + 提醒类别：锁屏横幅
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // 锁屏可见
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        // ---------------- 按用户偏好决定声音 / 震动 ----------------
        if (prefs.sound && prefs.vibrate) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        } else if (prefs.sound) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        } else if (prefs.vibrate) {
            // 只震动：显式给震动模式，并清掉声音
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            builder.setVibrate(VIBRATION_PATTERN)
        } else {
            // 都不开：静默通知，但仍会出现在通知栏与锁屏
            builder.setSilent(true)
        }

        // ---------------- 全屏提醒（锁屏直接弹出打卡页） ----------------
        if (prefs.fullScreen) {
            builder.setFullScreenIntent(contentIntent, true)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        }.onFailure { Log.e(TAG, "发送${if (isEnter) "进入" else "离开"}提醒失败", it) }
    }

    /** 构建跳转打卡确认页的 PendingIntent */
    private fun buildConfirmPendingIntent(
        context: Context,
        pointId: Long,
        isEnter: Boolean,
        distanceMeters: Double?,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, CheckinConfirmActivity::class.java).apply {
            // 从通知进入，需带上打卡点与事件信息
            putExtra(CheckinConfirmActivity.EXTRA_POINT_ID, pointId)
            putExtra(CheckinConfirmActivity.EXTRA_IS_ENTER, isEnter)
            distanceMeters?.let {
                putExtra(CheckinConfirmActivity.EXTRA_DISTANCE, it)
            }
            // 避免重复点击通知时叠加多个界面实例
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ------------------------------------------------------------------ 前台服务常驻通知

    /**
     * 构建前台服务常驻通知。
     *
     * Android 14 (API 34) 起前台服务必须声明类型；本通知用于展示当前监听状态。
     */
    fun buildForegroundNotification(context: Context, contentText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification_checkin)
            .setContentTitle(context.getString(R.string.notification_service_title))
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }

    /** 更新前台服务常驻通知文案 */
    fun updateForegroundNotification(context: Context, contentText: String) {
        if (!hasNotificationPermission(context)) return
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID_FOREGROUND, buildForegroundNotification(context, contentText))
        }.onFailure { Log.e(TAG, "更新前台通知失败", it) }
    }

    // ------------------------------------------------------------------ 权限

    /** 是否已获得通知权限（API 33 以下默认有权限） */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 当前是否可以使用「全屏提醒」（锁屏直接弹出打卡确认页）。
     *
     * 背景：Android 14 (API 34) 起 `USE_FULL_SCREEN_INTENT` 仅对闹钟/通话类应用默认授予，
     * 其余应用默认**被撤销**；未持有时系统会把 `setFullScreenIntent` **静默降级**为
     * 普通横幅通知（用户与开发都无从感知）。
     *
     * 实现说明：本工程 `compileSdk = 33`（本机仅安装 android-33 平台），
     * 因此这里用**反射**调用 API 34 新增的 `NotificationManager#canUseFullScreenIntent()`，
     * 避免编译期绑定到更高的 compileSdk。
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        // API 34 以下不存在该限制，恒可用
        if (Build.VERSION.SDK_INT < API_34) return true
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            val method = NotificationManager::class.java.getMethod("canUseFullScreenIntent")
            method.invoke(manager) as? Boolean ?: false
        }.getOrElse {
            // 反射失败时保守返回 false，让自检提示用户手动确认，而不是谎报「可用」
            Log.w(TAG, "检测 canUseFullScreenIntent 失败：${it.message}")
            false
        }
    }

    /**
     * 构造「全屏提醒」授权设置页的 Intent（仅 API 34+ 有意义）。
     *
     * 返回 null 表示当前系统不需要（或无法）跳转。
     * 调用方需对 `startActivity` 做 `runCatching` 兜底（部分 ROM 可能裁剪该设置页）。
     */
    fun fullScreenIntentSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < API_34) return null
        // Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT 是 API 34 常量，
        // 这里使用字面量，避免在 compileSdk 33 下编译失败。
        return Intent(ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Android 14 的 API level（compileSdk 33 下没有 Build.VERSION_CODES.UPSIDE_DOWN_CAKE 常量） */
    private const val API_34 = 34

    /** `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` 的字面量值 */
    private const val ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT =
        "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"

    /** 震动模式：等待 0ms，震动 400ms，间隔 200ms，再震动 400ms */
    private val VIBRATION_PATTERN = longArrayOf(0L, 400L, 200L, 400L)
}
