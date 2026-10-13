// 职责：Compose Material3 主题（配色、字体、形状），与 XML 主题保持一致
package com.example.checkin.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 品牌色：以“绿色 + 定位”为主视觉 */
private val Green40 = Color(0xFF1B6C3A)
private val Green80 = Color(0xFF7FD79A)
private val GreenGrey40 = Color(0xFF4F6354)
private val GreenGrey80 = Color(0xFFB6CCBB)
private val Amber40 = Color(0xFF7A5900)
private val Amber80 = Color(0xFFF2C14E)

private val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5F4BD),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = GreenGrey40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E8D6),
    onSecondaryContainer = Color(0xFF0D1F13),
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF261A00),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDEE5DC),
    onSurfaceVariant = Color(0xFF424940)
)

private val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Color(0xFF00391B),
    primaryContainer = Color(0xFF00522A),
    onPrimaryContainer = Color(0xFFA5F4BD),
    secondary = GreenGrey80,
    onSecondary = Color(0xFF213527),
    secondaryContainer = Color(0xFF374B3D),
    onSecondaryContainer = Color(0xFFD2E8D6),
    tertiary = Amber80,
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5D4200),
    onTertiaryContainer = Color(0xFFFFDF9B),
    background = Color(0xFF111411),
    onBackground = Color(0xFFE1E4DE),
    surface = Color(0xFF111411),
    onSurface = Color(0xFFE1E4DE),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BF)
)

/**
 * 应用主题。
 *
 * @param dynamicColor Android 12+ 动态取色开关，默认关闭以保证品牌色一致
 */
@Composable
fun CheckinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // 状态栏透明 + 图标深浅色自适应，实现 edge-to-edge
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
