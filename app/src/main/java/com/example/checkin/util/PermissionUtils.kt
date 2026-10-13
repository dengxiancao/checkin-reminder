// 职责：定位/通知权限工具，提供权限状态判断、设置页跳转与拒绝原因区分
package com.example.checkin.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 权限拒绝的原因。
 *
 * 用于给出不同的引导文案：普通拒绝可以再次弹窗，永久拒绝只能引导去系统设置。
 */
enum class PermissionDenialReason {
    /** 未被拒绝 */
    NONE,

    /** 本次拒绝，但还可以再次弹窗申请 */
    DENIED,

    /** 勾选了“不再询问”或多次拒绝，系统不会再弹窗，必须去设置页手动开启 */
    DENIED_PERMANENTLY
}

/** 应用所需的权限状态快照 */
data class AppPermissionState(
    /** 精确定位权限（ACCESS_FINE_LOCATION） */
    val fineLocation: Boolean = false,
    /** 粗略定位权限（ACCESS_COARSE_LOCATION） */
    val coarseLocation: Boolean = false,
    /** 通知权限（仅 Android 13+ 需要运行时申请，低版本看系统通知总开关） */
    val notification: Boolean = true,
    /**
     * 后台定位权限（ACCESS_BACKGROUND_LOCATION，Android 10+）。
     *
     * 该权限必须由用户到系统设置里手动选“始终允许”，无法通过运行时弹窗申请。
     */
    val backgroundLocation: Boolean = false
) {
    /** 是否具备定位能力（细/粗任一即可） */
    val hasLocation: Boolean get() = fineLocation || coarseLocation

    /** 是否具备前后台定位 + 通知的完整能力（后台提醒的最低要求） */
    val allGranted: Boolean get() = hasLocation && notification && backgroundLocation

    /** 缺失的权限项描述，用于引导文案 */
    val missingNames: List<String>
        get() = buildList {
            if (!fineLocation) add("精确定位")
            if (!notification) add("通知")
            if (!backgroundLocation) add("后台定位（始终允许）")
        }
}

/** 权限相关工具方法（非 Composable 部分） */
object PermissionUtils {

    /** 运行时需要申请的定位权限 */
    val LOCATION_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /** 是否需要运行时申请通知权限（Android 13 / API 33 起） */
    val needsNotificationPermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 读取当前权限状态 */
    fun currentState(context: Context): AppPermissionState {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val notification = if (needsNotificationPermission) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 13 以下通知默认开启；但用户仍可能在系统设置里关闭通知总开关
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        // 后台定位（Android 10+）：只能由用户在系统设置中选择“始终允许”
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Android 10 以下没有独立的后台定位权限，随前台权限一起授予
            fine || coarse
        }

        return AppPermissionState(
            fineLocation = fine,
            coarseLocation = coarse,
            notification = notification,
            backgroundLocation = background
        )
    }

    /**
     * 判断权限的拒绝原因。
     *
     * @param activity 用于查询 shouldShowRequestPermissionRationale
     * @param permission 目标权限
     */
    fun denialReason(
        activity: Activity?,
        permission: String,
        granted: Boolean
    ): PermissionDenialReason {
        if (granted) return PermissionDenialReason.NONE
        if (activity == null) return PermissionDenialReason.DENIED
        // 已拒绝且系统不再允许展示申请弹窗 => 永久拒绝
        val canAskAgain = activity.shouldShowRequestPermissionRationale(permission)
        return if (canAskAgain) {
            PermissionDenialReason.DENIED
        } else {
            PermissionDenialReason.DENIED_PERMANENTLY
        }
    }

    /** 跳转到本应用的系统设置详情页（用于永久拒绝后的引导） */
    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    /** 跳转到本应用的通知设置页 */
    fun openNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
