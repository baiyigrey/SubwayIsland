package com.subwayisland.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * MIUIX / HyperOS 视觉规范的最小实现。
 *
 * 官方 Miuix 是 Compose Multiplatform 库，当前版本要求 Kotlin ≥ 2.2
 * （连带 Compose 1.10），而本模块要保持“能用一个 JDK 17 + AGP 8.7 直接构建”。
 * 所以这里按它的设计语言手写一套等价的设计令牌，不引第三方 UI 依赖：
 *  - 大圆角卡片（20dp）+ 分组列表 + 1px 分隔线
 *  - 强调色 #3482FF，深色模式下提亮到 #5C9BFF
 *  - 字号梯度：大标题 30 / 卡片标题 16 / 正文 14 / 辅助 12.5
 */
object MiuixPalette {
    val Blue = Color(0xFF3482FF)
    val BlueLight = Color(0xFF5C9BFF)
    val Green = Color(0xFF2BBE6B)
    val Orange = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF453A)

    /** 岛上那层深色玻璃：不随系统主题变，和超级岛本身的黑色底一致 */
    val IslandTop = Color(0xFF262A33)
    val IslandBottom = Color(0xFF0E1014)
    val IslandText = Color(0xFFFFFFFF)
    val IslandSubText = Color(0xFF9AA1AD)
}

private val LightScheme = lightColorScheme(
    primary = MiuixPalette.Blue,
    onPrimary = Color.White,
    background = Color(0xFFF7F7F9),
    onBackground = Color(0xFF111318),
    surface = Color.White,
    onSurface = Color(0xFF111318),
    surfaceVariant = Color(0xFFF1F2F5),
    onSurfaceVariant = Color(0xFF8A8F99),
    outlineVariant = Color(0xFFEDEEF1),
    error = MiuixPalette.Red
)

private val DarkScheme = darkColorScheme(
    primary = MiuixPalette.BlueLight,
    onPrimary = Color.White,
    background = Color.Black,
    onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF17181C),
    onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF222429),
    onSurfaceVariant = Color(0xFF9AA0AA),
    outlineVariant = Color(0xFF2A2C31),
    error = MiuixPalette.Red
)

private val MiuixTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
)

@Composable
fun MiuixTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = MiuixTypography,
        content = content
    )
}
