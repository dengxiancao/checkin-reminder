// 职责：打卡记录仓库，负责记录写入（手动/自动确认）与按时间倒序查询
package com.example.checkin.data.repository

import com.example.checkin.data.local.dao.CheckinRecordDao
import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.data.local.entity.CheckinRecord
import kotlinx.coroutines.flow.Flow

/**
 * 打卡记录数据仓库。
 *
 * 记录来源有两种：
 * - [CheckInSource.MANUAL]：用户在 App 内主动打卡；
 * - [CheckInSource.AUTO_CONFIRMED]：系统检测到进入范围后提醒，用户点击确认。
 */
class CheckinRecordRepository(private val dao: CheckinRecordDao) {

    /** 全部记录，按时间倒序 */
    val allRecordsByTimeDesc: Flow<List<CheckinRecord>> = dao.observeAllByTimeDesc()

    /** 记录总数 */
    val recordCount: Flow<Int> = dao.observeCount()

    // ---------------------------------------------------------------- 查询

    /** 某打卡点的记录（时间倒序），过滤在 SQL 层完成 */
    fun observeByPointId(pointId: Long): Flow<List<CheckinRecord>> =
        dao.observeByPointId(pointId)

    /** 按 id 取单条记录 */
    suspend fun getById(id: Long): CheckinRecord? = dao.getById(id)

    /** 一次性取全部记录（导出/备份用的一次性快照，不建立长期订阅） */
    suspend fun getAllOnce(): List<CheckinRecord> = dao.getAllOnce()

    /** 某打卡点最近一条待确认记录（围栏自动产生、尚未确认打卡） */
    suspend fun latestPendingForPoint(pointId: Long): CheckinRecord? =
        dao.latestPendingForPoint(pointId)

    // ---------------------------------------------------------------- 打卡确认

    /**
     * 完成一次打卡（用户点击「立即打卡」）。
     *
     * 语义：
     * - 若该打卡点存在**待确认**记录（围栏自动产生的进入/离开事件），
     *   则把它就地确认：写入真实打卡时间与实际位置，来源改为 [CheckInSource.MANUAL]。
     *   这样一次「进入 → 确认」只对应一条记录，不会重复；
     * - 若不存在待确认记录（用户从列表直接打卡），则新建一条手动记录。
     *
     * @return 落库的记录 id
     */
    suspend fun checkIn(
        point: CheckinPoint,
        latitude: Double,
        longitude: Double,
        distanceMeters: Double?
    ): Long {
        val time = System.currentTimeMillis()
        val pending = dao.latestPendingForPoint(point.id)
        return if (pending != null) {
            dao.confirmPending(
                id = pending.id,
                time = time,
                latitude = latitude,
                longitude = longitude,
                distanceMeters = distanceMeters,
                source = CheckInSource.MANUAL.name
            )
            pending.id
        } else {
            dao.insert(
                CheckinRecord(
                    pointId = point.id,
                    pointName = point.name,
                    latitude = latitude,
                    longitude = longitude,
                    time = time,
                    source = CheckInSource.MANUAL,
                    isEnter = null,
                    distanceMeters = distanceMeters
                )
            )
        }
    }

    // ---------------------------------------------------------------- 写入

    /**
     * 写入一条打卡记录。
     *
     * @param source 打卡来源，默认手动
     */
    suspend fun add(
        point: CheckinPoint,
        latitude: Double,
        longitude: Double,
        source: CheckInSource = CheckInSource.MANUAL,
        isEnter: Boolean? = null,
        distanceMeters: Double? = null,
        time: Long = System.currentTimeMillis()
    ): Long = dao.insert(
        CheckinRecord(
            pointId = point.id,
            // 名称做快照，打卡点改名或删除后历史仍可读
            pointName = point.name,
            latitude = latitude,
            longitude = longitude,
            time = time,
            source = source,
            isEnter = isEnter,
            distanceMeters = distanceMeters
        )
    )

    /** 直接插入记录实体 */
    suspend fun add(record: CheckinRecord): Long = dao.insert(record)

    /**
     * 按原 id 原样写回记录。
     *
     * 撤销删除时使用：删除前把实体整体缓存，撤销时用 REPLACE 策略写回，
     * 记录的 id / 时间 / 坐标 / 来源全部保持与删除前一致，
     * 因此统计与「进出区间配对」不会被撤销操作破坏。
     */
    suspend fun restore(record: CheckinRecord): Long = dao.upsert(record)

    /** 整体更新一条记录（编辑时间/坐标/来源时使用） */
    suspend fun update(record: CheckinRecord) = dao.update(record)

    /** 删除单条记录 */
    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /** 清空全部记录 */
    suspend fun clear() = dao.deleteAll()
}
