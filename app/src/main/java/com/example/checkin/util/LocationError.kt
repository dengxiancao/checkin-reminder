// 职责：定位错误封装，把高德 errorCode 转成可读中文提示并统一打印日志
package com.example.checkin.util

import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * 定位错误。
 *
 * 高德错误码分段规则（官方错误码对照表）：
 * - 0        定位成功
 * - 1~11     参数/网络/协议类错误（多为集成或网络问题）
 * - 12       缺少定位权限
 * - 13       WIFI 与基站信息获取失败
 * - 14       网络超时
 * - 15       网络中断
 * - 18       定位服务未开启（GPS 开关关闭）
 * - 20       定位失败（无有效结果）
 * - 21~22    网络异常
 * - 23       传感器异常
 * - 24       网络连接失败
 * - 25       定位权限被拒绝 / 无法获取位置
 * - 26       签名或 Key 校验失败（含包名/SHA1 不匹配）
 * - 27       定位服务未初始化 / 隐私合规未同意
 * - 28~31    高德服务端或数据错误
 *
 * ⚠️ 实测补充（2026-10-13，真机小米 11 / Android 13 / 定位 SDK V11.3.101）：
 * 「签名 SHA1 与高德控制台登记不一致」时，SDK **不会**返回 26，而是返回
 * `code=8` + errorInfo 内含 `auth fail:INVALID_USER_SCODE#SHA1AndPackage#<SHA1>#<包名>`。
 * 因此 [LocationError.readableMessage] 会优先按 errorInfo 判定鉴权失败，而不是只看错误码。
 */
data class LocationError(
    val errorCode: Int,
    val errorInfo: String,
    val message: String
) {
    companion object {

        /** 定位成功 */
        const val SUCCESS = 0

        /** 未在本表中登记的错误码统一前缀 */
        private const val TAG = "LocationError"

        /**
         * 根据错误码生成可读错误信息。
         *
         * @param errorCode 高德返回的错误码（0 表示成功）
         * @param errorInfo 高德返回的原始描述，可为空
         */
        fun from(errorCode: Int, errorInfo: String?): LocationError {
            val readable = readableMessage(errorCode, errorInfo)
            // 需求要求「打印错误码」：这里统一在错误产生点输出日志
            Log.e(
                TAG,
                "定位失败 errorCode=$errorCode, errorInfo=${errorInfo ?: "(空)"}, 可读提示=$readable"
            )
            return LocationError(
                errorCode = errorCode,
                errorInfo = errorInfo.orEmpty(),
                message = readable
            )
        }

        /**
         * 错误码 -> 可读文案（纯函数，便于单测）。
         *
         * 注意：高德在「鉴权失败」时错误码往往只给一个笼统值（实测新版定位 SDK 会返回
         * code=8「其他错误」），真正的线索藏在 errorInfo 里，形如：
         * `auth fail:INVALID_USER_SCODE#SHA1AndPackage#<当前SHA1>#<包名>#...`
         * 这种场景必须按 errorInfo 判定，否则用户只会看到一句「未知错误」而完全无从下手。
         *
         * @param errorCode 高德返回的错误码（0 表示成功）
         * @param errorInfo 高德返回的原始描述，可为空
         */
        fun readableMessage(errorCode: Int, errorInfo: String? = null): String {
            if (isAuthFailure(errorInfo)) {
                return "高德鉴权失败：Key / 包名 / 签名 SHA1 三者不匹配。" +
                    "请到高德控制台为本包名补登当前签名 SHA1（更换签名密钥后必须补登）"
            }
            return when (errorCode) {
                SUCCESS -> "定位成功"

                // 参数与网络类
                1 -> "定位参数错误，请检查定位参数设置"
                2 -> "网络请求失败，请检查网络连接"
                3 -> "定位请求响应超时，请到信号较好的位置重试"
                4 -> "解析定位结果失败"
                5 -> "解析到的坐标为空"
                6 -> "定位结果错误"
                7 -> "定位 Key 校验异常"
                8 -> "未知错误"
                9 -> "初始化异常"
                10 -> "定位服务启动失败"
                11 -> "网络请求被拒绝"

                12 -> "缺少定位权限，请在系统设置中允许定位"

                // 网络与信号类
                13 -> "WIFI 与基站信息获取失败，请开启 WiFi 或到室外重试"
                14 -> "网络超时，请检查网络连接"
                15 -> "网络中断，请检查网络连接"
                16 -> "无法获取当前位置，请检查设备定位功能"
                17 -> "定位结果为空"
                18 -> "系统定位服务未开启，请在设置中打开定位（GPS）开关"
                19 -> "定位失败，请稍后重试"
                20 -> "定位失败，未获取到有效结果"

                21 -> "网络异常，请检查网络连接"
                22 -> "网络异常，连接失败"
                23 -> "设备传感器异常，请重启设备后重试"
                24 -> "网络连接失败，请检查网络"
                25 -> "定位权限被拒绝或获取位置失败，请重新授权"

                // 集成类（最常见）
                26 -> "定位 Key 校验失败：请确认高德 Key 的包名与 SHA1 与本应用一致"
                27 -> "定位服务未初始化或未同意隐私政策，请重启应用后重试"
                28 -> "定位服务返回异常，请稍后重试"
                29 -> "定位服务返回异常，请稍后重试"
                30 -> "定位服务返回异常，请稍后重试"
                31 -> "定位服务连接失败，请稍后重试"

                else -> "定位失败（错误码 $errorCode），请稍后重试"
            }
        }

        /**
         * 判断高德返回的描述是否属于「鉴权类失败」。
         *
         * 高德在不同版本/不同 SDK 下把这类失败归到 7 或 8，错误码不可靠，
         * 但描述里始终带有 `INVALID_USER_SCODE` 或 `auth fail`，故按描述判定。
         */
        @VisibleForTesting
        internal fun isAuthFailure(errorInfo: String?): Boolean {
            val info = errorInfo ?: return false
            return info.contains("INVALID_USER_SCODE", ignoreCase = true) ||
                info.contains("auth fail", ignoreCase = true)
        }
    }
}
