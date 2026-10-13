// 职责：隐私同意状态同步存取，供 Application 在冷启动时立即读取（高德 SDK 合规前置条件）
package com.example.checkin.util

import android.content.Context

/**
 * 隐私政策同意状态。
 *
 * **为什么单独用 SharedPreferences 而不是 DataStore**：
 * 高德 SDK 要求在**实例化任何定位/地图客户端之前**调用
 * `updatePrivacyShow` / `updatePrivacyAgree`，而该初始化发生在
 * `Application.onCreate()`——此处不能阻塞等待 DataStore（会卡启动甚至死锁）。
 * 因此这个「必须在启动瞬间同步读到」的标志放在 SharedPreferences 里。
 *
 * 曾出现过的问题：早期实现用 DataStore 的异步值配合一个内存缓存，
 * 导致**每次冷启动 `Application.onCreate` 读到的都是默认值 false**，
 * 于是高德 SDK 认为用户从未同意隐私政策，定位直接失败（错误码 27）。
 */
object PrivacyConsentStore {

    private const val PREF_NAME = "privacy_consent"
    private const val KEY_AGREED = "privacy_agreed"

    /** 同步读取是否已同意隐私政策（默认 false，合规优先） */
    fun isAgreed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AGREED, false)

    /** 同步写入同意状态 */
    fun setAgreed(context: Context, agreed: Boolean) {
        prefs(context).edit().putBoolean(KEY_AGREED, agreed).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
