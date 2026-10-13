// 职责：定位工具类，封装高德定位 SDK 的单次定位、连续定位与停止定位
package com.example.checkin.util

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.checkin.geofence.AMapLocationEngine
import com.example.checkin.geofence.LocationSnapshot

/**
 * 定位结果：单次定位用一次性回调，连续定位用持续回调。
 *
 * 使用密封类而不是抛异常，调用方可以穷举处理，避免忘记写 try/catch。
 */
sealed interface LocationOutcome {

    /** 定位成功 */
    data class Success(val location: LocationSnapshot) : LocationOutcome

    /**
     * 定位失败。
     *
     * @param error 包含高德错误码、原始描述与可读中文提示
     */
    data class Failure(val error: LocationError) : LocationOutcome
}

/**
 * 高德定位工具类（Locator）。
 *
 * 设计要点：
 * 1. **单一职责**：只做「发起定位 + 回调结果」，围栏判定等业务逻辑不放在这里；
 * 2. **线程安全**：高德要求客户端操作在**主线程**执行，因此内部统一用主线程 Handler 投递；
 * 3. **超时兜底**：高德 `setHttpTimeOut` 只在有网络请求时才生效（纯 GPS 定位可能一直不回调），
 *    这里额外用主线程延时任务做超时保护，保证单次定位一定会有结果返回；
 * 4. **参数可配**：定位间隔默认 [DEFAULT_INTERVAL_MS]（5000ms），超时默认
 *    [DEFAULT_TIMEOUT_MS]（10 秒），定位模式固定为
 *    [com.amap.api.location.AMapLocationClientOption.AMapLocationMode.Hight_Accuracy]
 *    （GPS + 基站 + WiFi 融合）。
 *
 * 典型用法：
 * ```
 * val locator = Locator(context)
 * // 单次定位
 * locator.requestSingleLocation { outcome -> ... }
 * // 连续定位
 * locator.startContinuousLocation(intervalMs = 5000L) { outcome -> ... }
 * // 停止
 * locator.stopLocation()
 * ```
 *
 * 注意：使用完毕后应调用 [stopLocation] 释放定位资源（例如在 ViewModel#onCleared 中）。
 */
class Locator(context: Context) {

    private val appContext: Context = context.applicationContext
    private val engine = AMapLocationEngine(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 单次定位的超时兜底任务 */
    private var singleTimeoutTask: Runnable? = null

    /** 单次定位的一次性回调（已包装为只执行一次） */
    private var singleCallback: OnceCallback? = null

    /** 连续定位的回调 */
    private var continuousCallback: ((LocationOutcome) -> Unit)? = null

    /** 当前是否处于定位中 */
    var isLocating: Boolean = false
        private set

    /** 当前是否处于连续定位模式 */
    var isContinuous: Boolean = false
        private set

    // ------------------------------------------------------------------ 单次定位

    /**
     * 启动单次定位。
     *
     * 成功或失败都会且只会回调一次；若超过 [timeoutMs] 仍无结果，则回调超时错误。
     *
     * @param intervalMs 定位间隔（对单次定位仅为首次取点策略，保留可配置）
     * @param timeoutMs  超时保护时间，默认 10 秒
     * @param needAddress 是否请求逆地理地址
     * @param onResult   结果回调（主线程）
     */
    @SuppressLint("MissingPermission")
    fun requestSingleLocation(
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        needAddress: Boolean = true,
        onResult: (LocationOutcome) -> Unit
    ) {
        Log.i(TAG, "启动单次定位：间隔=${intervalMs}ms，超时=${timeoutMs}ms")

        stopLocation()

        // 包一层「只回调一次」，避免 SDK 多次回调或超时与成功回调并发
        val once = OnceCallback(onResult)
        singleCallback = once
        continuousCallback = null
        isContinuous = false
        isLocating = true

        engine.onLocation = { snapshot ->
            Log.i(
                TAG,
                "单次定位成功：lat=${snapshot.latitude}, lng=${snapshot.longitude}, " +
                    "accuracy=${snapshot.accuracy}m, source=${snapshot.sourceName}"
            )
            isLocating = false
            cancelSingleTimeout()
            // 单次定位拿到结果后立即停止，避免继续耗电
            engine.stop()
            once.invoke(LocationOutcome.Success(snapshot))
        }

        engine.onError = { code, info ->
            isLocating = false
            cancelSingleTimeout()
            engine.stop()
            once.invoke(LocationOutcome.Failure(LocationError.from(code, info)))
        }

        engine.start(
            intervalMs = intervalMs,
            onceLocation = true,
            httpTimeOutMs = timeoutMs,
            // 打卡确认页只需要经纬度与距离，逆地理是可选项
            needAddress = needAddress
        )

        // 超时兜底：高德 setHttpTimeOut 覆盖不到「无网络请求的纯 GPS 场景」
        scheduleSingleTimeout(timeoutMs, once)
    }

    // ------------------------------------------------------------------ 连续定位

    /**
     * 启动连续定位，按 [intervalMs] 持续回调。
     *
     * 每次成功/失败都会回调，直到调用 [stopLocation]。
     *
     * @param intervalMs 定位间隔，默认 5000ms，最小 1000ms
     * @param httpTimeOutMs 单次联网请求超时，默认 10 秒
     * @param backgroundNotification 需要后台持续定位时传入常驻通知（高德后台定位能力）
     * @param onResult 结果回调（主线程）
     */
    @SuppressLint("MissingPermission")
    fun startContinuousLocation(
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        httpTimeOutMs: Long = DEFAULT_TIMEOUT_MS,
        backgroundNotification: Notification? = null,
        onResult: (LocationOutcome) -> Unit
    ) {
        Log.i(
            TAG,
            "启动连续定位：间隔=${intervalMs}ms，超时=${httpTimeOutMs}ms，" +
                "后台定位=${backgroundNotification != null}"
        )

        stopLocation()

        continuousCallback = onResult
        singleCallback = null
        isContinuous = true
        isLocating = true

        engine.onLocation = { snapshot ->
            Log.d(
                TAG,
                "连续定位回调：lat=${snapshot.latitude}, lng=${snapshot.longitude}, " +
                    "accuracy=${snapshot.accuracy}m, source=${snapshot.sourceName}"
            )
            continuousCallback?.invoke(LocationOutcome.Success(snapshot))
        }

        engine.onError = { code, info ->
            // 连续定位下单次失败不终止监听，仅上报错误
            continuousCallback?.invoke(LocationOutcome.Failure(LocationError.from(code, info)))
        }

        engine.start(
            intervalMs = intervalMs,
            onceLocation = false,
            httpTimeOutMs = httpTimeOutMs,
            backgroundNotification = backgroundNotification
        )
    }

    // ------------------------------------------------------------------ 停止

    /**
     * 停止定位并释放资源。
     *
     * 调用后不会再有任何回调；再次定位需要重新调用上面的启动方法。
     */
    fun stopLocation() {
        cancelSingleTimeout()
        // 先摘掉回调再停止，杜绝「停止后仍然收到回调」
        engine.onLocation = null
        engine.onError = null
        singleCallback = null
        continuousCallback = null
        engine.stop()
        isLocating = false
        isContinuous = false
        Log.i(TAG, "已停止定位")
    }

    // ------------------------------------------------------------------ 内部实现

    /** 安排单次定位超时兜底 */
    private fun scheduleSingleTimeout(timeoutMs: Long, once: OnceCallback) {
        val task = Runnable {
            if (once.isConsumed) return@Runnable
            Log.w(TAG, "单次定位超时（${timeoutMs}ms 内未回调）")
            isLocating = false
            engine.stop()
            once.invoke(LocationOutcome.Failure(LocationError.from(ERROR_TIMEOUT, "定位超时")))
        }
        singleTimeoutTask = task
        // 略微晚于 SDK 自身超时，优先让 SDK 返回更精确的错误码
        mainHandler.postDelayed(task, timeoutMs + TIMEOUT_GRACE_MS)
    }

    /** 取消超时任务 */
    private fun cancelSingleTimeout() {
        singleTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        singleTimeoutTask = null
    }

    /** 保证回调只执行一次的包装器（线程安全） */
    private class OnceCallback(private val delegate: (LocationOutcome) -> Unit) {
        @Volatile
        var isConsumed: Boolean = false
            private set

        private val lock = Any()

        fun invoke(outcome: LocationOutcome) {
            synchronized(lock) {
                if (isConsumed) return
                isConsumed = true
            }
            delegate(outcome)
        }
    }

    companion object {
        private const val TAG = "Locator"

        /** 默认定位间隔：5 秒（需求默认值） */
        const val DEFAULT_INTERVAL_MS = 5_000L

        /** 默认单次定位超时：10 秒（需求默认值） */
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** 超时兜底比 SDK 超时稍晚，优先使用 SDK 的错误码 */
        private const val TIMEOUT_GRACE_MS = 1_500L

        /**
         * 自定义超时错误码。
         *
         * 取 -2，与 [AMapLocationEngine.ERROR_START_FAILED]（-1）及高德正数错误码区间都不冲突。
         */
        const val ERROR_TIMEOUT = -2
    }
}
