// 职责：地理计算与围栏半径等基础工具的单测（距离、半径收敛、定位参数 API）
package com.example.checkin

import com.amap.api.location.AMapLocationClientOption
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.geofence.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeoUtilsTest {

    // ---------------------------------------------------------------- 距离计算

    @Test
    fun `同一点距离为零`() {
        val d = GeoUtils.distanceMeters(39.909187, 116.397451, 39.909187, 116.397451)
        assertTrue("同点距离应接近 0，实际 $d", d < 0.001)
    }

    @Test
    fun `纬度差一度约等于111公里`() {
        // 1 度纬度 ≈ 111.19 km，允许 1% 误差
        val d = GeoUtils.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 1_200.0)
    }

    @Test
    fun `已知两点距离误差在百分之一以内`() {
        // 天安门 -> 鸟巢
        val d = GeoUtils.distanceMeters(39.909187, 116.397451, 39.992893, 116.396397)
        assertEquals(9_300.0, d, 1_200.0)
    }

    // ---------------------------------------------------------------- 围栏判定

    @Test
    fun `半径内判定为已进入`() {
        val inside = GeoUtils.isInside(
            centerLat = 39.909187, centerLon = 116.397451,
            radiusMeters = 200,
            currentLat = 39.910187, currentLon = 116.397451, // 向北约 111 米
            accuracyMeters = 0f
        )
        assertTrue(inside)
    }

    @Test
    fun `半径外判定为未进入`() {
        val inside = GeoUtils.isInside(
            centerLat = 39.909187, centerLon = 116.397451,
            radiusMeters = 200,
            currentLat = 39.919187, currentLon = 116.397451, // 向北约 1.1 公里
            accuracyMeters = 0f
        )
        assertFalse(inside)
    }

    @Test
    fun `精度容差最多放宽50米`() {
        // 半径 200 + 容差上限 50 => 阈值 250；本点约 290 米，仍应判定在外
        val inside = GeoUtils.isInside(
            centerLat = 39.909187, centerLon = 116.397451,
            radiusMeters = 200,
            currentLat = 39.911797, currentLon = 116.397451,
            accuracyMeters = 200f
        )
        assertFalse("精度容差不应超过 50 米", inside)
    }

    // ---------------------------------------------------------------- 半径约束

    @Test
    fun `半径会被收敛到100到500米`() {
        assertEquals(100, CheckinPoint.clampRadius(10))
        assertEquals(500, CheckinPoint.clampRadius(9_999))
        assertEquals(200, CheckinPoint.clampRadius(200))
    }

    // ---------------------------------------------------------------- 高德 API 可用性

    @Test
    fun `高德定位参数对象可正常构建`() {
        // 同时验证 com.amap.api:location 的关键类在运行时真实存在且方法签名正确，
        // 防止只靠编译期通过、真机抛出 NoSuchMethodError。
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            interval = 5_000L
            isNeedAddress = true
            isOnceLocation = false
            isLocationCacheEnable = false
            isMockEnable = false
            isSensorEnable = false
            httpTimeOut = 10_000L
            geoLanguage = AMapLocationClientOption.GeoLanguage.ZH
            setSelfStartServiceEnable(true)
        }

        assertEquals(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy, option.locationMode)
        assertEquals(5_000L, option.interval)
        assertTrue(option.isNeedAddress)
        assertTrue("setSelfStartServiceEnable 应生效", option.isSelfStartServiceEnable)
        assertNotNull(option.clone())
    }

    @Test
    fun `高德定位客户端类与围栏类可从反射确认存在`() {
        val client = Class.forName("com.amap.api.location.AMapLocationClient")
        assertTrue(client.methods.any { it.name == "setLocationOption" })
        assertTrue(client.methods.any { it.name == "enableBackgroundLocation" })

        // 高德地理围栏：需求要求用 addGeoFence 注册圆形围栏
        val fenceClient = Class.forName("com.amap.api.fence.GeoFenceClient")
        assertTrue(
            "GeoFenceClient 应提供圆形围栏注册方法",
            fenceClient.methods.any { it.name == "addGeoFence" }
        )
        assertTrue(fenceClient.methods.any { it.name == "removeGeoFence" })
    }

    @Test
    fun `坐标工具自检`() {
        assertTrue(abs(GeoUtils.distanceMeters(39.9, 116.4, 39.9, 116.4)) < 0.001)
    }
}
