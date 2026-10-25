package com.octopmobile.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌色与 res/values/colors.xml 保持一致（最终以系统深/浅色为准，不做自定义配色）。
private val BrandPrimary = Color(0xFF5B8DEF)
private val BrandBackgroundDark = Color(0xFF0F1117)
private val BrandSurfaceDark = Color(0xFF171A21)
private val BrandOnDark = Color(0xFFECEFF4)

private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color(0xFF0B1020),
    background = BrandBackgroundDark,
    onBackground = BrandOnDark,
    surface = BrandSurfaceDark,
    onSurface = BrandOnDark,
    surfaceVariant = Color(0xFF1F242E),
    onSurfaceVariant = Color(0xFFB6BECB),
    outline = Color(0xFF3A424F),
)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF11141A),
    surface = Color.White,
    onSurface = Color(0xFF11141A),
    surfaceVariant = Color(0xFFE9ECF1),
    onSurfaceVariant = Color(0xFF454C57),
    outline = Color(0xFFC7CDD6),
)

/** 主题跟随系统深/浅色（docs/spec.md §22）。 */
@Composable
fun OctopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
