// 职责：应用级依赖容器（轻量手写 DI），集中提供数据库与各仓库实例
package com.example.checkin.data.repository

import android.content.Context
import com.example.checkin.data.local.CheckinDatabase

/**
 * 简易服务定位器。
 *
 * 工程规模不大，手写容器即可避免引入 Hilt/Koin 带来的额外构建开销；
 * 所有实例都是进程内单例，前台服务与 UI 共享同一份数据。
 */
object ServiceLocator {

    @Volatile
    private var database: CheckinDatabase? = null

    private fun db(context: Context): CheckinDatabase =
        database ?: synchronized(this) {
            database ?: CheckinDatabase.get(context.applicationContext).also { database = it }
        }

    /**
     * 暴露数据库实例。
     *
     * 仅供需要**跨表事务**的场景使用（备份恢复要在同一事务里写
     * `checkin_point` 与 `checkin_record` 两张表）。普通增删改查请走下面的仓库。
     */
    fun database(context: Context): CheckinDatabase = db(context)

    /** 打卡点仓库 */
    fun checkinPointRepository(context: Context): CheckinPointRepository =
        CheckinPointRepository(db(context).checkinPointDao())

    /** 打卡记录仓库 */
    fun checkinRecordRepository(context: Context): CheckinRecordRepository =
        CheckinRecordRepository(db(context).checkinRecordDao())

    /** 备份/恢复仓库（跨表事务） */
    fun backupRepository(context: Context): BackupRepository =
        BackupRepository(db(context))
}
