// 职责：电池优化与后台权限跳转工具，检测免优化状态并尽力打开厂商设置页
package com.example.checkin.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 电池优化（Doze）与后台运行权限工具。
 *
 * 说明：国产 ROM 的「自启动」「后台运行」都是厂商私有开关，**没有公开 API 可以查询或修改**。
 * 因此本类的策略是：
 * 1. 能查的（电池优化白名单、通知、定位权限）给出真实状态；
 * 2. 查不到的（自启动、锁任务卡片）只能尽力跳转 + 给出图文步骤让用户手动开；
 * 3. 跳转失败时逐级降级到系统通用设置页，保证一定有可达路径。
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimization"

    /**
     * 本应用是否已被排除在电池优化之外（即「不优化」）。
     *
     * 这是**可查询的真实状态**，也是自检里最关键的一项：
     * 未加入白名单时，Doze 会让前台服务在锁屏后很快被限制。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return runCatching {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    /**
     * 拉起「请求忽略电池优化」系统弹窗。
     *
     * 需要清单声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限。
     * 部分 ROM 会忽略该 Action，此时降级到「电池优化列表」或系统设置首页。
     *
     * @return true 表示成功发起了某个设置页跳转
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        // 1) 优先：直接弹出「是否允许后台高耗电/不优化」对话框
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, direct)) return true

        // 2) 降级：打开电池优化白名单列表，让用户手动选「未优化」
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, list)) return true

        // 3) 兜底：系统设置首页
        return openAppDetails(context)
    }

    /** 打开本应用的「应用信息」详情页 */
    fun openAppDetails(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startSafely(context, intent)
    }

    /** 打开本应用的通知设置页 */
    fun openNotificationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, intent)) return true
        return openAppDetails(context)
    }

    /** 打开系统「省电模式 / 电池」设置页 */
    fun openBatterySaverSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (startSafely(context, intent)) return true
        return openAppDetails(context)
    }

    /**
     * 尝试打开厂商的「自启动管理」页。
     *
     * ⚠️ 这些都是**厂商私有 Activity**，名称未公开且随 ROM 版本变化，
     * 因此逐个尝试，全部失败时降级到应用详情页；
     * 界面上必须同时给出可照做的图文步骤，不能只依赖跳转。
     *
     * @return true 表示成功跳转到某个厂商页面
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = autostartCandidates(RomUtils.vendor)
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (startSafely(context, intent)) {
                Log.i(TAG, "已跳转自启动设置：$pkg/$cls")
                return true
            }
        }
        Log.w(TAG, "未找到可用的自启动设置页，降级到应用详情页")
        return openAppDetails(context)
    }

    /** 各厂商自启动管理页的候选组件 */
    private fun autostartCandidates(vendor: RomVendor): List<Pair<String, String>> = when (vendor) {
        RomVendor.MIUI -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )

        RomVendor.EMUI -> listOf(
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
        )

        RomVendor.HONOR -> listOf(
            "com.hihonor.systemmanager" to
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        )

        RomVendor.COLOR_OS -> listOf(
            "com.coloros.safecenter" to
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity"
        )

        RomVendor.ORIGIN_OS -> listOf(
            "com.vivo.permissionmanager" to
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        )

        RomVendor.FLYME -> listOf(
            "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
            "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC"
        )

        RomVendor.SAMSUNG -> listOf(
            "com.samsung.android.lool" to
                "com.samsung.android.sm.ui.battery.BatteryActivity"
        )

        RomVendor.OTHER -> emptyList()
    }

    /** 安全启动 Activity：失败只记日志，不抛异常 */
    private fun startSafely(context: Context, intent: Intent): Boolean = runCatching {
        ContextCompat.startActivity(context, intent, null)
        true
    }.getOrElse {
        Log.d(TAG, "跳转失败：${intent.action ?: intent.component} -> ${it.message}")
        false
    }

    /** 当前是否处于系统省电模式（省电模式下后台限制会更激进） */
    fun isPowerSaveMode(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return runCatching { powerManager.isPowerSaveMode }.getOrDefault(false)
    }

    /** 该设备是否具备精确闹钟能力（Android 12+ 需要 SCHEDULE_EXACT_ALARM 授权） */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE)
            as? android.app.AlarmManager ?: return false
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    }
}
