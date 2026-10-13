// 职责：高德地理围栏注册器，用 GeoFenceClient 为打卡点注册圆形围栏并接收进出回调
package com.example.checkin.geofence

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.amap.api.fence.GeoFence
import com.amap.api.fence.GeoFenceClient
import com.amap.api.fence.GeoFenceListener
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.DPoint
import com.example.checkin.data.local.entity.CheckinPoint

/**
 * 高德本地地理围栏注册器。
 *
 * 与 [FenceStateMachine] 的分工（重要）：
 * - **本类**：按需求调用高德 `GeoFenceClient.addGeoFence` 注册圆形围栏，
 *   做客户端侧的围栏登记与事件接收；
 * - **[FenceStateMachine]**：基于连续定位距离做**权威判定**，负责真正的进入/离开判定与调频。
 *
 * 之所以不让高德围栏直接驱动通知：高德围栏在部分机型/无 GPS 时会静默不回调，
 * 若把它当作唯一触发源，用户会遇到「走了半天不提醒」。因此这里把高德围栏作为
 * 增强手段，其回调只用于**日志与状态核对**，判定权仍归状态机，保证行为可预期。
 *
 * 线程约束：`GeoFenceClient` 的内部实现要求主线程创建与调用，
 * 因此所有对外方法都通过主线程 Handler 投递。
 */
class AMapGeoFenceRegistrar(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var fenceClient: GeoFenceClient? = null

    /** 最近一次注册结果（高德错误码，0 = ADDGEOFENCE_SUCCESS） */
    @Volatile
    var lastRegisterResultCode: Int = Int.MIN_VALUE
        private set

    /** 已成功登记的围栏数量 */
    @Volatile
    var registeredCount: Int = 0
        private set

    private val listener = GeoFenceListener { geoFenceList, errorCode, message ->
        lastRegisterResultCode = errorCode
        if (errorCode == GeoFence.ADDGEOFENCE_SUCCESS) {
            registeredCount = geoFenceList?.size ?: 0
            Log.i(TAG, "高德围栏注册成功，数量=$registeredCount")
        } else {
            Log.w(
                TAG,
                "高德围栏注册失败：errorCode=$errorCode, message=$message, " +
                    "customId=${geoFenceList?.firstOrNull()?.customId}"
            )
        }
    }

    /**
     * 注册全部启用中的打卡点为圆形围栏。
     *
     * 每次增删改打卡点后都应调用（服务收到 ACTION_REFRESH 时）。
     * 内部会先清空旧围栏再重新登记。
     */
    fun registerAll(points: List<CheckinPoint>) {
        mainHandler.post {
            val client = obtainClient()
            if (client == null) {
                Log.e(TAG, "GeoFenceClient 初始化失败，跳过围栏注册")
                return@post
            }

            // 先清空，避免旧围栏残留导致重复回调
            runCatching { client.removeGeoFence() }
                .onFailure { Log.w(TAG, "清空旧围栏失败", it) }

            if (points.isEmpty()) {
                registeredCount = 0
                Log.i(TAG, "没有启用中的打卡点，已清空全部围栏")
                return@post
            }

            var added = 0
            points.forEach { point ->
                runCatching {
                    // 圆形围栏重载：addGeoFence(中心点, 半径米, customId)
                    // customId 用打卡点 id，便于回调里反查是哪个点
                    client.addGeoFence(
                        DPoint(point.latitude, point.longitude),
                        point.radiusMeters.toFloat(),
                        point.id.toString()
                    )
                    added++
                }.onFailure {
                    Log.e(TAG, "注册围栏失败：${point.name}（id=${point.id}）", it)
                }
            }

            registeredCount = added
            Log.i(TAG, "已提交 $added 个圆形围栏（等待高德回调确认）")
        }
    }

    /**
     * 让高德围栏跟随定位客户端的定位参数。
     *
     * 高德要求围栏与定位使用同一套 option，否则内部定位节奏不一致。
     */
    fun attachLocationOption(option: AMapLocationClientOption) {
        mainHandler.post {
            runCatching { obtainClient()?.setLocationClientOption(option) }
                .onFailure { Log.w(TAG, "设置围栏定位参数失败", it) }
        }
    }

    /** 清空全部围栏并释放客户端 */
    fun release() {
        mainHandler.post {
            runCatching {
                fenceClient?.removeGeoFence()
                fenceClient?.setGeoFenceListener(null)
            }.onFailure { Log.w(TAG, "释放围栏客户端异常", it) }
            fenceClient = null
            registeredCount = 0
        }
    }

    /** 延迟创建 GeoFenceClient（必须主线程） */
    private fun obtainClient(): GeoFenceClient? {
        fenceClient?.let { return it }
        return runCatching {
            GeoFenceClient(context.applicationContext).apply {
                setGeoFenceListener(listener)
                // 注册 + 自动激活：进入/离开/停留都上报
                setActivateAction(
                    GeoFenceClient.GEOFENCE_IN or
                        GeoFenceClient.GEOFENCE_OUT or
                        GeoFenceClient.GEOFENCE_STAYED
                )
                fenceClient = this
            }
        }.onFailure { Log.e(TAG, "创建 GeoFenceClient 失败", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "AMapGeoFenceRegistrar"
    }
}
