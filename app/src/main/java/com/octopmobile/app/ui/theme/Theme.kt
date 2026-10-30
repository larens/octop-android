package com.octopmobile.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.octopmobile.app.data.settings.ThemeColor
import com.octopmobile.app.data.settings.ThemeMode

// 品牌色与 res/values/colors.xml 保持一致（最终以系统深/浅色为准，不做自定义配色）。
private val BrandBackgroundDark = Color(0xFF0F1117)
private val BrandSurfaceDark = Color(0xFF171A21)
private val BrandOnDark = Color(0xFFECEFF4)

private fun darkColors(primary: Color) = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF0B1020),
    background = BrandBackgroundDark,
    onBackground = BrandOnDark,
    surface = BrandSurfaceDark,
    onSurface = BrandOnDark,
    surfaceVariant = Color(0xFF1F242E),
    onSurfaceVariant = Color(0xFFB6BECB),
    outline = Color(0xFF3A424F),
)

private fun lightColors(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF11141A),
    surface = Color.White,
    onSurface = Color(0xFF11141A),
    surfaceVariant = Color(0xFFE9ECF1),
    onSurfaceVariant = Color(0xFF454C57),
    outline = Color(0xFFC7CDD6),
)

fun ThemeColor.color(): Color = when (this) {
    ThemeColor.BLUE -> Color(0xFF5B8DEF)
    ThemeColor.GREEN -> Color(0xFF00A884)
    ThemeColor.ORANGE -> Color(0xFFF58A34)
    ThemeColor.PURPLE -> Color(0xFF8B6FE8)
    ThemeColor.RED -> Color(0xFFE65757)
}

@Composable
fun OctopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    mode: ThemeMode = ThemeMode.SYSTEM,
    color: ThemeColor = ThemeColor.BLUE,
    content: @Composable () -> Unit,
) {
    val useDark = when (mode) {
        ThemeMode.SYSTEM -> darkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (useDark) darkColors(color.color()) else lightColors(color.color()),
        content = content,
    )
}
