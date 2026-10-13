// 职责：备份导出与恢复（跨表事务，保证恢复的原子性）
package com.example.checkin.data.repository

import androidx.room.withTransaction
import com.example.checkin.backup.BackupPrefs
import com.example.checkin.backup.BackupSnapshot
import com.example.checkin.data.local.CheckinDatabase

/** 恢复结果统计 */
data class RestoreOutcome(
    val pointsRestored: Int,
    val recordsRestored: Int
)

/**
 * 备份仓库。
 *
 * 恢复必须**在单个数据库事务内**完成「清库 + 回填」：
 * 若中途失败（磁盘满、数据非法），事务回滚，用户看到的是「恢复失败，数据未变」，
 * 而不是一个被清空了一半的数据库 —— 后者对打卡类应用等于数据永久丢失。
 *
 * 写入顺序受外键约束：
 * - 删除：先 `checkin_record`（子表）后 `checkin_point`（父表），否则父表删除会被外键拒绝；
 * - 插入：先 `checkin_point`（父表）后 `checkin_record`（子表），否则子表插入会找不到父行。
 */
class BackupRepository(private val database: CheckinDatabase) {

    private val pointDao get() = database.checkinPointDao()
    private val recordDao get() = database.checkinRecordDao()

    /** 导出一份完整快照（一次性读取，不建立长期订阅） */
    suspend fun export(prefs: BackupPrefs): BackupSnapshot = BackupSnapshot(
        exportedAt = System.currentTimeMillis(),
        points = pointDao.getAllOnce(),
        records = recordDao.getAllOnce(),
        prefs = prefs
    )

    /**
     * 用备份快照**整体覆盖**当前数据。
     *
     * ⚠️ 这是破坏性操作：会先清空全部打卡点与记录，再写入备份内容。
     * 调用方必须先向用户确认。
     */
    suspend fun restore(snapshot: BackupSnapshot): RestoreOutcome =
        database.withTransaction {
            recordDao.deleteAll()
            pointDao.deleteAll()
            pointDao.insertAll(snapshot.points)
            if (snapshot.records.isNotEmpty()) {
                recordDao.insertAll(snapshot.records)
            }
            RestoreOutcome(
                pointsRestored = snapshot.points.size,
                recordsRestored = snapshot.records.size
            )
        }
}
