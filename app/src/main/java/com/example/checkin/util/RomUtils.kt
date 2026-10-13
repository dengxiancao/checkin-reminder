// 职责：国产 ROM 识别，用于在设置页给出对应厂商的后台权限引导步骤
package com.example.checkin.util

import android.os.Build
import java.lang.reflect.Method
import java.util.Locale

/** 主流国产 ROM 厂商 */
enum class RomVendor(val displayName: String) {
    MIUI("小米 / 红米（MIUI / HyperOS）"),
    EMUI("华为（EMUI / HarmonyOS）"),
    HONOR("荣耀（MagicOS）"),
    COLOR_OS("OPPO / 一加 / realme（ColorOS）"),
    ORIGIN_OS("vivo / iQOO（OriginOS / Funtouch）"),
    FLYME("魅族（Flyme）"),
    SAMSUNG("三星（One UI）"),
    OTHER("其他 Android 设备")
}

/**
 * ROM 识别工具。
 *
 * 识别依据是厂商定制的系统属性（ro.build.version.emui、ro.miui.ui.version.name 等），
 * 这些属性并非官方 API，不同机型/版本可能缺失，因此每一项都做了容错，
 * 识别不出时降级为 [RomVendor.OTHER] 并给出通用步骤。
 */
object RomUtils {

    /** 当前设备 ROM 厂商（结果缓存，属性读取有一定开销） */
    val vendor: RomVendor by lazy { detect() }

    private fun detect(): RomVendor {
        // 华为（含 HarmonyOS）
        if (hasProp("ro.build.version.emui") || hasProp("hw_sc.build.platform.version")) {
            // 荣耀在独立后仍可能带 hw 前缀属性，需先判荣耀
            return if (isHonor()) RomVendor.HONOR else RomVendor.EMUI
        }
        if (isHonor()) return RomVendor.HONOR

        // 小米
        if (hasProp("ro.miui.ui.version.name") || hasProp("ro.miui.ui.version.code")) {
            return RomVendor.MIUI
        }

        // OPPO 系
        if (hasProp("ro.build.version.opporom") || hasProp("ro.oppo.theme.version")) {
            return RomVendor.COLOR_OS
        }

        // vivo 系
        if (hasProp("ro.vivo.os.version") || hasProp("ro.vivo.product.overseas")) {
            return RomVendor.ORIGIN_OS
        }

        // 魅族
        if (hasProp("ro.flyme.published") || hasProp("ro.meizu.setupwizard.flyme")) {
            return RomVendor.FLYME
        }

        // 三星
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        if (manufacturer.contains("samsung")) return RomVendor.SAMSUNG

        return RomVendor.OTHER
    }

    /** 是否为荣耀设备（荣耀独立后 MANUFACTURER 变为 HONOR） */
    private fun isHonor(): Boolean {
        val brand = (Build.BRAND + Build.MANUFACTURER).lowercase(Locale.ROOT)
        return brand.contains("honor")
    }

    /** 读取系统属性（失败返回 null，不抛异常） */
    private fun getProp(key: String): String? = runCatching {
        val clazz: Class<*> = Class.forName("android.os.SystemProperties")
        val get: Method = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun hasProp(key: String): Boolean = getProp(key) != null
}
