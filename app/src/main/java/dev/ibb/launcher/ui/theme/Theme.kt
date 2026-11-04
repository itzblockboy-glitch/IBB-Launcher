package dev.ibb.launcher.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * IBB theme.
 *
 * Dark-first by design: the launcher always renders the deep-void backdrop, because the glass
 * aesthetic depends on a dark base for the translucent panels to read. We still expose a
 * (very dark) light scheme for accessibility and for OEMs that force light mode at the window
 * level — it keeps text legible without breaking the frosted look.
 */
private val IbbDarkScheme = darkColorScheme(
    primary = VioletBright,
    onPrimary = Color(0xFF0C0A1A),
    primaryContainer = IndigoMid,
    onPrimaryContainer = Porcelain,
    secondary = Cyan,
    onSecondary = Color(0xFF03222A),
    secondaryContainer = CyanDim,
    tertiary = Amber,
    onTertiary = Color(0xFF2A1A07),
    background = Void,
    onBackground = Porcelain,
    surface = VoidElevated,
    onSurface = Porcelain,
    surfaceVariant = Slate,
    onSurfaceVariant = Mist,
    outline = GlassBorderBright,
    outlineVariant = GlassBorder,
    error = Danger,
    onError = Color(0xFF2B0710),
    scrim = Color(0xCC000000),
)

private val IbbLightScheme = darkColorScheme(
    // Intentionally still dark: light mode swaps surfaces, not the brand's mood.
    primary = VioletDim,
    onPrimary = Porcelain,
    secondary = CyanDim,
    background = Color(0xFF0E0D18),
    surface = Color(0xFF16141F),
    onSurface = Porcelain,
    onSurfaceVariant = Mist,
    error = Danger,
)

@Composable
fun IbbTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) IbbDarkScheme else IbbLightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Edge-to-edge with transparent bars; the backdrop draws behind them.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = IbbTypography,
        content = content,
    )
}
