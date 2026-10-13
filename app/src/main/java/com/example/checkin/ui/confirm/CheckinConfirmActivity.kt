// 职责：打卡确认页宿主 Activity，接收通知携带的打卡点信息并承载 Compose 页面
package com.example.checkin.ui.confirm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.checkin.ui.permission.rememberLocationPermission
import com.example.checkin.ui.theme.CheckinTheme

/**
 * 打卡确认页。
 *
 * 由进入/离开打卡范围的通知点击进入（见 [Notifier]），
 * 展示打卡点信息与当前距离，并支持一键完成打卡落库。
 */
class CheckinConfirmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 允许锁屏上直接显示本页，并在通知点亮屏幕时唤醒。
        // 这样用户在锁屏收到打卡提醒后点击即可直接打卡，无需先解锁。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val pointId = intent?.getLongExtra(EXTRA_POINT_ID, 0L) ?: 0L
        val isEnter = intent?.getBooleanExtra(EXTRA_IS_ENTER, true) ?: true
        // 通知里已带上触发时的距离，先展示出来，随后会被实时定位结果刷新
        val initialDistance = intent
            ?.takeIf { it.hasExtra(EXTRA_DISTANCE) }
            ?.getDoubleExtra(EXTRA_DISTANCE, 0.0)

        setContent {
            CheckinTheme {
                // 用生命周期感知的权限状态：用户从系统设置开启通知后返回，会自动刷新
                val permission = rememberLocationPermission()

                CheckinConfirmScreen(
                    pointId = pointId,
                    isEnter = isEnter,
                    initialDistance = initialDistance,
                    notificationAllowed = permission.state.notification,
                    onOpenNotificationSettings = { permission.openNotificationSettings() },
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        /** 打卡点 id */
        const val EXTRA_POINT_ID = "extra_confirm_point_id"

        /** 是否由「进入」触发（false 表示离开） */
        const val EXTRA_IS_ENTER = "extra_confirm_is_enter"

        /** 触发时与打卡点的距离（可选） */
        const val EXTRA_DISTANCE = "extra_confirm_distance"

        /** 构造跳转意图（供非通知场景复用，例如从列表直接打卡） */
        fun newIntent(
            context: Context,
            pointId: Long,
            isEnter: Boolean = true,
            distanceMeters: Double? = null
        ): Intent = Intent(context, CheckinConfirmActivity::class.java).apply {
            putExtra(EXTRA_POINT_ID, pointId)
            putExtra(EXTRA_IS_ENTER, isEnter)
            distanceMeters?.let { putExtra(EXTRA_DISTANCE, it) }
        }
    }
}
