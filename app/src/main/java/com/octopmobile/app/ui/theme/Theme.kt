package com.octopmobile.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
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
    primaryContainer = Color(0xFF303238),
    onPrimaryContainer = Color(0xFFE6E7E9),
    secondary = Color(0xFFB8BEC8),
    onSecondary = Color(0xFF1B1E23),
    secondaryContainer = Color(0xFF34373C),
    onSecondaryContainer = Color(0xFFE4E6E9),
    tertiary = Color(0xFFA8AEB8),
    onTertiary = Color(0xFF16191E),
    tertiaryContainer = Color(0xFF2B2E33),
    onTertiaryContainer = Color(0xFFE2E4E8),
    background = BrandBackgroundDark,
    onBackground = BrandOnDark,
    surface = BrandSurfaceDark,
    onSurface = BrandOnDark,
    surfaceTint = primary,
    surfaceDim = Color(0xFF0B0D11),
    surfaceBright = Color(0xFF262A31),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF14171C),
    surfaceContainer = Color(0xFF191C22),
    surfaceContainerHigh = Color(0xFF202329),
    surfaceContainerHighest = Color(0xFF272B32),
    surfaceVariant = Color(0xFF1F242E),
    onSurfaceVariant = Color(0xFFB6BECB),
    outline = Color(0xFF3A424F),
    outlineVariant = Color(0xFF343A44),
    inverseSurface = Color(0xFFE6E7EA),
    inverseOnSurface = Color(0xFF2C2F35),
)

private fun lightColors(primary: Color) = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E5E8),
    onPrimaryContainer = Color(0xFF1E2228),
    secondary = Color(0xFF5C6470),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1E4),
    onSecondaryContainer = Color(0xFF1E2228),
    tertiary = Color(0xFF6A717C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECEDEF),
    onTertiaryContainer = Color(0xFF24282E),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF11141A),
    surface = Color.White,
    onSurface = Color(0xFF11141A),
    surfaceTint = primary,
    surfaceDim = Color(0xFFE2E4E8),
    surfaceBright = Color(0xFFFCFCFD),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F6F8),
    surfaceContainer = Color(0xFFEFF0F3),
    surfaceContainerHigh = Color(0xFFE9EAED),
    surfaceContainerHighest = Color(0xFFE3E5E9),
    surfaceVariant = Color(0xFFE9ECF1),
    onSurfaceVariant = Color(0xFF454C57),
    outline = Color(0xFFC7CDD6),
    outlineVariant = Color(0xFFDDE0E5),
    inverseSurface = Color(0xFF2E3136),
    inverseOnSurface = Color(0xFFF2F3F5),
)

fun ThemeColor.color(): Color = when (this) {
    ThemeColor.BLUE -> Color(0xFF5B8DEF)
    ThemeColor.GREEN -> Color(0xFF00A884)
    ThemeColor.ORANGE -> Color(0xFFF58A34)
    ThemeColor.PURPLE -> Color(0xFF8B6FE8)
    ThemeColor.RED -> Color(0xFFE65757)
}

/** 全应用共用的品牌渐变：顶部带主题色，向下过渡到页面底色。 */
@Composable
fun octopBackgroundBrush(): Brush = Brush.verticalGradient(
    0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
    0.42f to MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
    1f to MaterialTheme.colorScheme.background,
)

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
