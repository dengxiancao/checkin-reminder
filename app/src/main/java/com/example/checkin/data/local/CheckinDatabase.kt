// 职责：Room 数据库定义与单例入口，统一管理打卡点与打卡记录两张表
package com.example.checkin.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.checkin.data.local.dao.CheckinPointDao
import com.example.checkin.data.local.dao.CheckinRecordDao
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.data.local.entity.CheckinRecord

@Database(
    entities = [CheckinPoint::class, CheckinRecord::class],
    version = 3,
    exportSchema = true,
    /**
     * v2 -> v3：`checkin_record` 新增可空列 `note`（记录备注）。
     *
     * 纯「加可空列」是**唯一可以安全自动迁移**的变更类型，
     * Room 会生成 `ALTER TABLE checkin_record ADD COLUMN note TEXT`，
     * 既有数据完整保留（新列为 NULL）。
     *
     * 一旦将来出现重命名 / 类型变更 / 非空加列，就必须改用显式
     * `Migration(3, 4)` 并在此处 `addMigrations(...)` —— 自动迁移会直接编译报错，
     * 这正是我们想要的：宁可构建失败，也不要在用户设备上静默丢数据。
     */
    autoMigrations = [AutoMigration(from = 2, to = 3)]
)
@TypeConverters(Converters::class)
abstract class CheckinDatabase : RoomDatabase() {

    abstract fun checkinPointDao(): CheckinPointDao

    abstract fun checkinRecordDao(): CheckinRecordDao

    companion object {
        private const val DB_NAME = "checkin.db"

        @Volatile
        private var INSTANCE: CheckinDatabase? = null

        /**
         * 获取数据库单例。
         *
         * 前台服务与 UI 可能并发访问，用双重校验锁保证只有一个实例。
         */
        fun get(context: Context): CheckinDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CheckinDatabase::class.java,
                    DB_NAME
                )
                    /**
                     * v1 -> v2 表结构发生了重命名（check_point -> checkin_point、
                     * 字段 radius -> radiusMeters、新增 source/color 等），
                     * v1 属于开发阶段结构且无正式数据，因此 v1->v2 的升级无需迁移。
                     *
                     * ★ 安全策略（v1.6.0 起线上已有真实用户数据，务必遵守）：
                     * - 只对「**降级**」场景保留破坏性重建（用户装回旧版本，旧代码无法识别新表结构）；
                     * - 「**升级**」场景一旦缺少 Migration，Room 会**直接抛异常**并把问题暴露在启动阶段，
                     *   而不是静默删除整库、无提示地清空用户的全部打卡点与打卡记录。
                     *
                     * ★ 新增数据库版本时的正确做法（例如 v2 -> v3）：
                     *   1) 优先使用自动迁移：在 @Database 上加
                     *      `autoMigrations = [AutoMigration(from = 2, to = 3)]`（纯加列/加表可由 Room 生成）；
                     *   2) 复杂变更（重命名、类型变化）显式提供 `Migration(2, 3)` 并在此处
                     *      `.addMigrations(MIGRATION_2_3)` 注册。
                     */
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
