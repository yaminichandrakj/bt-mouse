package com.btmouse.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    // Default buttons get the grey "selected" fill with white text; screens ask for white explicitly.
    primary = Selected,
    onPrimary = OnInk,
    primaryContainer = Selected,
    onPrimaryContainer = OnInk,
    secondary = OnInkSoft,
    onSecondary = Ink,
    secondaryContainer = Selected,
    onSecondaryContainer = OnInk,
    tertiary = StatusGreen,
    error = StatusRed,
    errorContainer = DangerContainer,
    onErrorContainer = OnDanger,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Raised,
    surfaceContainer = Panel,
    surfaceContainerHigh = Control,
    surfaceContainerHighest = ControlHigh,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = Outline,
    outlineVariant = Hairline
)

@Composable
fun BtMouseTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = DarkBackground.toArgb()
                window.navigationBarColor = DarkBackground.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
