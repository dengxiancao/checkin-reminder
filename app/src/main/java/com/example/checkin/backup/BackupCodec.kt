// 职责：备份文件编解码（实体 <-> JSON），零新增依赖
package com.example.checkin.backup

import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinPoint
import com.example.checkin.data.local.entity.CheckinRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 备份中的用户偏好 */
data class BackupPrefs(
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val fullScreen: Boolean = true
)

/** 一次完整的备份快照 */
data class BackupSnapshot(
    val exportedAt: Long,
    val schemaVersion: Int = BackupCodec.SCHEMA_VERSION,
    val points: List<CheckinPoint>,
    val records: List<CheckinRecord>,
    val prefs: BackupPrefs = BackupPrefs(),
    /** 解码时被丢弃的孤立记录数（其 pointId 在 points 中不存在，写回会违反外键） */
    val droppedRecords: Int = 0
)

/**
 * 备份文件编解码器。
 *
 * 采用**显式字段映射**而不是反射式序列化，原因：
 * 1. 备份格式一旦发布就要长期向后兼容，字段名必须受控、不能被重构意外改掉；
 * 2. 显式映射能让「缺少字段」「类型不符」「版本不匹配」都给出可读错误，
 *    而不是在恢复时抛出难以理解的反射异常。
 *
 * 版本策略：[SCHEMA_VERSION] 递增表示格式发生**不兼容**变化。
 * 恢复时若发现版本高于当前支持版本，**拒绝恢复**并提示用户升级应用，
 * 避免用旧逻辑错误解读新数据（那会造成静默的数据损坏）。
 */
object BackupCodec {

    /** 当前备份格式版本 */
    const val SCHEMA_VERSION: Int = 1

    /** 备份文件标识，用于确认这是本应用的备份 */
    const val APP_ID: String = "com.example.checkin"

    private val FILE_STAMP_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmm", Locale.CHINA)

    /** 备份文件格式不受支持（通常是备份来自更高版本的应用） */
    class UnsupportedVersionException(val found: Int) :
        Exception("备份文件版本为 $found，当前应用仅支持 $SCHEMA_VERSION 及以下。请升级应用后再恢复。")

    /** 备份文件损坏或不是本应用导出的 */
    class MalformedBackupException(message: String) : Exception(message)

    // ---------------------------------------------------------------- 编码

    fun encode(snapshot: BackupSnapshot): String {
        val sb = StringBuilder(1024)
        sb.append("{\n")
        sb.append("  \"app\": ").append(Json.quote(APP_ID)).append(",\n")
        sb.append("  \"schemaVersion\": ").append(snapshot.schemaVersion).append(",\n")
        sb.append("  \"exportedAt\": ").append(snapshot.exportedAt).append(",\n")

        sb.append("  \"prefs\": {")
        sb.append("\"sound\": ").append(snapshot.prefs.sound).append(", ")
        sb.append("\"vibrate\": ").append(snapshot.prefs.vibrate).append(", ")
        sb.append("\"fullScreen\": ").append(snapshot.prefs.fullScreen)
        sb.append("},\n")

        sb.append("  \"points\": [")
        sb.append(snapshot.points.joinToString(separator = ",") { encodePoint(it) })
        sb.append("],\n")

        sb.append("  \"records\": [")
        sb.append(snapshot.records.joinToString(separator = ",") { encodeRecord(it) })
        sb.append("]\n")

        sb.append("}\n")
        return sb.toString()
    }

    private fun encodePoint(point: CheckinPoint): String = buildString {
        append("\n    {")
        append("\"id\": ").append(point.id).append(", ")
        append("\"name\": ").append(Json.quote(point.name)).append(", ")
        append("\"latitude\": ").append(Json.number(point.latitude)).append(", ")
        append("\"longitude\": ").append(Json.number(point.longitude)).append(", ")
        append("\"radiusMeters\": ").append(point.radiusMeters).append(", ")
        append("\"enabled\": ").append(point.enabled).append(", ")
        append("\"createdAt\": ").append(point.createdAt).append(", ")
        append("\"updatedAt\": ").append(point.updatedAt).append(", ")
        append("\"color\": ").append(point.color?.let(Json::quote) ?: "null").append(", ")
        append("\"notifyOnEnter\": ").append(point.notifyOnEnter).append(", ")
        append("\"notifyOnExit\": ").append(point.notifyOnExit)
        append("}")
    }

    private fun encodeRecord(record: CheckinRecord): String = buildString {
        append("\n    {")
        append("\"id\": ").append(record.id).append(", ")
        append("\"pointId\": ").append(record.pointId).append(", ")
        append("\"pointName\": ").append(Json.quote(record.pointName)).append(", ")
        append("\"latitude\": ").append(Json.number(record.latitude)).append(", ")
        append("\"longitude\": ").append(Json.number(record.longitude)).append(", ")
        append("\"time\": ").append(record.time).append(", ")
        append("\"source\": ").append(Json.quote(record.source.name)).append(", ")
        append("\"isEnter\": ").append(record.isEnter?.toString() ?: "null").append(", ")
        append("\"distanceMeters\": ")
        append(record.distanceMeters?.let(Json::number) ?: "null").append(", ")
        append("\"note\": ").append(record.note?.let(Json::quote) ?: "null")
        append("}")
    }

    // ---------------------------------------------------------------- 解码

    /**
     * 解码备份文件。
     *
     * @throws UnsupportedVersionException 版本高于当前支持
     * @throws MalformedBackupException    不是本应用备份 / JSON 损坏 / 必填字段缺失
     */
    fun decode(text: String): BackupSnapshot {
        val root = try {
            Json.parse(text)
        } catch (e: Json.JsonException) {
            throw MalformedBackupException("备份文件不是合法的 JSON：${e.message}")
        }

        val map = root as? Map<*, *> ?: throw MalformedBackupException("备份文件顶层应为 JSON 对象")

        val app = map["app"] as? String
        if (app != null && app != APP_ID) {
            throw MalformedBackupException("该文件不是本应用导出的备份（应用标识为 $app）")
        }

        val version = (map["schemaVersion"] as? Double)?.toInt() ?: SCHEMA_VERSION
        if (version > SCHEMA_VERSION) throw UnsupportedVersionException(version)

        val exportedAt = (map["exportedAt"] as? Double)?.toLong() ?: 0L

        val points = (map["points"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map { decodePoint(it) }

        val validPointIds = points.mapTo(HashSet()) { it.id }

        val rawRecords = (map["records"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
            .map { decodeRecord(it) }

        // 外键保护：pointId 不存在的记录写回时会触发 FOREIGN KEY 约束失败，
        // 这里先行剔除并计数上报，避免整次恢复因个别脏数据而中断。
        val records = rawRecords.filter { it.pointId in validPointIds }

        val prefsMap = map["prefs"] as? Map<*, *>
        val prefs = BackupPrefs(
            sound = prefsMap?.get("sound") as? Boolean ?: true,
            vibrate = prefsMap?.get("vibrate") as? Boolean ?: true,
            fullScreen = prefsMap?.get("fullScreen") as? Boolean ?: true
        )

        return BackupSnapshot(
            exportedAt = exportedAt,
            schemaVersion = version,
            points = points,
            records = records,
            prefs = prefs,
            droppedRecords = rawRecords.size - records.size
        )
    }

    private fun decodePoint(map: Map<*, *>): CheckinPoint {
        val name = map["name"] as? String
            ?: throw MalformedBackupException("备份中的打卡点缺少 name 字段")

        return CheckinPoint(
            id = map.long("id"),
            name = name,
            latitude = map.double("latitude"),
            longitude = map.double("longitude"),
            radiusMeters = CheckinPoint.clampRadius(map.long("radiusMeters", 200L).toInt()),
            enabled = map.bool("enabled", true),
            createdAt = map.long("createdAt"),
            color = map["color"] as? String,
            notifyOnEnter = map.bool("notifyOnEnter", true),
            notifyOnExit = map.bool("notifyOnExit", true),
            updatedAt = map.long("updatedAt")
        )
    }

    private fun decodeRecord(map: Map<*, *>): CheckinRecord {
        val pointName = map["pointName"] as? String
            ?: throw MalformedBackupException("备份中的记录缺少 pointName 字段")

        val source = (map["source"] as? String)
            ?.let { runCatching { CheckInSource.valueOf(it) }.getOrNull() }
            ?: CheckInSource.MANUAL

        return CheckinRecord(
            id = map.long("id"),
            pointId = map.long("pointId"),
            pointName = pointName,
            latitude = map.double("latitude"),
            longitude = map.double("longitude"),
            time = map.long("time"),
            source = source,
            isEnter = map["isEnter"] as? Boolean,
            distanceMeters = (map["distanceMeters"] as? Double),
            // 空字符串与缺失都归一化为 null，避免出现「有备注但内容为空」的脏数据
            note = (map["note"] as? String)?.takeIf { it.isNotBlank() }
        )
    }

    /** 由时间戳生成备份文件名 */
    fun suggestFileName(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val stamp = Instant.ofEpochMilli(timestamp).atZone(zoneId).format(FILE_STAMP_FMT)
        return "checkin_backup_$stamp.json"
    }

    // ---------------------------------------------------------------- 取值辅助

    private fun Map<*, *>.long(key: String, default: Long = 0L): Long =
        (this[key] as? Double)?.toLong() ?: default

    private fun Map<*, *>.double(key: String, default: Double = 0.0): Double =
        (this[key] as? Double) ?: default

    private fun Map<*, *>.bool(key: String, default: Boolean): Boolean =
        (this[key] as? Boolean) ?: default
}
