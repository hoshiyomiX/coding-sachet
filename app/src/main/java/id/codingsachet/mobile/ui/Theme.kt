package id.codingsachet.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Sky as the accent (Coding Sachet: sky-400 #38BDF8). */
val SachetSky = Color(0xFF38BDF8)
val SachetSkyDark = Color(0xFF0369A1)

private val DarkScheme = darkColorScheme(
    primary = SachetSky,
    onPrimary = Color(0xFF00344F),
    primaryContainer = SachetSkyDark,
    onPrimaryContainer = Color(0xFFCDEBFF),
    secondary = Color(0xFF8FCBF5),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE1E3E4),
    surface = Color(0xFF161D24),
    onSurface = Color(0xFFE1E3E4),
    surfaceVariant = Color(0xFF1E2833),
    onSurfaceVariant = Color(0xFFB9C6D4),
    error = Color(0xFFFFB4AB),
)

private val LightScheme = lightColorScheme(
    primary = SachetSkyDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBFF),
    onPrimaryContainer = Color(0xFF001E30),
    secondary = Color(0xFF335F79),
    background = Color(0xFFF5FAFD),
    onBackground = Color(0xFF171D24),
    surface = Color(0xFFFDFEFF),
    onSurface = Color(0xFF171D24),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF3F4850),
    error = Color(0xFFBA1A1A),
)

@Composable
fun SachetTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}
