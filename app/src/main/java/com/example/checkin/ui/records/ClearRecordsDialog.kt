// 职责：记录页的确认对话框（清空全部记录）
package com.example.checkin.ui.records

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.checkin.R

/**
 * 清空全部记录前的二次确认。
 *
 * ★ 这个对话框是**必须存在**的：在 v1.6.0 中，记录页顶部图标点击后
 *   直接执行 `DELETE FROM checkin_record`，没有任何确认也没有撤销，
 *   用户一次误触就会永久丢失全部打卡历史。清空是不可逆操作，
 *   任何不可逆操作的入口都必须有确认步骤。
 *
 * @param recordCount 当前记录条数，明确告知影响范围
 */
@Composable
internal fun ClearRecordsDialog(
    recordCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.records_clear_confirm_title)) },
        text = { Text(stringResource(R.string.records_clear_confirm_message, recordCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.records_clear_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
