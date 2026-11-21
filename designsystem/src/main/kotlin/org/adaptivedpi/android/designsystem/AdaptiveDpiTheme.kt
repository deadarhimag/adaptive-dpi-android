package org.adaptivedpi.android.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode { System, Light, Dark }

private val lightColors = lightColorScheme(
    primary = Color(0xFF0C6256),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB0F0DF),
    onPrimaryContainer = Color(0xFF07382F),
    secondary = Color(0xFF405F57),
    secondaryContainer = Color(0xFFD6E9E2),
    onSecondaryContainer = Color(0xFF223C35),
    background = Color(0xFFF6FAF8),
    surface = Color(0xFFF6FAF8),
    surfaceContainer = Color(0xFFEAF1ED),
    surfaceContainerLow = Color(0xFFF0F5F2),
    onSurface = Color(0xFF15221E),
    onSurfaceVariant = Color(0xFF465651),
    outline = Color(0xFF6F807A),
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF8DD6C6),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF164D43),
    onPrimaryContainer = Color(0xFFC1F2E5),
    secondary = Color(0xFFB0CEC4),
    secondaryContainer = Color(0xFF304943),
    onSecondaryContainer = Color(0xFFDAEEE7),
    background = Color(0xFF101916),
    surface = Color(0xFF101916),
    surfaceContainer = Color(0xFF202D28),
    surfaceContainerLow = Color(0xFF18231F),
    onSurface = Color(0xFFE1ECE6),
    onSurfaceVariant = Color(0xFFB3C6BD),
    outline = Color(0xFF8CA097),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AdaptiveDpiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val darkTheme = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) darkColors else lightColors,
        shapes = shapes,
        content = content,
    )
}
