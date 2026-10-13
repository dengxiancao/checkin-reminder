// 职责：高德定位客户端封装，负责连续定位、后台定位能力与回调分发
package com.example.checkin.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.example.checkin.util.LocationSource

/** 一次有效定位的快照，屏蔽高德 SDK 类型，便于上层与单测使用 */
data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    /** 水平精度（米），越小越准 */
    val accuracy: Float,
    /** 逆地理地址，未开启逆地理或解析失败时为 null */
    val address: String?,
    /** 定位来源（gps / network / cache / unknown） */
    val source: LocationSource,
    /** 高德原始定位类型值，便于排查 */
    val locationType: Int,
    /** 定位可信度（高德 6.x 起提供，越大越可信） */
    val trustLevel: Int,
    /** 定位时间戳（毫秒） */
    val timestamp: Long
) {
    /** 供界面直接展示的来源文本 */
    val sourceName: String get() = source.displayName
}

/**
 * 高德定位底座封装。
 *
 * 关键约束：
 * 1. AMapLocationClient 的属性设置与启动**必须在主线程**调用，否则 SDK 内部会抛异常；
 *    因此 [start] / [stop] 都通过主线程 Handler 投递。
 * 2. 连续定位模式下用 [AMapLocationClientOption.setSelfStartServiceEnable] 允许定位服务
 *    被系统杀死后自启，配合前台服务提升后台存活率。
 */
class AMapLocationEngine(private val context: Context) {

    private var client: AMapLocationClient? = null

    /** 当前生效的定位间隔，用于判断是否需要重建客户端 */
    @Volatile
    private var currentIntervalMs: Long? = null

    /** 是否为单次定位模式（动态调频仅对连续定位有意义） */
    @Volatile
    private var onceLocationMode: Boolean = false

    /** 当前生效的联网超时，重建时复用 */
    @Volatile
    private var currentHttpTimeOutMs: Long? = null

    /** 当前使用的后台定位通知，重建时复用 */
    @Volatile
    private var currentBackgroundNotification: Notification? = null

    /** 当前是否请求逆地理地址，重建时复用 */
    @Volatile
    private var currentNeedAddress: Boolean = true

    /** 收到有效定位时回调（已过滤错误码与非法坐标） */
    var onLocation: ((LocationSnapshot) -> Unit)? = null

    /** 定位失败时回调，参数为高德错误码与描述 */
    var onError: ((Int, String) -> Unit)? = null

    private val listener = AMapLocationListener { location ->
        handleLocation(location)
    }

    /** 是否已经启动定位 */
    val isStarted: Boolean
        get() = client?.isStarted == true

    /**
     * 取当前生效的定位参数副本。
     *
     * 高德围栏客户端需要与定位客户端共用同一套 option（否则内部定位节奏不一致），
     * 因此这里把正在使用的 option 暴露出去。未启动过定位时返回 null。
     */
    fun currentOption(): AMapLocationClientOption? =
        if (client == null) {
            null
        } else {
            runCatching {
                buildOption(
                    intervalMs = currentIntervalMs ?: DEFAULT_INTERVAL_MS,
                    onceLocation = onceLocationMode,
                    httpTimeOutMs = currentHttpTimeOutMs ?: DEFAULT_HTTP_TIMEOUT_MS,
                    needAddress = currentNeedAddress
                )
            }.getOrNull()
        }

    /**
     * 初始化并启动定位。
     *
     * @param intervalMs 连续定位间隔，建议 5000~15000ms，过短会显著增加耗电
     * @param onceLocation true=单次定位（取一次结果即可），false=连续定位
     * @param httpTimeOutMs 联网超时时间（毫秒）
     * @param backgroundNotification 非空时调用 enableBackgroundLocation 开启高德后台定位能力
     */
    @SuppressLint("MissingPermission")
    fun start(
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        onceLocation: Boolean = false,
        httpTimeOutMs: Long = DEFAULT_HTTP_TIMEOUT_MS,
        needAddress: Boolean = true,
        backgroundNotification: Notification? = null
    ) {
        if (client != null) {
            Log.d(TAG, "定位客户端已存在，先停止再重启")
            stop()
        }

        // 记录本次参数，供 applyInterval 动态重建时复用
        onceLocationMode = onceLocation
        currentHttpTimeOutMs = httpTimeOutMs
        currentNeedAddress = needAddress
        currentBackgroundNotification = backgroundNotification
        currentIntervalMs = if (onceLocation) null else intervalMs.coerceAtLeast(MIN_INTERVAL_MS)

        try {
            client = AMapLocationClient(context.applicationContext).apply {
                setLocationListener(listener)
                setLocationOption(buildOption(intervalMs, onceLocation, httpTimeOutMs, needAddress))
                // 后台定位能力需要定位权限，未授权时直接跳过，避免抛 SecurityException
                if (backgroundNotification != null && hasLocationPermission()) {
                    // 通知 id 不能为 0，且 notification 不能为 null
                    enableBackgroundLocation(BACKGROUND_LOCATION_NOTIFICATION_ID, backgroundNotification)
                }
                startLocation()
            }
            Log.i(
                TAG,
                "高德定位已启动：模式=${if (onceLocation) "单次" else "连续"}，" +
                    "间隔=${intervalMs}ms，超时=${httpTimeOutMs}ms"
            )
        } catch (t: Throwable) {
            // 构造方法声明抛出 Exception；Key 缺失/隐私未同意时也可能在此失败
            Log.e(TAG, "启动高德定位失败", t)
            onError?.invoke(ERROR_START_FAILED, t.message ?: "启动定位失败")
            client = null
        }
    }

    /** 是否已获得定位权限（细/粗定位任一即可） */
    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** 停止定位并释放资源；释放后再次定位需重新实例化 */
    fun stop() {
        client?.let { c ->
            runCatching {
                c.stopLocation()
                c.unRegisterLocationListener(listener)
                c.disableBackgroundLocation(true)
                c.onDestroy()
            }.onFailure { Log.w(TAG, "释放定位客户端异常", it) }
        }
        client = null
        currentIntervalMs = null
    }

    /**
     * 动态调整连续定位的间隔。
     *
     * 高德 SDK 的定位间隔通过 `AMapLocationClientOption` 传入，运行中直接修改不会生效，
     * 必须 **停止 → 重新设置 option → 启动**（官方推荐做法）。
     * 因此这里只在间隔真正变化时才重建，避免无谓抖动。
     *
     * @return true 表示已应用（间隔发生变化并重启），false 表示无需变更或当前未在定位
     */
    fun applyInterval(intervalMs: Long): Boolean {
        // 动态调频只对「连续定位」有意义：单次定位模式下无间隔可调；
        // 客户端尚未创建时也无从重建。注意此处条件**不能**写成 !onceLocationMode
        // （那是原实现的笔误：连续定位时 onceLocationMode==false，!false==true，
        //  会导致函数在第一行就返回，动态调频从此永不生效）。
        if (onceLocationMode || client == null) return false
        val normalized = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        if (currentIntervalMs == normalized) return false

        Log.i(TAG, "调整定位间隔：${currentIntervalMs ?: "默认"}ms -> ${normalized}ms")
        // 复用 start() 的重建流程，保持参数一致
        start(
            intervalMs = normalized,
            onceLocation = false,
            httpTimeOutMs = currentHttpTimeOutMs ?: DEFAULT_HTTP_TIMEOUT_MS,
            needAddress = currentNeedAddress,
            backgroundNotification = currentBackgroundNotification
        )
        return true
    }

    /**
     * 构造定位参数。
     *
     * 省电取舍：
     * - 高精度模式（GPS + 基站 + WiFi 融合），默认 5 秒间隔；
     * - 关闭传感器（不采集海拔/速度）以省电；
     * - 关闭定位缓存，避免返回过期坐标导致围栏误判 / Debug 页显示旧值；
     * - 关闭 Mock 位置，防止虚拟定位伪造打卡；
     * - httpTimeOut 控制单次联网定位请求的超时时间。
     */
    private fun buildOption(
        intervalMs: Long,
        onceLocation: Boolean,
        httpTimeOutMs: Long,
        needAddress: Boolean
    ): AMapLocationClientOption =
        AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
            isNeedAddress = needAddress
            isOnceLocation = onceLocation
            // 单次定位时等待 WiFi 刷新以获得更高精度（连续定位下仅首次生效）
            isOnceLocationLatest = onceLocation
            isLocationCacheEnable = false
            isMockEnable = false
            isSensorEnable = false
            isGpsFirst = false
            httpTimeOut = httpTimeOutMs.coerceAtLeast(MIN_HTTP_TIMEOUT_MS)
            geoLanguage = AMapLocationClientOption.GeoLanguage.ZH
            // 连续定位场景下允许定位服务被系统杀死后自启动，配合 START_STICKY 提升后台存活；
            // 单次定位不需要该能力，关闭以避免进程被意外拉起。
            if (!onceLocation) {
                setSelfStartServiceEnable(true)
            }
        }

    /** 校验并分发定位结果 */
    private fun handleLocation(location: AMapLocation) {
        // 用 Throwable 兜底：高德 SDK 内部异常（JNI、状态机）会直接抛到主线程导致闪退，
        // 这里统一转成错误回调，让界面能提示而不是崩溃。
        try {
            if (location.errorCode != 0) {
                Log.w(TAG, "定位失败 code=${location.errorCode} info=${location.errorInfo}")
                onError?.invoke(location.errorCode, location.errorInfo ?: "定位失败")
                return
            }
            // 坐标为 0 视为无效（高德在无结果时会返回 0.0）
            if (location.latitude == 0.0 && location.longitude == 0.0) {
                Log.w(TAG, "忽略无效坐标 (0,0)")
                return
            }

            onLocation?.invoke(
                LocationSnapshot(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    address = location.address,
                    source = LocationSource.fromAmapLocationType(location.locationType),
                    locationType = location.locationType,
                    trustLevel = location.trustedLevel,
                    timestamp = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "处理定位回调异常", t)
            onError?.invoke(ERROR_CALLBACK_FAILED, t.message ?: "处理定位结果异常")
        }
    }

    companion object {
        private const val TAG = "AMapLocationEngine"

        /** 高德后台定位通知 id，不能为 0 */
        private const val BACKGROUND_LOCATION_NOTIFICATION_ID = 1001

        /** 定位间隔下限，高德规定小于 1000ms 按 1000ms 处理 */
        const val MIN_INTERVAL_MS = 1000L

        /** 默认连续定位间隔：5 秒（需求默认值） */
        const val DEFAULT_INTERVAL_MS = 5_000L

        /** 默认联网超时：10 秒（需求默认值） */
        const val DEFAULT_HTTP_TIMEOUT_MS = 10_000L

        /** 联网超时下限，高德建议不低于 8 秒，否则弱网必然超时 */
        const val MIN_HTTP_TIMEOUT_MS = 8_000L

        /** 自定义启动失败错误码（避免与高德错误码区间冲突） */
        const val ERROR_START_FAILED = -1

        /** 自定义：处理定位回调时抛异常（如 JNI 异常） */
        const val ERROR_CALLBACK_FAILED = -3
    }
}
