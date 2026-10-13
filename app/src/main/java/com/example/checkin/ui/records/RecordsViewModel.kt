// 职责：打卡记录页 ViewModel —— 记录增删改、撤销、统计聚合、CSV 导出与备份恢复
package com.example.checkin.ui.records

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.checkin.R
import com.example.checkin.backup.BackupCodec
import com.example.checkin.backup.BackupPrefs
import com.example.checkin.backup.CsvExporter
import com.example.checkin.data.local.entity.CheckInSource
import com.example.checkin.data.local.entity.CheckinRecord
import com.example.checkin.data.repository.CheckinRecordRepository
import com.example.checkin.data.repository.RestoreOutcome
import com.example.checkin.data.repository.ServiceLocator
import com.example.checkin.stats.DailyPointStat
import com.example.checkin.stats.StatsCalculator
import com.example.checkin.util.PreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 记录页的提示消息；[undoable] 为 true 时界面需要展示「撤销」动作。
 *
 * [seq] 是**单调递增的序号**，不是可有可无的装饰：
 * 消息通道是 `MutableStateFlow`，它对**相等**的新值会直接合并、不发射。
 * 而连续删两条记录时，文案完全相同（「已删除该条记录」），若没有 seq，
 * 第二条删除的消息会被静默吞掉 —— 用户看不到 Snackbar，也就失去了「撤销」入口。
 * 因此每条消息都必须带上唯一序号，保证「投递即发射」。
 */
data class RecordsMessage(
    val text: String,
    val undoable: Boolean = false,
    val seq: Long = 0L
)

/** 记录页的三个视图 */
enum class RecordsView { TIMELINE, CALENDAR, STATS }

/** 统计页的时间粒度 */
enum class StatsRange { DAY, WEEK, MONTH }

/**
 * 记录页 ViewModel。
 *
 * 从 [com.example.checkin.ui.MainViewModel] 中独立出来，原因有二：
 * 1. **职责单一**：MainViewModel 负责打卡点与保活监控，本类负责记录数据及其派生视图
 *    （统计、日历、导出），两者的订阅生命周期与依赖完全不同；
 * 2. **可测性**：记录侧的纯逻辑（排序、聚合、撤销缓冲）不依赖监测开关等状态，
 *    单独成类后更容易被覆盖，也避免 MainViewModel 继续膨胀。
 */
class RecordsViewModel(application: Application) : AndroidViewModel(application) {

    private val recordRepository: CheckinRecordRepository =
        ServiceLocator.checkinRecordRepository(application)

    private val backupRepository = ServiceLocator.backupRepository(application)
    private val preferencesStore = PreferencesStore.get(application)

    /** 全部记录，按时间倒序 */
    val records: StateFlow<List<CheckinRecord>> = recordRepository.allRecordsByTimeDesc
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyList()
        )

    /**
     * 按日期聚合的统计结果。
     *
     * 聚合在 [StatsCalculator] 中以纯函数完成，这里只做「记录列表 -> 统计」的映射。
     * 用 `WhileSubscribed` 而不是 `Eagerly`：记录页不可见时不保留整张统计表。
     */
    val dailyStats: StateFlow<Map<LocalDate, List<DailyPointStat>>> = records
        .map { StatsCalculator.calculate(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = emptyMap()
        )

    // ---------------------------------------------------------------- 视图状态

    private val _view = MutableStateFlow(RecordsView.TIMELINE)
    val view: StateFlow<RecordsView> = _view.asStateFlow()

    private val _statsRange = MutableStateFlow(StatsRange.MONTH)
    val statsRange: StateFlow<StatsRange> = _statsRange.asStateFlow()

    private val _message = MutableStateFlow<RecordsMessage?>(null)
    val message: StateFlow<RecordsMessage?> = _message.asStateFlow()

    /** 正在编辑的记录；非 null 时界面弹出编辑对话框 */
    private val _editing = MutableStateFlow<CheckinRecord?>(null)
    val editing: StateFlow<CheckinRecord?> = _editing.asStateFlow()

    /** 待清空的确认状态；true 时界面弹出清空确认框 */
    private val _clearConfirmVisible = MutableStateFlow(false)
    val clearConfirmVisible: StateFlow<Boolean> = _clearConfirmVisible.asStateFlow()

    /**
     * 待撤销缓冲区。
     *
     * 只保留**最近一次**删除：连续删除时，前一条会被真正提交（不再可撤销），
     * 这与 Gmail / Android 通知的「撤销」语义一致 —— 撤销窗口只对最后一次操作有效。
     */
    private var lastDeleted: CheckinRecord? = null

    /**
     * 与 [lastDeleted] 对应的那条提示消息的序号。
     *
     * 界面上的 Snackbar 可能排队展示，用户点到的很可能是**较早**那条删除的「撤销」。
     * 因此撤销/提交都要带上消息序号做校验：只有序号与当前缓冲一致时才生效，
     * 否则说明该次删除已被后续操作取代，必须忽略，避免把**别的**记录写回去。
     */
    private var pendingUndoSeq: Long = 0L

    /** 消息序号自增源（见 [RecordsMessage.seq] 的说明） */
    private var messageSeq: Long = 0L

    private val _undoAvailable = MutableStateFlow(false)

    /** 当前是否有可撤销的删除 */
    val undoAvailable: StateFlow<Boolean> = _undoAvailable.asStateFlow()

    /** 备份/恢复是否进行中（用于禁用按钮、防重复触发） */
    private val _transferInProgress = MutableStateFlow(false)
    val transferInProgress: StateFlow<Boolean> = _transferInProgress.asStateFlow()

    // ---------------------------------------------------------------- 视图切换

    fun selectView(view: RecordsView) {
        _view.value = view
    }

    fun selectStatsRange(range: StatsRange) {
        _statsRange.value = range
    }

    // ---------------------------------------------------------------- 单条删除 / 撤销 / 编辑

    /** 删除单条记录，并开启撤销窗口 */
    fun deleteRecord(record: CheckinRecord) {
        viewModelScope.launch {
            runCatching { recordRepository.deleteById(record.id) }
                .onSuccess {
                    lastDeleted = record
                    _undoAvailable.value = true
                    pendingUndoSeq = postMessage(
                        text = app().getString(R.string.records_deleted_one),
                        undoable = true
                    )
                }
                .onFailure {
                    postMessage(app().getString(R.string.records_delete_failed, it.message.orEmpty()))
                }
        }
    }

    /**
     * 撤销最近一次删除（按原 id 原样写回）。
     *
     * @param seq 触发该动作的提示消息序号；与当前缓冲不匹配时直接忽略
     *            （详见 [pendingUndoSeq] 的说明）。
     */
    fun undoDelete(seq: Long) {
        if (seq != pendingUndoSeq) return
        val record = lastDeleted ?: return
        lastDeleted = null
        _undoAvailable.value = false
        pendingUndoSeq = 0L
        viewModelScope.launch {
            runCatching { recordRepository.restore(record) }
                .onSuccess {
                    postMessage(app().getString(R.string.records_undo_done))
                }
                .onFailure {
                    postMessage(app().getString(R.string.records_undo_failed, it.message.orEmpty()))
                }
        }
    }

    /**
     * 撤销窗口关闭（Snackbar 消失）：丢弃缓冲，使该次删除不可再撤销。
     *
     * @param seq 同 [undoDelete]：序号不匹配说明该缓冲已被新的删除取代，不能清掉。
     */
    fun commitDelete(seq: Long) {
        if (seq != pendingUndoSeq) return
        lastDeleted = null
        _undoAvailable.value = false
        pendingUndoSeq = 0L
    }

    /** 打开编辑对话框 */
    fun startEdit(record: CheckinRecord) {
        _editing.value = record
    }

    /** 关闭编辑对话框 */
    fun cancelEdit() {
        _editing.value = null
    }

    /**
     * 保存编辑结果。
     *
     * 只允许修改**时间**（补卡/纠错）与**备注**，坐标与来源保持原样 ——
     * 位置是打卡的事实依据，允许手改会让记录失去可信度；
     * 时间纠错则是行业共识（漏打、延迟打卡是常态）。
     */
    fun saveEdit(record: CheckinRecord) {
        viewModelScope.launch {
            runCatching { recordRepository.update(record) }
                .onSuccess {
                    _editing.value = null
                    postMessage(app().getString(R.string.records_edit_saved))
                }
                .onFailure {
                    postMessage(app().getString(R.string.records_edit_failed, it.message.orEmpty()))
                }
        }
    }

    // ---------------------------------------------------------------- 清空

    /** 请求清空（先弹确认，不直接写库） */
    fun requestClear() {
        _clearConfirmVisible.value = true
    }

    fun dismissClear() {
        _clearConfirmVisible.value = false
    }

    /** 确认清空全部记录 */
    fun confirmClear() {
        _clearConfirmVisible.value = false
        viewModelScope.launch {
            runCatching { recordRepository.clear() }
                .onSuccess {
                    lastDeleted = null
                    _undoAvailable.value = false
                    pendingUndoSeq = 0L
                    postMessage(app().getString(R.string.records_cleared))
                }
                .onFailure {
                    postMessage(app().getString(R.string.records_clear_failed, it.message.orEmpty()))
                }
        }
    }

    // ---------------------------------------------------------------- 导出 / 备份 / 恢复

    /**
     * 生成 CSV 文本。
     *
     * 表头与文案从资源取（保证本地化），格式化逻辑在纯函数 [CsvExporter] 中，
     * 因此 CSV 转义、BOM 等易错点可被 JVM 单测覆盖。
     */
    suspend fun buildCsv(): String = withContext(Dispatchers.IO) {
        val ctx = app()
        val records = recordRepository.getAllOnce()
            .sortedByDescending { it.time }
        CsvExporter.toCsv(
            records = records,
            headers = listOf(
                ctx.getString(R.string.csv_header_time),
                ctx.getString(R.string.csv_header_point),
                ctx.getString(R.string.csv_header_source),
                ctx.getString(R.string.csv_header_latitude),
                ctx.getString(R.string.csv_header_longitude),
                ctx.getString(R.string.csv_header_distance),
                ctx.getString(R.string.csv_header_direction),
                ctx.getString(R.string.csv_header_note)
            ),
            sourceLabel = { source ->
                ctx.getString(
                    when (source) {
                        CheckInSource.MANUAL -> R.string.records_source_manual
                        CheckInSource.AUTO_CONFIRMED -> R.string.records_source_auto_confirmed
                        CheckInSource.FENCE_EXIT -> R.string.records_source_fence_exit
                    }
                )
            },
            directionLabel = { isEnter ->
                when (isEnter) {
                    true -> ctx.getString(R.string.records_enter)
                    false -> ctx.getString(R.string.records_exit)
                    null -> ctx.getString(R.string.csv_direction_none)
                }
            }
        )
    }

    /** CSV 建议文件名 */
    fun csvFileName(): String = CsvExporter.suggestFileName(System.currentTimeMillis())

    /** 生成备份 JSON 文本 */
    suspend fun buildBackupJson(): String = withContext(Dispatchers.IO) {
        val prefs = preferencesStore.readNotifyPreferences()
        val snapshot = backupRepository.export(
            BackupPrefs(sound = prefs.sound, vibrate = prefs.vibrate, fullScreen = prefs.fullScreen)
        )
        BackupCodec.encode(snapshot)
    }

    /** 备份建议文件名 */
    fun backupFileName(): String = BackupCodec.suggestFileName(System.currentTimeMillis())

    /**
     * 从备份 JSON 恢复。
     *
     * 会先清空当前全部数据再写入，属于破坏性操作，调用方必须已获得用户确认。
     */
    fun restoreFromJson(text: String, onFinished: (Boolean) -> Unit = {}) {
        if (_transferInProgress.value) return
        _transferInProgress.value = true
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { BackupCodec.decode(text) }
                val outcome: RestoreOutcome = withContext(Dispatchers.IO) {
                    backupRepository.restore(snapshot)
                }
                val suffix = if (snapshot.droppedRecords > 0) {
                    app().getString(R.string.records_restore_dropped, snapshot.droppedRecords)
                } else {
                    ""
                }
                postMessage(
                    app().getString(
                        R.string.records_restore_success,
                        outcome.pointsRestored,
                        outcome.recordsRestored
                    ) + suffix
                )
                onFinished(true)
            } catch (e: BackupCodec.UnsupportedVersionException) {
                postMessage(e.message.orEmpty())
                onFinished(false)
            } catch (e: BackupCodec.MalformedBackupException) {
                postMessage(app().getString(R.string.records_restore_invalid, e.message.orEmpty()))
                onFinished(false)
            } catch (e: Exception) {
                postMessage(app().getString(R.string.records_restore_failed, e.message.orEmpty()))
                onFinished(false)
            } finally {
                _transferInProgress.value = false
            }
        }
    }

    /** 消费提示消息 */
    fun consumeMessage() {
        _message.value = null
    }

    /**
     * 直接投递一条提示消息。
     *
     * 供导出流程使用：写入文件在 UI 层（需要 `ContentResolver` 与用户选择的 Uri），
     * 结果需要回到本类的消息通道统一展示。
     */
    fun notify(text: String) {
        postMessage(text)
    }

    /**
     * 投递一条提示消息，返回其唯一序号。
     *
     * **所有**消息都必须经此入口：内部会给每条消息分配自增序号，
     * 避免文案相同的相邻消息被 `MutableStateFlow` 的等值合并吞掉（见 [RecordsMessage.seq]）。
     */
    private fun postMessage(text: String, undoable: Boolean = false): Long {
        val seq = messageSeq + 1
        messageSeq = seq
        _message.value = RecordsMessage(text = text, undoable = undoable, seq = seq)
        return seq
    }

    private fun app(): Application = getApplication()

    companion object {
        /** UI 取消订阅后保留数据的宽限期，覆盖旋转与短暂切后台 */
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
