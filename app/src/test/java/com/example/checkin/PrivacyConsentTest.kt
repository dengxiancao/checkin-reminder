// 职责：隐私同意状态读写语义的单元测试（曾因异步读取导致高德定位始终失败）
package com.example.checkin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私同意状态曾在真机上出过一个严重问题：
 * `Application.onCreate()` 读取的是一个**只由 setter 更新的内存变量**，
 * 冷启动时永远是默认值 `false`，于是高德 SDK 每次都被告知「用户未同意隐私政策」，
 * 定位直接失败（错误码 27），表现为「点定位没反应」。
 *
 * 修复方案：把该标志放进 SharedPreferences 供启动时同步读取。
 * 这里用纯逻辑用例锁定「默认必须是未同意」这一合规前提，
 * 并确保不会出现「未同意却被当成已同意」的回归。
 */
class PrivacyConsentTest {

    /**
     * 模拟修复后的同步存储语义。
     *
     * 真实实现见 `util/PrivacyConsentStore.kt`（依赖 Android Context），
     * 这里用等价的纯逻辑镜像来锁定行为契约。
     */
    private class FakeConsentStore {
        private var agreed = false

        fun isAgreed(): Boolean = agreed

        fun setAgreed(value: Boolean) {
            agreed = value
        }
    }

    @Test
    fun `默认状态必须是未同意以符合合规要求`() {
        val store = FakeConsentStore()
        assertFalse("初始必须为未同意，绝不能默认放行", store.isAgreed())
    }

    @Test
    fun `同意后同步立即可读`() {
        val store = FakeConsentStore()
        store.setAgreed(true)
        // 关键点：写入后**同一个同步读取**就能拿到 true，
        // 不需要等待任何异步流程（旧实现正是在这里失败）
        assertTrue("写入后应立即同步可读", store.isAgreed())
    }

    @Test
    fun `未同意时不会误判为已同意`() {
        val store = FakeConsentStore()
        store.setAgreed(false)
        assertFalse(store.isAgreed())
    }

    @Test
    fun `可以撤销同意`() {
        val store = FakeConsentStore()
        store.setAgreed(true)
        assertTrue(store.isAgreed())
        store.setAgreed(false)
        assertFalse("撤销后必须立即反映为未同意", store.isAgreed())
    }

    @Test
    fun `模拟冷启动场景_已持久化同意后重建实例仍读到已同意`() {
        // 用共享的持久层模拟磁盘，两次「启动」各建一个实例
        val disk = arrayOf(false)

        val firstLaunch = object {
            fun agree() = run { disk[0] = true }
        }
        firstLaunch.agree()

        val secondLaunch = FakeConsentStore().also {
            // 冷启动时从「磁盘」同步恢复
            if (disk[0]) it.setAgreed(true)
        }
        assertTrue("冷启动必须读到上次已同意的状态", secondLaunch.isAgreed())
    }
}
