// 职责：打卡记录 DAO，提供记录插入、按时间倒序查询与统计
package com.example.checkin.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.checkin.data.local.entity.CheckinRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckinRecordDao {

    // ---------------------------------------------------------------- 查询（时间倒序）

    /** 观察全部打卡记录，按时间倒序（记录页使用） */
    @Query("SELECT * FROM checkin_record ORDER BY time DESC")
    fun observeAllByTimeDesc(): Flow<List<CheckinRecord>>

    /** 记录总数 */
    @Query("SELECT COUNT(*) FROM checkin_record")
    fun observeCount(): Flow<Int>

    /**
     * 观察某个打卡点的记录（时间倒序）。
     *
     * 过滤条件**下推到 SQL**：打卡点详情页只关心单点历史，
     * 若在应用层用 `filter { it.pointId == id }` 过滤，会随着记录总量增长
     * 让每次发射都携带全表数据，既费内存又费 CPU。
     */
    @Query("SELECT * FROM checkin_record WHERE pointId = :pointId ORDER BY time DESC")
    fun observeByPointId(pointId: Long): Flow<List<CheckinRecord>>

    /** 按 id 取单条记录（编辑页回填使用） */
    @Query("SELECT * FROM checkin_record WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CheckinRecord?

    /** 一次性取全部记录（**非 Flow**），用于备份导出的一次性快照 */
    @Query("SELECT * FROM checkin_record ORDER BY id ASC")
    suspend fun getAllOnce(): List<CheckinRecord>

    /**
     * 取某打卡点**待确认**的记录。
     *
     * 「待确认」= 由围栏自动产生（source = AUTO_CONFIRMED）**且为进入事件**（is_enter = 1）。
     * 用户在打卡确认页点击「立即打卡」时，会把最新一条待确认记录改写为
     * 手动打卡并更新为当前时间与位置，避免同一次进入产生两条重复记录。
     *
     * ★ 必须同时过滤 is_enter：离开事件现在使用独立的 [CheckInSource.FENCE_EXIT] 来源，
     *   这里再加一道 SQL 层保险，防止历史遗留的「离开」记录被误当成待确认打卡而遭就地改写
     *   （那会导致历史里再也看不到「离开」、进出记录无法配对）。
     */
    @Query(
        """
        SELECT * FROM checkin_record
        WHERE pointId = :pointId AND source = 'AUTO_CONFIRMED' AND is_enter = 1
        ORDER BY time DESC LIMIT 1
        """
    )
    suspend fun latestPendingForPoint(pointId: Long): CheckinRecord?

    /** 确认一条待确认记录：更新为手动打卡、写入实际打卡时间与位置 */
    @Query(
        """
        UPDATE checkin_record
        SET time = :time, latitude = :latitude, longitude = :longitude,
            distance_meters = :distanceMeters, source = :source
        WHERE id = :id
        """
    )
    suspend fun confirmPending(
        id: Long,
        time: Long,
        latitude: Double,
        longitude: Double,
        distanceMeters: Double?,
        source: String
    )

    // ---------------------------------------------------------------- 写入

    /** 插入打卡记录，返回自增 id */
    @Insert
    suspend fun insert(record: CheckinRecord): Long

    /**
     * 插入或替换记录。
     *
     * ★ 用于「撤销删除」：删除时缓存了原始实体（含原 id），撤销时需要把它
     *   **按原 id 原样写回**。主键 autoGenerate 只在 id == 0 时才自动分配，
     *   传入非 0 id 会沿用该 id，因此这里用 REPLACE 冲突策略即可精确还原。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: CheckinRecord): Long

    /** 按主键整体更新一条记录（编辑备注/时间/位置时使用） */
    @Update
    suspend fun update(record: CheckinRecord)

    /** 批量插入记录（备份恢复使用，按原 id 写回） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<CheckinRecord>)

    /** 删除单条记录 */
    @Query("DELETE FROM checkin_record WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空全部记录 */
    @Query("DELETE FROM checkin_record")
    suspend fun deleteAll()
}
