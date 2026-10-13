// 职责：通用 Compose 组件（权限提示卡、隐私合规弹窗）
package com.example.checkin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.checkin.R
import com.example.checkin.util.AppPermissionState

/**
 * 权限引导卡。
 *
 * 只有当定位/后台定位/通知三项全部就绪时才会隐藏；
 * 后台定位必须引导用户去系统设置里改为“始终允许”，因此单独一个按钮。
 */
@Composable
fun PermissionCard(
    permissions: AppPermissionState,
    onRequestForeground: () -> Unit,
    onRequestBackground: () -> Unit,
    onRequestNotification: () -> Unit,
    modifier: Modifier = Modifier
) {
    // hasLocation 表示前台+后台定位是否都已具备（见 AppPermissionState 定义）
    //
    // ★ 这里刻意用 if/else 包裹而不是「提前 return」。在 @Composable 里提前返回会让
    //   Compose 编译器生成配对的 endReplaceableGroup，导致 group start/end 不平衡，
    //   在 release（R8 优化后）会抛 ArrayIndexOutOfBoundsException —— 本工程已在
    //   记录页与定位调试页踩过同样的坑，约定 UI 层一律不使用提前返回。
    if (!permissions.allGranted) {
        PermissionCardBody(
            permissions = permissions,
            onRequestForeground = onRequestForeground,
            onRequestBackground = onRequestBackground,
            onRequestNotification = onRequestNotification,
            modifier = modifier
        )
    }
}

@Composable
private fun PermissionCardBody(
    permissions: AppPermissionState,
    onRequestForeground: () -> Unit,
    onRequestBackground: () -> Unit,
    onRequestNotification: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Spacer(Modifier.height(8.dp))

            PermissionRow(
                label = stringResource(R.string.permission_location_foreground),
                granted = permissions.hasLocation,
                onRequest = onRequestForeground
            )
            PermissionRow(
                label = stringResource(R.string.permission_location_background),
                granted = permissions.backgroundLocation,
                onRequest = onRequestBackground
            )
            PermissionRow(
                label = stringResource(R.string.permission_notification),
                granted = permissions.notification,
                onRequest = onRequestNotification
            )

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.permission_denied_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f)
        )
        if (granted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.permission_granted),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        } else {
            TextButton(onClick = onRequest) {
                Text(stringResource(R.string.permission_grant))
            }
        }
    }
}

/**
 * 高德 SDK 合规弹窗。
 *
 * 依据《高德开放平台隐私权政策》要求，必须在用户点击同意后才能初始化定位能力，
 * 这也是国内 App 上架的硬性合规项。
 */
@Composable
fun PrivacyConsentDialog(onAgree: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 必须显式同意，不允许点击外部关闭 */ },
        title = { Text(stringResource(R.string.privacy_title)) },
        text = {
            Text(stringResource(R.string.privacy_message))
        },
        confirmButton = {
            TextButton(onClick = onAgree) {
                Text(stringResource(R.string.privacy_agree))
            }
        }
    )
}
