// 职责：围栏总控，串联高德定位、高德围栏注册、状态机判定、动态调频、打卡记录与通知
package com.example.checkin.geofence

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.data.repository.CheckinPointRepository
import com.example.checkin.data.repository.CheckinRecordRepository
import com.example.checkin.notify.NotificationDeduper
import com.example.checkin.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 进入/离开范围触发的总控。
 *
 * 单次定位回调的处理链路：
 * ```
 * AMapLocationEngine
 *      ↓ 位置快照
 * FenceStateMachine      状态机判定（OUTSIDE/NEAR/INSIDE/LEFT）→ 产出 ENTER/EXIT 事件
 *      ↓                    ↓
 * LocationIntervalScheduler   Room：写打卡记录 + Notifier：发通知
 *      ↓
 * AMapLocationEngine.applyInterval（动态调频）
 * ```
 *
 * 高德 `GeoFenceClient` 的围栏登记也在这里完成（参见 [AMapGeoFenceRegistrar] 的说明：
 * 它的回调只做日志核对，权威判定以状态机为准，避免高德围栏静默不回调导致漏报）。
 */
class GeofenceManager(
    context: Context,
    private val pointRepository: CheckinPointRepository,
    private val recordRepository: CheckinRecordRepository
) {

    private val appContext = context.applicationContext

    /** 通知封装：进入/离开/打卡成功 */
    private val notifier = Notifier

    /** 主线程 Handler：用于把「重建定位客户端」这类动作移出定位回调栈 */
    private val mainHandler = Handler(Looper.getMainLooper())

    private val engine = AMapLocationEngine(appContext)

    /** 使用进程级共享状态机，使 UI 能订阅到服务算出的状态 */
    private val stateMachine = FenceMonitor.stateMachine

    private val scheduler = LocationIntervalScheduler()
    private val amapFenceRegistrar = AMapGeoFenceRegistrar(appContext)

    /**
     * 工作协程作用域。
     *
     * ⚠️ 必须在每次 [start] 时**新建**：协程作用域一旦 `cancel()` 就永久失效，
     * 若在字段初始化时创建、在 [stop] 里取消，则「停止监控 → 再次开启监控」后
     * 所有 `launch` 都会静默不执行（围栏注册、记录写入全部失效）。
     */
    private var scope: CoroutineScope = newScope()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前启用中的打卡点缓存，避免每次定位都查库 */
    @Volatile
    private var activePoints: List<CheckinPoint> = emptyList()

    /** 监控是否处于运行状态 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** 各打卡点的围栏状态，供 UI 订阅 */
    val fenceStates: StateFlow<Map<Long, FenceRuntimeState>> = stateMachine.states

    /** 当前定位频率档位（毫秒），供通知/日志展示 */
    val currentIntervalMs: Long get() = scheduler.currentTier.intervalMs

    /** 当前监控的打卡点数量 */
    val monitoredPointCount: Int get() = activePoints.size

    /** 定位异常时上报给服务 */
    var onLocationError: ((Int, String) -> Unit)? = null

    /** 状态或数量变化时上报（服务据此刷新常驻通知） */
    var onMonitorStateChanged: (() -> Unit)? = null

    /**
     * 收到一次有效定位时的心跳回调。
     *
     * 服务据此**续期唤醒锁**：PARTIAL_WAKE_LOCK 带 1 小时超时作为兜底，
     * 但若这 1 小时内没有新的 onStartCommand（看活闹钟在 Doze 深度休眠或被 ROM
     * 拦截时可能被抑制），锁到期后无人续期，CPU 仍会休眠、定位回调停摆——
     * 恰恰是该锁要解决的问题本身。用「定位回调」作为心跳续期，语义最直接。
     */
    var onHeartbeat: (() -> Unit)? = null

    /**
     * 刷新围栏注册。
     *
     * 打卡点增删改后调用（服务收到 ACTION_REFRESH），会重新载入打卡点、
     * 重新向高德注册围栏，并清理已删除打卡点的状态。
     */
    fun refresh(onDone: (() -> Unit)? = null) {
        scope.launch {
            activePoints = runCatching { pointRepository.getEnabledPoints() }
                .onFailure { Log.e(TAG, "读取打卡点失败", it) }
                .getOrDefault(emptyList())

            Log.i(
                TAG,
                "刷新围栏：共 ${activePoints.size} 个启用中的打卡点，立即重新注册高德围栏"
            )

            // 重新向高德注册圆形围栏（内部会先清空旧围栏）
            amapFenceRegistrar.registerAll(activePoints)

            onDone?.invoke()
            onMonitorStateChanged?.invoke()
        }
    }

    /**
     * 启动监控。
     *
     * @param backgroundNotification 高德后台定位所需通知（前台服务已有常驻通知时传 null）
     */
    fun start(backgroundNotification: Notification? = null) {
        if (isRunning) {
            Log.d(TAG, "监控已在运行，仅刷新围栏")
            refresh()
            return
        }

        engine.onError = { code, info ->
            Log.w(TAG, "定位错误 code=$code info=$info")
            onLocationError?.invoke(code, info)
        }
        engine.onLocation = { snapshot ->
            handleLocation(snapshot)
        }

        // 作用域可能在之前的 stop() 中被取消，这里重建，保证后续 launch 有效
        scope = newScope()

        // 首次以低频启动，等第一次定位结果回来后再按需升频（省电优先）
        val initialInterval = scheduler.currentTier.intervalMs
        Log.i(TAG, "启动监控：初始定位间隔=${initialInterval}ms")

        engine.start(
            intervalMs = initialInterval,
            onceLocation = false,
            backgroundNotification = backgroundNotification
        )

        isRunning = true
        refresh {
            // 把当前定位参数同步给高德围栏客户端，保证围栏与定位节奏一致
            engine.currentOption()?.let { amapFenceRegistrar.attachLocationOption(it) }
        }
    }

    /** 停止监控并释放全部资源 */
    fun stop() {
        Log.i(TAG, "停止监控")
        isRunning = false
        engine.onLocation = null
        engine.onError = null
        engine.stop()
        amapFenceRegistrar.release()
        scheduler.reset()
        // 清空围栏状态与距离：否则再次开启监控时会带着上一次的 INSIDE/LEFT 状态，
        // 在重新建立基线前就误判出进入/离开事件（首次判定本应只建基线、不产事件）。
        stateMachine.reset()
        activePoints = emptyList()
        // 停止监控后清空去重记录：下次开启监控时允许重新提醒
        deduper.clear()
        // 取消当前作用域（下次 start 会重建一个新的）
        scope.cancel()
    }

    /** 处理一次定位：状态机判定 → 事件落库/通知 → 动态调频 */
    private fun handleLocation(snapshot: LocationSnapshot) {
        // 0) 心跳：每收到一次定位就通知服务续期唤醒锁（见 onHeartbeat 的说明）
        onHeartbeat?.invoke()

        val points = activePoints
        if (points.isEmpty()) {
            // 没有启用中的打卡点，保持低频空转，避免无谓耗电
            engine.applyInterval(LocationIntervalTier.FAR.intervalMs)
            return
        }

        // 1) 状态机判定
        val events = stateMachine.evaluate(
            points = points,
            latitude = snapshot.latitude,
            longitude = snapshot.longitude,
            accuracyMeters = snapshot.accuracy,
            timestamp = snapshot.timestamp
        )

        // 2) 处理进入/离开事件
        events.forEach { handleFenceEvent(it) }

        // 3) 动态调频
        adjustInterval()
    }

    /**
     * 通知去重器（需求 5）。
     *
     * 同一打卡点在同一状态下只提醒一次：INSIDE 只提醒一次进入，
     * 经过 LEFT 之后再次进入才会重新提醒。
     */
    private val deduper = NotificationDeduper()

    /** 把事件写入打卡记录并发送高优先级通知 */
    private fun handleFenceEvent(event: FenceEvent) {
        val point = event.checkPoint
        val isEnter = event.type == FenceEventType.ENTER

        Log.i(
            TAG,
            "触发${if (isEnter) "进入" else "离开"}事件：${point.name}，" +
                "距离 ${event.distanceMeters.toInt()} 米，半径 ${point.radiusMeters} 米，" +
                "新状态=${event.newState}"
        )

        // ---------------- 防重复提醒 ----------------
        // 注意：先判断开关，再决定是否消费去重名额；否则关掉开关的点会白占名额
        val switchEnabled = if (isEnter) point.notifyOnEnter else point.notifyOnExit
        val shouldNotify = switchEnabled && deduper.shouldNotify(point.id, event.newState)

        if (!switchEnabled) {
            Log.d(TAG, "${point.name} 的提醒开关已关闭，仅写记录不发通知")
        } else if (!shouldNotify) {
            Log.i(TAG, "${point.name} 已就 ${event.newState} 状态提醒过，跳过重复通知（去重）")
        }

        scope.launch {
            // 1) 写入打卡记录：
            //    - 进入事件 → AUTO_CONFIRMED（待用户点击打卡确认页后转 MANUAL）
            //    - 离开事件 → FENCE_EXIT（独立来源，仅用于轨迹追溯，绝不被后续打卡就地改写）
            runCatching {
                recordRepository.add(
                    point = point,
                    latitude = event.latitude,
                    longitude = event.longitude,
                    source = if (isEnter) CheckInSource.AUTO_CONFIRMED else CheckInSource.FENCE_EXIT,
                    isEnter = isEnter,
                    distanceMeters = event.distanceMeters,
                    time = event.timestamp
                )
            }.onFailure { Log.e(TAG, "写入打卡记录失败", it) }

            // 2) 发送高优先级通知（震动 + 声音 + 锁屏可见，点击进打卡确认页）
            if (shouldNotify) {
                if (isEnter) {
                    notifier.notifyEnter(
                        context = appContext,
                        pointId = point.id,
                        pointName = point.name,
                        distanceMeters = event.distanceMeters
                    )
                } else {
                    notifier.notifyLeave(
                        context = appContext,
                        pointId = point.id,
                        pointName = point.name,
                        distanceMeters = event.distanceMeters
                    )
                }
            }
        }
    }

    /**
     * 动态调频。
     *
     * 全部远离 → 30 秒；有 NEAR/INSIDE → 5 秒。档位切换带迟滞与最小间隔节流。
     */
    private fun adjustInterval() {
        val anyNearOrInside = stateMachine.hasNearPoint() || stateMachine.hasInsidePoint()

        // 先取一次状态快照，避免在 lambda 内反复读 StateFlow.value
        val snapshot = stateMachine.states.value

        // 取最近打卡点的 NEAR 阈值，用于迟滞判断
        val nearest = activePoints.minByOrNull { point ->
            snapshot[point.id]?.distanceMeters ?: Double.MAX_VALUE
        }
        val nearestThreshold = nearest?.let { stateMachine.nearThresholdOf(it.radiusMeters) }

        val changed = scheduler.evaluate(
            anyNearOrInside = anyNearOrInside,
            minDistanceMeters = stateMachine.minDistance(),
            nearestNearThreshold = nearestThreshold
        )

        if (changed) {
            val tier = scheduler.currentTier
            val reason = scheduler.lastReason
            // 投递到主线程消息队列**尾部**再执行：applyInterval 内部会
            // stopLocation + onDestroy + 新建定位客户端，若直接在 handleLocation
            // 的回调栈内执行，等于在监听器回调期间销毁「正在回调自己的」客户端，
            // 存在回调链路状态不一致的隐患。
            mainHandler.post {
                val applied = engine.applyInterval(tier.intervalMs)
                Log.i(
                    TAG,
                    "定位频率切换为 ${tier.name}（${tier.intervalMs}ms），原因：$reason，已生效=$applied"
                )
                onMonitorStateChanged?.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceManager"
    }
}
