// 职责：打卡点 DAO，提供插入/更新/删除、按启用状态查询与列表观察
package com.example.checkin.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.checkin.data.local.entity.CheckinPoint
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckinPointDao {

    // ---------------------------------------------------------------- 查询

    /** 观察全部打卡点，按创建时间倒序（列表页使用） */
    @Query("SELECT * FROM checkin_point ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CheckinPoint>>

    /**
     * 按启用状态查询。
     *
     * @param enabled true 只取启用的点（围栏注册用），false 只取停用的点
     */
    @Query("SELECT * FROM checkin_point WHERE enabled = :enabled ORDER BY createdAt DESC")
    suspend fun getByEnabled(enabled: Boolean): List<CheckinPoint>

    /** 按 id 查询 */
    @Query("SELECT * FROM checkin_point WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CheckinPoint?

    /**
     * 一次性取全部打卡点（**非 Flow**）。
     *
     * 用于「备份导出」这类一次读取快照的场景：Flow 会持续订阅数据库，
     * 而导出只需要当前时点的数据，用挂起函数更省资源。
     */
    @Query("SELECT * FROM checkin_point ORDER BY id ASC")
    suspend fun getAllOnce(): List<CheckinPoint>

    // ---------------------------------------------------------------- 写入

    /** 插入打卡点，返回自增 id */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(point: CheckinPoint): Long

    /** 批量插入打卡点（备份恢复使用，按原 id 写回） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<CheckinPoint>)

    /** 清空全部打卡点（备份恢复前先清库） */
    @Query("DELETE FROM checkin_point")
    suspend fun deleteAll()

    /** 更新打卡点 */
    @Update
    suspend fun update(point: CheckinPoint)

    /** 按实体删除 */
    @Delete
    suspend fun delete(point: CheckinPoint)

    // ---------------------------------------------------------------- 局部更新

    /** 切换启用状态 */
    @Query("UPDATE checkin_point SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())
}
