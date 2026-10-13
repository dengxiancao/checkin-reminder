// 职责：Compose 侧权限申请逻辑，统一处理定位/通知权限申请、拒绝原因与从设置页返回后的刷新
package com.example.checkin.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.checkin.util.AppPermissionState
import com.example.checkin.util.PermissionDenialReason
import com.example.checkin.util.PermissionUtils

/** 从 Context 中找出宿主 Activity（Compose 里拿到的通常是 ContextWrapper） */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 定位与通知权限的统一处理入口。
 *
 * 用法：
 * ```
 * val permission = rememberLocationPermission()
 * permission.requestLocation()   // 申请定位
 * permission.requestNotification() // 申请通知（Android 13+）
 * permission.state.hasLocation   // 是否已获得定位权限
 * permission.locationDenial      // 拒绝原因，用于给出不同引导文案
 * ```
 *
 * 关键行为：
 * - 从系统设置页返回时（ON_RESUME）自动重新读取权限状态；
 * - 区分「普通拒绝」与「永久拒绝（不再询问）」，便于给出不同引导文案。
 */
class LocationPermissionState internal constructor(
    /** 当前权限状态 */
    val state: AppPermissionState,
    /** 定位权限的拒绝原因 */
    val locationDenial: PermissionDenialReason,
    /** 通知权限的拒绝原因 */
    val notificationDenial: PermissionDenialReason,
    /** 请求定位权限（精确定位 + 粗略定位） */
    val requestLocation: () -> Unit,
    /** 请求通知权限（Android 13+；低版本引导到通知设置页） */
    val requestNotification: () -> Unit,
    /** 一次性请求定位 + 通知 */
    val requestAll: () -> Unit,
    /** 跳转应用设置页 */
    val openAppSettings: () -> Unit,
    /** 跳转通知设置页 */
    val openNotificationSettings: () -> Unit
) {
    /** 是否需要展示引导说明（有任一权限缺失） */
    val needsGuidance: Boolean get() = !state.allGranted

    /**
     * 是否具备「开始监控」的最低权限要求。
     *
     * 只要求**前台定位 + 通知**。后台定位（「始终允许」）只能由用户到系统设置里手动授予，
     * 若把它作为硬性门槛，用户在只授予前台定位时会**完全无法开启监控**，体验很差。
     * 后台定位缺失时监控仍可运行，界面通过权限引导卡继续提示补授。
     */
    val canMonitor: Boolean get() = state.hasLocation && state.notification

    /**
     * 仅在尚未获得定位权限时发起申请。
     *
     * 用于「用户刚保存完打卡点」等场景：顺手补一次授权，已有权限则不打扰用户。
     */
    val requestLocationIfNeeded: () -> Unit
        get() = { if (!state.hasLocation) requestLocation() }

    /** 是否属于用户明确拒绝（需要引导去设置页手动开启） */
    val blockedPermanently: Boolean
        get() = locationDenial == PermissionDenialReason.DENIED_PERMANENTLY ||
            notificationDenial == PermissionDenialReason.DENIED_PERMANENTLY
}

@Composable
fun rememberLocationPermission(): LocationPermissionState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // 权限状态与拒绝原因
    var state by remember { mutableStateOf(PermissionUtils.currentState(context)) }
    var locationDenial by remember { mutableStateOf(PermissionDenialReason.NONE) }
    var notificationDenial by remember { mutableStateOf(PermissionDenialReason.NONE) }

    /** 重新读取权限状态（设置页返回、授权回调后调用） */
    fun refresh() {
        state = PermissionUtils.currentState(context)
    }

    /** 刷新状态 + 同步更新两个拒绝原因（避免分散调用 refresh 造成引用不一致） */
    fun refreshWith(update: () -> Unit) {
        update()
        refresh()
    }

    /** 申请定位权限 */
    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        refreshWith {
            locationDenial = PermissionUtils.denialReason(
                activity = activity,
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                granted = granted
            )
        }
    }

    /** 申请通知权限 */
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshWith {
            notificationDenial = PermissionUtils.denialReason(
                activity = activity,
                permission = Manifest.permission.POST_NOTIFICATIONS,
                granted = granted
            )
        }
    }

    // 从系统设置页返回时自动刷新（用户可能刚手动开启了权限）
    // 注意：Activity 基类没有 lifecycle 属性，必须用 ComponentActivity 才能监听生命周期
    val componentActivity = activity as? ComponentActivity
    DisposableEffect(componentActivity) {
        val owner = componentActivity ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // 注意：这里刻意不使用 remember 包裹返回值——每次重组都重建，保证内部 lambda
    // 始终捕获最新的 launcher 与状态，避免闭包持有过期引用。
    return LocationPermissionState(
        state = state,
        locationDenial = locationDenial,
        notificationDenial = notificationDenial,
        requestLocation = {
            locationLauncher.launch(PermissionUtils.LOCATION_PERMISSIONS)
        },
        requestNotification = {
            if (PermissionUtils.needsNotificationPermission) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Android 13 以下没有运行时开关，只能引导到系统通知设置
                PermissionUtils.openNotificationSettings(context)
            }
        },
        requestAll = {
            locationLauncher.launch(PermissionUtils.LOCATION_PERMISSIONS)
            if (PermissionUtils.needsNotificationPermission) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        openAppSettings = { PermissionUtils.openAppSettings(context) },
        openNotificationSettings = { PermissionUtils.openNotificationSettings(context) }
    )
}
