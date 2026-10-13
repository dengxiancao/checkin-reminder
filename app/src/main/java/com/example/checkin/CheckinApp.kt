// 职责：Application 入口，初始化高德定位/地图 SDK（隐私合规 + Key）并创建通知渠道
package com.example.checkin

import android.app.Application
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.MapsInitializer
import com.example.checkin.data.repository.ServiceLocator
import com.example.checkin.notify.Notifier
import com.example.checkin.service.CheckinForegroundService
import com.example.checkin.util.PreferencesStore
import com.example.checkin.util.PrivacyConsentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CheckinApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1) 通知渠道必须在任何通知发出之前创建（Android 8.0+ 必需）
        Notifier.createChannels(this)

        // 2) 初始化高德 SDK
        initAMap()

        // 3) 预热「提醒方式」偏好缓存。
        //    Notifier 需要在发通知前**同步**读取该缓存；若进程是被系统直接拉起
        //    （开机自恢复 / 看活闹钟），用户可能从未打开过设置页，不预热就会读到
        //    默认值（声音+震动+全屏全开）而发错通知。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { PreferencesStore.prewarm(this@CheckinApp) }
                .onFailure { Log.e(TAG, "预热提醒方式偏好失败", it) }
        }

        // 4) 若上次退出时监控处于开启状态，则自动拉起前台服务
        autoStartMonitoringIfNeeded()
    }

    /**
     * App 启动时自动恢复监控。
     *
     * 用户上次主动开启监控 → 开机/重启 App 后应自动继续监控，无需手动再点。
     * 这里放到 IO 协程里读 DataStore，避免阻塞主线程造成启动白屏。
     */
    private fun autoStartMonitoringIfNeeded() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val enabled = runCatching {
                // 统一走进程级单例，与 MainViewModel / Notifier 共享同一份缓存
                PreferencesStore.get(this@CheckinApp).readMonitoringEnabled()
            }.onFailure {
                Log.e(TAG, "读取监控开关失败", it)
            }.getOrDefault(false)

            if (!enabled) {
                Log.i(TAG, "监控开关为关闭状态，不自动启动服务")
                return@launch
            }

            // 没有启用中的打卡点时不必常驻前台服务
            val points = runCatching {
                ServiceLocator.checkinPointRepository(this@CheckinApp).getEnabledPoints()
            }.onFailure {
                Log.e(TAG, "读取打卡点失败", it)
            }.getOrDefault(emptyList())

            if (points.isEmpty()) {
                Log.i(TAG, "没有启用中的打卡点，不自动启动监控服务")
                return@launch
            }

            Log.i(TAG, "监控开关为开启，自动拉起前台监控服务（${points.size} 个打卡点）")
            CheckinForegroundService.start(this@CheckinApp)
        }
    }

    /**
     * 初始化高德定位与地图 SDK。
     *
     * Key 来自 local.properties 的 MAP_KEY 字段，经 BuildConfig 注入，绝不硬编码。
     * 依据高德合规要求，必须先调用 updatePrivacyShow / updatePrivacyAgree，
     * 再实例化定位客户端，否则定位会直接返回错误。
     *
     * 注意：地图 SDK 的隐私接口在不同大版本间有差异（本工程使用的 map3d 6.8.0 没有
     * 公开该接口，而 3dmap 9.x 有），因此对地图侧采用**反射 + 存在性判断**，
     * 既避免编译期绑定到某个版本，也保证在新版 SDK 上依然满足合规要求。
     */
    private fun initAMap() {
        val key = BuildConfig.MAP_KEY
        if (key.isBlank()) {
            Log.w(
                TAG,
                "高德 Key 为空！请在 local.properties 中配置 MAP_KEY=你的Key，" +
                    "否则定位与地图将无法工作。"
            )
        }

        // 是否已同意隐私政策。
        // 必须从 SharedPreferences **同步**读取：此处的值决定了高德 SDK 是否允许工作，
        // 若读到 false（例如异步读到默认值），定位会直接失败（错误码 27）。
        val privacyAgreed = PrivacyConsentStore.isAgreed(this)
        Log.i(TAG, "高德 SDK 初始化：Key长度=${key.length}，隐私已同意=$privacyAgreed")

        // ---------------- 定位 SDK：接口稳定，直接调用 ----------------
        runCatching {
            AMapLocationClient.updatePrivacyShow(this, true, privacyAgreed)
            AMapLocationClient.updatePrivacyAgree(this, privacyAgreed)
            if (key.isNotBlank()) {
                // 清单里的 meta-data 在部分 ROM 上可能被覆盖，这里双保险
                AMapLocationClient.setApiKey(key)
            }
        }.onFailure {
            Log.e(TAG, "定位 SDK 隐私/Key 初始化失败", it)
        }

        // ---------------- 地图 SDK：反射调用，兼容各版本 ----------------
        invokePrivacyIfPresent("updatePrivacyShow", this, true, privacyAgreed)
        invokePrivacyIfPresent("updatePrivacyAgree", this, privacyAgreed)
        if (key.isNotBlank() && runCatching { MapsInitializer.setApiKey(key) }.isFailure) {
            Log.w(TAG, "MapsInitializer.setApiKey 调用失败，请确认地图 SDK 版本")
        }

        // 定位网络协议统一走 HTTPS
        AMapLocationClientOption.setLocationProtocol(
            AMapLocationClientOption.AMapLocationProtocol.HTTPS
        )
    }

    /**
     * 通过反射调用 MapsInitializer 的隐私合规方法。
     *
     * @return true 表示方法存在且调用成功；false 表示当前 SDK 版本没有该接口
     */
    private fun invokePrivacyIfPresent(methodName: String, vararg args: Any?): Boolean {
        return runCatching {
            val method = MapsInitializer::class.java.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == args.size
            } ?: return false
            method.invoke(null, *args)
            true
        }.getOrElse {
            Log.w(TAG, "调用 MapsInitializer.$methodName 失败：${it.message}")
            false
        }
    }

    private companion object {
        const val TAG = "CheckinApp"
    }
}
