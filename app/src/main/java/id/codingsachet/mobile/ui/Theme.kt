package id.codingsachet.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Theme wrapper over the official miuix [MiuixTheme] (MIGRATION.md):
 * light mode keeps miuix's default HyperOS palette, dark mode maps the
 * app's previous Material3 dark scheme onto miuix's Colors.
 */
val SachetSky = Color(0xFF38BDF8)
val SachetSkyDark = Color(0xFF0369A1)

/** Success color per theme (tool "done" chips): readable on both modes. */
val SachetSuccessDark = Color(0xFF9CD67D)
val SachetSuccessLight = Color(0xFF2E7D32)

/**
 * Dark scheme: the old Material3 DarkScheme values re-mapped onto miuix
 * color roles (see MIGRATION.md §4). Container roles that had no direct
 * M3 counterpart keep tuned slate-blue values so selected rows, executor
 * chips and error cards stay legible.
 */
private val SachetDarkColors = darkColorScheme(
    primary = SachetSky,
    onPrimary = Color(0xFF00344F),
    primaryContainer = SachetSkyDark,
    onPrimaryContainer = Color(0xFFCDEBFF),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE1E3E4),
    surface = Color(0xFF161D24),
    onSurface = Color(0xFFE1E3E4),
    surfaceVariant = Color(0xFF1E2833),
    onSurfaceVariantSummary = Color(0xFFB9C6D4),
    onSurfaceSecondary = Color(0xFFE1E3E4),
    onSurfaceVariantActions = Color(0xFFB9C6D4),
    secondaryContainer = Color(0xFF2A3B4D),
    onSecondaryContainer = Color(0xFFA8C6E8),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF4A1714),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun SachetTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MiuixTheme(
        // Light = miuix default (HyperOS); dark = the app's legacy scheme.
        colors = if (dark) SachetDarkColors else lightColorScheme(),
        content = content,
    )
}

/** Theme-aware success tint (tool cards' "done" state). */
@Composable
fun sachetSuccess(): Color = if (isSystemInDarkTheme()) SachetSuccessDark else SachetSuccessLight
