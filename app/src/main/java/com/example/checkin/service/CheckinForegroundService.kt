// 职责：打卡监控前台服务，常驻通知显示监控点数，支持 ACTION_START/STOP/REFRESH，返回 START_STICKY
package com.example.checkin.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.checkin.R
import com.example.checkin.data.repository.ServiceLocator
import com.example.checkin.geofence.GeofenceManager
import com.example.checkin.notify.Notifier
import com.example.checkin.util.PreferencesStore
import com.example.checkin.util.ServiceWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 打卡监控前台服务。
 *
 * 职责（对齐需求）：
 * 1. **前台服务**：`onStartCommand` 立即 `startForeground`，类型
 *    `FOREGROUND_SERVICE_LOCATION`；常驻通知显示**当前监控的打卡点数量**；
 * 2. **START_STICKY**：被系统回收后由系统重建，`intent == null` 时也能恢复监控；
 * 3. **Action 协议**：
 *    - [ACTION_START]   开始监控
 *    - [ACTION_STOP]    停止监控
 *    - [ACTION_REFRESH] 增删改打卡点后重新注册围栏（不重启服务）
 * 4. 真正的围栏判定与动态调频由 [GeofenceManager] 承担。
 */
class CheckinForegroundService : Service() {

    private lateinit var geofenceManager: GeofenceManager
    private lateinit var preferencesStore: PreferencesStore

    /**
     * 服务自身的协程作用域。
     *
     * 专门用于持久化开关状态这类轻量 IO；随 onDestroy 一起取消，
     * 不使用 GlobalScope 以避免作用域泄漏。
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 是否已进入前台（避免重复 startForeground 抛异常） */
    private var isForeground = false

    /** 部分唤醒锁：保证锁屏后 CPU 不休眠，定位回调不中断 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建")

        preferencesStore = PreferencesStore.get(this)
        geofenceManager = GeofenceManager(
            context = applicationContext,
            pointRepository = ServiceLocator.checkinPointRepository(this),
            recordRepository = ServiceLocator.checkinRecordRepository(this)
        )

        // 定位异常时刷新常驻通知，让用户知道当前状态
        geofenceManager.onLocationError = { code, info ->
            Log.w(TAG, "定位异常 code=$code info=$info")
            updateNotification(
                getString(R.string.notification_service_locating_error, code)
            )
        }

        // 监控状态（点数/频率）变化时刷新通知
        geofenceManager.onMonitorStateChanged = {
            updateNotification(buildRunningText())
        }

        // 每次收到定位回调就续期唤醒锁：1 小时超时是兜底，长锁屏下必须靠心跳续期，
        // 否则锁到期后 CPU 休眠，定位回调停摆（见 acquirePartialWakeLock 的说明）。
        geofenceManager.onHeartbeat = {
            acquirePartialWakeLock()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent 为 null 说明服务是被系统重建的（START_STICKY），按「开始监控」处理
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand action=$action startId=$startId")

        // 持续定位 + 看活需要持有唤醒锁，避免 CPU 休眠导致回调停摆
        acquirePartialWakeLock()

        when (action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopMonitoring()
                return START_NOT_STICKY
            }

            ACTION_REFRESH -> {
                // 仅重新注册围栏，不重启服务、不打断已有定位
                if (isForeground) {
                    Log.i(TAG, "收到 ACTION_REFRESH，重新注册围栏")
                    updateNotification(getString(R.string.notification_service_refreshing))
                    geofenceManager.refresh {
                        updateNotification(buildRunningText())
                    }
                } else {
                    // 服务此前并未运行（例如用户刚开监控就改打卡点），
                    // 此时 refresh 没有意义，直接完整启动监控
                    Log.i(TAG, "服务未在运行，ACTION_REFRESH 转为启动监控")
                    if (!promoteToForeground()) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startMonitoring()
                }
                scheduleWatchdog()
                return START_STICKY
            }

            else -> {
                // ACTION_START 或系统重建
                if (!promoteToForeground()) {
                    Log.e(TAG, "进入前台失败（可能缺少定位权限），服务停止")
                    releaseWakeLock()
                    stopSelf()
                    return START_NOT_STICKY
                }
                startMonitoring()
                // 建立/续期周期性看活
                scheduleWatchdog()
                return START_STICKY
            }
        }
    }

    /**
     * 获取或续期部分唤醒锁。
     *
     * 屏幕熄灭后 CPU 可能进入休眠，高德定位回调会被推迟甚至停摆；
     * 持有 PARTIAL_WAKE_LOCK 能保证定位与围栏判定在锁屏下继续工作。
     *
     * 注意：唤醒锁带 1 小时超时作为兜底（防止异常路径漏释放），因此**会到期**；
     * 服务通过 [GeofenceManager.onHeartbeat] 在每次收到定位回调时调用本方法续期，
     * 保证长时间锁屏且没有新 onStartCommand 时定位不会中断。
     */
    private fun acquirePartialWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            // 复用同一个 WakeLock 对象（避免每次续期都新建），仅在未持有时 acquire
            val lock = wakeLock ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:checkin-location"
            ).also { wakeLock = it }

            if (!lock.isHeld) {
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                Log.i(TAG, "已获取/续期部分唤醒锁（超时 ${WAKE_LOCK_TIMEOUT_MS / 60_000} 分钟）")
            }
        }.onFailure { Log.w(TAG, "获取唤醒锁失败", it) }
    }

    /** 释放唤醒锁 */
    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "已释放唤醒锁")
            }
        }.onFailure { Log.w(TAG, "释放唤醒锁失败", it) }
        wakeLock = null
    }

    /**
     * 进入前台。
     *
     * Android 14 起 `foregroundServiceType="location"` 必须与已授予的定位权限匹配，
     * 否则抛 SecurityException；这里捕获后返回 false 由调用方安全退出。
     */
    private fun promoteToForeground(): Boolean {
        if (isForeground) return true

        val notification = Notifier.buildForegroundNotification(
            this,
            getString(R.string.notification_service_starting)
        )

        return try {
            ServiceCompat.startForeground(
                this,
                Notifier.NOTIFICATION_ID_FOREGROUND,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                }
            )
            isForeground = true
            running.set(true)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground 失败", t)
            false
        }
    }

    /** 开始监控并持久化开关状态 */
    private fun startMonitoring() {
        if (geofenceManager.isRunning) {
            Log.d(TAG, "已在监控中，仅刷新围栏")
            geofenceManager.refresh { updateNotification(buildRunningText()) }
            return
        }

        updateNotification(getString(R.string.notification_service_starting))

        geofenceManager.start(
            // 前台服务自己已有常驻通知，无需再让高德额外弹一条
            backgroundNotification = null
        )

        // 持久化「用户期望监控开启」，供 App 重启后自动恢复
        serviceScope.launch {
            preferencesStore.setMonitoringEnabled(true)
        }
    }

    /** 停止监控（用户主动关闭） */
    private fun stopMonitoring() {
        Log.i(TAG, "停止监控")
        geofenceManager.stop()
        // 用户主动关闭：取消看活任务，避免服务被反复拉起
        ServiceWatchdog.cancel(this)
        serviceScope.launch {
            preferencesStore.setMonitoringEnabled(false)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        running.set(false)
        stopSelf()
    }

    /**
     * 服务进入前台后建立周期性看活。
     *
     * 每次 onStartCommand 都重排一次，等价于把看活任务不断续期；
     * 若进程被杀，最后一次排定的任务仍会在到点时唤醒 [WatchdogReceiver] 拉起服务。
     */
    private fun scheduleWatchdog() {
        runCatching { ServiceWatchdog.schedule(this) }
            .onFailure { Log.w(TAG, "排定看活任务失败", it) }
    }

    /** 组装常驻通知文案：显示当前监控的打卡点数量与定位频率 */
    private fun buildRunningText(): String = getString(
        R.string.notification_service_running,
        geofenceManager.monitoredPointCount,
        geofenceManager.currentIntervalMs / 1000
    )

    private fun updateNotification(text: String) {
        if (isForeground) {
            Notifier.updateForegroundNotification(this, text)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务划掉应用时保持服务存活（清单中 stopWithTask=false）。
     *
     * 这里做两件额外的事（需求 1：onTaskRemoved 中重启服务）：
     * 1. 直接再发一次 start，把被划掉时可能降级的服务重新提到前台；
     * 2. 排一个较短的看活任务，防止本进程随后仍被 ROM 冻结时无法自恢复。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "任务被移除，尝试自恢复并排定短延迟看活")

        // 只有「当前确实处于监控中」才需要自恢复；用户已关闭监控时不应被强行拉起
        if (geofenceManager.isRunning) {
            runCatching {
                // 幂等：服务内部会判断是否已在运行
                CheckinForegroundService.start(this)
            }.onFailure { Log.w(TAG, "任务移除后重启服务失败", it) }

            // 划掉后台后 ROM 常会立刻冻结进程，这里用较短延迟尽快自检一次
            runCatching { ServiceWatchdog.schedule(this, delayMs = TASK_REMOVED_RETRY_DELAY_MS) }
                .onFailure { Log.w(TAG, "排定短延迟看活失败", it) }
        } else {
            Log.i(TAG, "当前未在监控中，任务移除后不做自恢复")
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "服务销毁")
        releaseWakeLock()
        serviceScope.cancel()
        geofenceManager.stop()
        isForeground = false
        running.set(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CheckinForegroundService"

        /** 唤醒锁超时：1 小时，作为忘记释放时的兜底 */
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

        /** 被划掉后台后的自检延迟：尽快确认服务是否还活着 */
        private const val TASK_REMOVED_RETRY_DELAY_MS = 10_000L

        /**
         * 服务是否正在运行（已进入前台即为运行中）。
         *
         * 供设置页「自检」使用：这是**真实状态**——
         * 服务 onDestroy 或用户停止监控后会立即变回 false。
         */
        fun isRunning(): Boolean = running.get()

        /** 进程内运行标记，由服务在进入/退出前台时同步 */
        private val running = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 开始监控 */
        const val ACTION_START = "com.example.checkin.action.START"

        /** 停止监控 */
        const val ACTION_STOP = "com.example.checkin.action.STOP"

        /** 打卡点增删改后重新注册围栏 */
        const val ACTION_REFRESH = "com.example.checkin.action.REFRESH"

        /**
         * 启动监控。
         *
         * Android 12+ 从后台调用 startForegroundService 会抛
         * ForegroundServiceStartNotAllowedException，因此内部捕获处理。
         */
        fun start(context: Context) = sendAction(context, ACTION_START)

        /** 停止监控 */
        fun stop(context: Context) = sendAction(context, ACTION_STOP)

        /**
         * 刷新围栏（打卡点增删改后调用）。
         *
         * 若服务未在运行，则视为启动监控，保证「先改打卡点、后开启监控」也能正常工作。
         */
        fun refresh(context: Context) = sendAction(context, ACTION_REFRESH)

        private fun sendAction(context: Context, action: String) {
            val intent = Intent(context, CheckinForegroundService::class.java).apply {
                this.action = action
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure {
                Log.e(TAG, "发送 $action 失败", it)
            }
        }
    }
}
