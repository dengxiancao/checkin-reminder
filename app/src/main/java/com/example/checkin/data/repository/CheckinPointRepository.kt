// 职责：打卡点仓库，对上层屏蔽 Room 细节，统一半径校验与时间戳维护
package com.example.checkin.data.repository

import com.example.checkin.data.local.dao.CheckinPointDao
import com.example.checkin.data.local.entity.CheckinPoint
import kotlinx.coroutines.flow.Flow

/**
 * 打卡点数据仓库。
 *
 * 所有写操作都经过这里，保证：
 * 1. 半径始终被 [CheckinPoint.clampRadius] 收敛到 100~500 米；
 * 2. `updatedAt` 由仓库统一维护，UI 层无需关心；
 * 3. 返回自增 id，便于新增后立即定位到该打卡点。
 */
class CheckinPointRepository(private val dao: CheckinPointDao) {

    /** 观察全部打卡点（按创建时间倒序） */
    val allPoints: Flow<List<CheckinPoint>> = dao.observeAll()

    // ---------------------------------------------------------------- 查询

    /** 取启用中的打卡点（围栏注册使用） */
    suspend fun getEnabledPoints(): List<CheckinPoint> = dao.getByEnabled(true)

    /** 按 id 查询 */
    suspend fun getById(id: Long): CheckinPoint? = dao.getById(id)

    /** 一次性取全部打卡点（导出/备份用的一次性快照） */
    suspend fun getAllOnce(): List<CheckinPoint> = dao.getAllOnce()

    // ---------------------------------------------------------------- 写入

    /**
     * 新增或更新打卡点。
     *
     * id 为 0 视为新增（返回新 id），否则视为更新（返回原 id）。
     */
    suspend fun save(point: CheckinPoint): Long {
        val normalized = point.copy(
            radiusMeters = CheckinPoint.clampRadius(point.radiusMeters),
            updatedAt = System.currentTimeMillis()
        )
        return if (normalized.id == 0L) {
            dao.insert(normalized)
        } else {
            dao.update(normalized)
            normalized.id
        }
    }

    /** 按实体删除（外键级联会一并删除其打卡记录） */
    suspend fun delete(point: CheckinPoint) = dao.delete(point)

    /** 启用 / 停用 */
    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)
}
