// 职责：打卡记录编辑对话框（纠错时间与补充备注）
package com.example.checkin.ui.records

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.checkin.R
import com.example.checkin.data.local.entity.CheckinRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EDIT_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

/**
 * 记录编辑对话框。
 *
 * 允许修改的范围**刻意收窄**为「时间 + 备注」：
 * - 时间：漏打卡、延迟打卡是常态，补卡是同类产品的共识能力；
 * - 备注：记录上下文（如「外出办事」「忘记打卡后补记」）。
 *
 * 坐标与来源**不允许手改** —— 它们是打卡事实的原始证据，
 * 一旦可编辑，统计与进出配对就失去了可信度。
 */
@Composable
internal fun RecordEditDialog(
    record: CheckinRecord,
    onDismiss: () -> Unit,
    onSave: (CheckinRecord) -> Unit
) {
    val zone = remember { ZoneId.systemDefault() }

    var timeText by remember(record.id) {
        mutableStateOf(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(record.time), zone).format(EDIT_FMT)
        )
    }
    var noteText by remember(record.id) { mutableStateOf(record.note.orEmpty()) }
    var error by remember(record.id) { mutableStateOf<String?>(null) }

    val invalidTimeMessage = stringResource(R.string.records_edit_time_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.records_edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = timeText,
                    onValueChange = {
                        timeText = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.records_edit_time)) },
                    supportingText = { Text(stringResource(R.string.records_edit_time_hint)) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = noteText,
                    onValueChange = {
                        // 备注长度上限 200：既是 UI 体验，也避免备份文件被单条记录撑大
                        if (it.length <= 200) noteText = it
                    },
                    label = { Text(stringResource(R.string.records_edit_note)) },
                    supportingText = { Text(stringResource(R.string.records_edit_note_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = runCatching {
                        LocalDateTime.parse(timeText.trim(), EDIT_FMT)
                    }.getOrNull()

                    if (parsed == null) {
                        error = invalidTimeMessage
                    } else {
                        onSave(
                            record.copy(
                                time = parsed.atZone(zone).toInstant().toEpochMilli(),
                                note = noteText.trim().takeIf { it.isNotEmpty() }
                            )
                        )
                    }
                }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
