// 职责：本地偏好存储（DataStore），保存隐私同意、监控开关与提醒方式
package com.example.checkin.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 进程内唯一的 DataStore 实例 */
private val Context.dataStore by preferencesDataStore(name = "checkin_prefs")

/** 提醒方式偏好 */
data class NotifyPreferences(
    /** 是否播放提示音 */
    val sound: Boolean = true,
    /** 是否震动 */
    val vibrate: Boolean = true,
    /** 是否使用全屏提醒（锁屏直接弹出打卡页） */
    val fullScreen: Boolean = true
)

/** 电源与电池优化状态 */
data class PowerState(
    /** 是否已加入电池优化白名单（不优化） */
    val batteryOptimizationExempt: Boolean = false,
    /** 系统是否处于省电模式 */
    val powerSaveMode: Boolean = false,
    /** 是否具备精确定时能力（Android 12+ 需 SCHEDULE_EXACT_ALARM） */
    val exactAlarmAllowed: Boolean = true
)

/**
 * 轻量偏好读写。
 *
 * 注意：这里刻意**不**使用 runBlocking 读取——Application.onCreate 中若阻塞主线程
 * 等待 DataStore 初始化会触发死锁风险，因此读取接口是挂起函数；
 * [isPrivacyAgreed] 仅作为同步兜底，默认 false，保证合规优先。
 */
class PreferencesStore(private val context: Context) {

    /** 同步兜底值：未读取过时视为未同意，保证合规优先 */
    var isPrivacyAgreed: Boolean = false
        private set

    /**
     * 同步缓存的提醒方式（由 Notifier 在发通知前同步读取）。
     *
     * 注意：缓存真正存放在**伴生对象**中（进程级），此处只是对外暴露的只读入口。
     * 这样无论通过哪个 [PreferencesStore] 实例读写，读到的都是同一份值——
     * 修复了「MainViewModel 用新实例写入、Notifier 用单例读取，两边缓存互不可见」
     * 导致用户在设置页的修改（声音/震动/全屏）永远不生效的问题。
     */
    val notifyPreferences: NotifyPreferences
        get() = cacheNotifyPreferences

    // ---------------------------------------------------------------- 隐私同意

    /**
     * 读取隐私同意状态。
     *
     * 以 [PrivacyConsentStore]（SharedPreferences）为**权威来源**：
     * `Application.onCreate` 初始化高德 SDK 时读的就是它，
     * 两处若不一致会出现「用户已同意但 SDK 认为未同意」导致定位失败。
     */
    suspend fun readPrivacyAgreed(): Boolean {
        val agreed = PrivacyConsentStore.isAgreed(context) ||
            context.dataStore.data.map { it[KEY_PRIVACY_AGREED] ?: false }.first()
        isPrivacyAgreed = agreed
        return agreed
    }

    /**
     * 写入隐私同意状态。
     *
     * 同时写入 [PrivacyConsentStore]，保证冷启动时同步可读。
     */
    suspend fun setPrivacyAgreed(agreed: Boolean) {
        isPrivacyAgreed = agreed
        PrivacyConsentStore.setAgreed(context, agreed)
        context.dataStore.edit { it[KEY_PRIVACY_AGREED] = agreed }
    }

    // ---------------------------------------------------------------- 监控开关

    /** 用户是否期望围栏监听处于开启状态（用于开机/重启后恢复） */
    suspend fun readMonitoringEnabled(): Boolean =
        context.dataStore.data.map { it[KEY_MONITORING_ENABLED] ?: false }.first()

    /** 记录用户期望的监听开关状态 */
    suspend fun setMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_MONITORING_ENABLED] = enabled }
    }

    // ---------------------------------------------------------------- 提醒方式

    /** 读取提醒方式偏好 */
    suspend fun readNotifyPreferences(): NotifyPreferences =
        context.dataStore.data.map { prefs ->
            NotifyPreferences(
                sound = prefs[KEY_NOTIFY_SOUND] ?: true,
                vibrate = prefs[KEY_NOTIFY_VIBRATE] ?: true,
                fullScreen = prefs[KEY_NOTIFY_FULL_SCREEN] ?: true
            )
        }.first().also { cacheNotifyPreferences = it }

    /** 写入提醒方式偏好，并同步更新缓存供 Notifier 立即生效 */
    suspend fun setNotifyPreferences(prefs: NotifyPreferences) {
        cacheNotifyPreferences = prefs
        context.dataStore.edit { edit ->
            edit[KEY_NOTIFY_SOUND] = prefs.sound
            edit[KEY_NOTIFY_VIBRATE] = prefs.vibrate
            edit[KEY_NOTIFY_FULL_SCREEN] = prefs.fullScreen
        }
    }

    companion object {
        private val KEY_PRIVACY_AGREED = booleanPreferencesKey("privacy_agreed")
        private val KEY_MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        private val KEY_NOTIFY_SOUND = booleanPreferencesKey("notify_sound")
        private val KEY_NOTIFY_VIBRATE = booleanPreferencesKey("notify_vibrate")
        private val KEY_NOTIFY_FULL_SCREEN = booleanPreferencesKey("notify_full_screen")

        /**
         * 进程级单例。
         *
         * Notifier 需要在发通知前**同步**拿到提醒方式偏好，
         * 因此这里提供一个共享实例来持有最近一次读取到的值。
         */
        @Volatile
        private var instance: PreferencesStore? = null

        /**
         * 提醒方式偏好的**进程级缓存**（所有实例共享）。
         *
         * 说明：该缓存曾作为实例字段存在，导致 [MainViewModel] 用 `PreferencesStore(context)`
         * 构造新实例写入、而 [Notifier] 用 [get] 单例读取，两份缓存互不可见，
         * 用户在设置页修改的「声音/震动/全屏提醒」既不会即时生效也不会在冷启动后生效。
         * 提升为伴生对象字段后，从设计上杜绝了「多实例缓存不一致」。
         */
        @Volatile
        private var cacheNotifyPreferences: NotifyPreferences = NotifyPreferences()

        fun get(context: Context): PreferencesStore =
            instance ?: synchronized(this) {
                instance ?: PreferencesStore(context.applicationContext).also { instance = it }
            }

        /**
         * 预热提醒方式缓存。
         *
         * 供 [com.example.checkin.CheckinApp] 在进程启动时调用一次，避免进程被系统
         * 直接拉起（如开机自恢复）时，[Notifier] 在用户从未打开过设置页的情况下
         * 读到默认值而发错通知。
         */
        suspend fun prewarm(context: Context) {
            get(context).readNotifyPreferences()
        }
    }
}
