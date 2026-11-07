package id.aihubmix.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Teal as the accent. */
val AihubmixTeal = Color(0xFF39C5BB)
val AihubmixTealDark = Color(0xFF1E8F88)

private val DarkScheme = darkColorScheme(
    primary = AihubmixTeal,
    onPrimary = Color(0xFF00322F),
    primaryContainer = AihubmixTealDark,
    onPrimaryContainer = Color(0xFFB8F2EC),
    secondary = Color(0xFF8FD3CE),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE1E3E4),
    surface = Color(0xFF161B20),
    onSurface = Color(0xFFE1E3E4),
    surfaceVariant = Color(0xFF1E252B),
    onSurfaceVariant = Color(0xFFB9C0C5),
    error = Color(0xFFFFB4AB),
)

private val LightScheme = lightColorScheme(
    primary = AihubmixTealDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F2EC),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF2E6B66),
    background = Color(0xFFF6FBFA),
    onBackground = Color(0xFF171D1F),
    surface = Color(0xFFFDFEFE),
    onSurface = Color(0xFF171D1F),
    surfaceVariant = Color(0xFFDBE5E3),
    onSurfaceVariant = Color(0xFF3F4947),
    error = Color(0xFFBA1A1A),
)

@Composable
fun AihubmixTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}
