// 职责：定位来源映射与错误码文案的单元测试，保证 Debug 页展示与错误提示正确
package com.example.checkin

import com.amap.api.location.AMapLocation
import com.example.checkin.util.LocationError
import com.example.checkin.util.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSourceAndErrorTest {

    // ---------------------------------------------------------------- 定位来源映射

    @Test
    fun `GPS定位类型映射为gps`() {
        val source = LocationSource.fromAmapLocationType(AMapLocation.LOCATION_TYPE_GPS)
        assertEquals(LocationSource.GPS, source)
        assertEquals("gps", source.displayName)
        assertTrue("GPS 属于卫星定位", source.isSatellite)
    }

    @Test
    fun `WiFi与基站类型映射为network`() {
        listOf(
            AMapLocation.LOCATION_TYPE_WIFI,
            AMapLocation.LOCATION_TYPE_CELL,
            AMapLocation.LOCATION_TYPE_AMAP,
            AMapLocation.LOCATION_TYPE_NETWORK,
            AMapLocation.LOCATION_TYPE_COARSE_LOCATION
        ).forEach { type ->
            val source = LocationSource.fromAmapLocationType(type)
            assertEquals("locationType=$type 应映射为 network", LocationSource.NETWORK, source)
            assertFalse(source.isSatellite)
        }
    }

    @Test
    fun `缓存类类型映射为cache`() {
        listOf(
            AMapLocation.LOCATION_TYPE_FAST,
            AMapLocation.LOCATION_TYPE_FIX_CACHE,
            AMapLocation.LOCATION_TYPE_LAST_LOCATION_CACHE,
            AMapLocation.LOCATION_TYPE_OFFLINE
        ).forEach { type ->
            assertEquals(
                "locationType=$type 应映射为 cache",
                LocationSource.CACHE,
                LocationSource.fromAmapLocationType(type)
            )
        }
    }

    @Test
    fun `未知类型映射为unknown`() {
        assertEquals(LocationSource.UNKNOWN, LocationSource.fromAmapLocationType(-999))
        assertEquals(LocationSource.UNKNOWN, LocationSource.fromAmapLocationType(0))
    }

    @Test
    fun `所有来源都有可读名称`() {
        LocationSource.entries.forEach { source ->
            assertTrue("${source.name} 缺少 displayName", source.displayName.isNotBlank())
        }
    }

    // ---------------------------------------------------------------- 错误码文案

    @Test
    fun `成功错误码文案为定位成功`() {
        assertEquals("定位成功", LocationError.readableMessage(LocationError.SUCCESS))
    }

    @Test
    fun `关键错误码都有专门的中文提示`() {
        // 这些是集成与使用中最常见的错误码，必须有针对性提示而不是落到兜底文案
        val keyCodes = listOf(12, 13, 14, 18, 25, 26, 27)
        keyCodes.forEach { code ->
            val message = LocationError.readableMessage(code)
            assertFalse(
                "错误码 $code 落到了兜底文案：$message",
                message.contains("错误码 $code")
            )
            assertTrue("错误码 $code 提示不应为空", message.isNotBlank())
        }
    }

    @Test
    fun `缺少定位权限错误码提示包含权限字样`() {
        assertTrue(LocationError.readableMessage(12).contains("权限"))
    }

    @Test
    fun `Key校验失败错误码提示包含Key与SHA1`() {
        val message = LocationError.readableMessage(26)
        assertTrue("应提示 Key 问题，实际：$message", message.contains("Key"))
        assertTrue("应提示 SHA1，实际：$message", message.contains("SHA1"))
    }

    @Test
    fun `未知错误码走兜底文案并带上错误码`() {
        val message = LocationError.readableMessage(9999)
        assertTrue("兜底文案应包含错误码，实际：$message", message.contains("9999"))
    }

    @Test
    fun `from会保留原始错误码与描述`() {
        val error = LocationError.from(26, "invalid api key")
        assertEquals(26, error.errorCode)
        assertEquals("invalid api key", error.errorInfo)
        assertTrue(error.message.contains("Key"))
    }

    @Test
    fun `from允许errorInfo为空`() {
        val error = LocationError.from(20, null)
        assertEquals(20, error.errorCode)
        assertEquals("", error.errorInfo)
        assertNotNull(error.message)
    }

    // ---------------------------------------------------------------- 鉴权失败（真机实测）

    @Test
    fun `鉴权失败描述会覆盖笼统错误码文案`() {
        // 真机实测：签名 SHA1 未在高德控制台登记时，SDK 返回的是 code=8（其他错误），
        // 真正的线索藏在 errorInfo 的 INVALID_USER_SCODE 里，必须优先给出可操作提示。
        val info = "其他错误 请到http://lbs.amap.com/api/android-location-sdk/guide/utilities/" +
            "errorcode/查看错误码说明,错误详细信息:auth fail:INVALID_USER_SCODE#SHA1AndPackage#" +
            "0F:65:D5:1D:32:55:1B:46:18:D0:7F:C9:9F:58:11:D1:A5:27:0C:13:com.example.checkin#gsid#123"
        val message = LocationError.readableMessage(8, info)
        assertTrue("应提示鉴权失败，实际：$message", message.contains("鉴权失败"))
        assertTrue("应提示 SHA1，实际：$message", message.contains("SHA1"))
        assertFalse("不应落到「未知错误」兜底：$message", message.contains("未知错误"))
    }

    @Test
    fun `鉴权失败可被单独识别`() {
        assertTrue(LocationError.isAuthFailure("auth fail:INVALID_USER_SCODE#SHA1AndPackage#x"))
        assertTrue(LocationError.isAuthFailure("AUTH FAIL: something"))
        assertFalse(LocationError.isAuthFailure("invalid api key"))
        assertFalse(LocationError.isAuthFailure(null))
    }

    @Test
    fun `无鉴权线索时仍按错误码文案`() {
        assertEquals("未知错误", LocationError.readableMessage(8))
        assertEquals("未知错误", LocationError.readableMessage(8, "网络抖动"))
    }

    @Test
    fun `from在鉴权失败时给出可操作提示`() {
        val error = LocationError.from(8, "auth fail:INVALID_USER_SCODE#SHA1AndPackage#abc")
        assertEquals(8, error.errorCode)
        assertTrue("应指向高德控制台，实际：${error.message}", error.message.contains("高德控制台"))
    }

    // ---------------------------------------------------------------- 超时错误码不冲突

    @Test
    fun `自定义超时错误码与高德错误码区间不冲突`() {
        // 高德错误码为 0~31，自定义值必须为负数
        assertTrue(com.example.checkin.util.Locator.ERROR_TIMEOUT < 0)
        assertTrue(com.example.checkin.geofence.AMapLocationEngine.ERROR_START_FAILED < 0)
        assertTrue(
            com.example.checkin.util.Locator.ERROR_TIMEOUT !=
                com.example.checkin.geofence.AMapLocationEngine.ERROR_START_FAILED
        )
    }

    @Test
    fun `默认定位参数符合需求约定`() {
        // 需求：定位间隔默认 5000ms，超时 10 秒
        assertEquals(5_000L, com.example.checkin.util.Locator.DEFAULT_INTERVAL_MS)
        assertEquals(10_000L, com.example.checkin.util.Locator.DEFAULT_TIMEOUT_MS)
    }
}
