package cn.luoyi0914.ofmgateway.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * 品牌色板。
 *
 * 只用 Material 3 的语义 token（primary / surfaceContainer* / error），
 * 页面里不出现硬编码颜色——否则深色模式下必然出现看不文字或看不清的卡片。
 */

private val Accent = Color(0xFF3B5BDB)

private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF585E71),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE2F9),
    onTertiaryContainer = Color(0xFF151B2C),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFBFBFF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFBFBFF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F4FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE3E2EA),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF2A46C0),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF293042),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFBFC6DC),
    onTertiary = Color(0xFF293042),
    tertiaryContainer = Color(0xFF3F4759),
    onTertiaryContainer = Color(0xFFDBE2F9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF45464F),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
)

/** 状态语义色：成功/警告。深浅色各一套，保证对比度。 */
private val SuccessLight = Color(0xFF1B873F)
private val SuccessDark = Color(0xFF6FD08C)
private val WarnLight = Color(0xFF8A5300)
private val WarnDark = Color(0xFFFFB95C)

object StatusColors {
    @Composable
    fun success(): Color = if (isSystemInDarkTheme()) SuccessDark else SuccessLight

    @Composable
    fun warn(): Color = if (isSystemInDarkTheme()) WarnDark else WarnLight
}

@Composable
fun OfmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, content = content)
}
