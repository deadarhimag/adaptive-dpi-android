package org.adaptivedpi.android.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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

/** Смысловые цвета состояния: «работает», «чиню», «не помочь». Ошибка - `colorScheme.error`. */
@Immutable
class StatusColors(
    val working: Color,
    val onWorking: Color,
    val workingContainer: Color,
    val onWorkingContainer: Color,
    val fixing: Color,
    val onFixing: Color,
    val fixingContainer: Color,
    val onFixingContainer: Color,
    val unavailable: Color,
    val onUnavailable: Color,
    val unavailableContainer: Color,
    val onUnavailableContainer: Color,
)

private val lightStatusColors = StatusColors(
    working = Color(0xFF1E6B3A),
    onWorking = Color.White,
    workingContainer = Color(0xFFC6EFCF),
    onWorkingContainer = Color(0xFF0A3A1B),
    fixing = Color(0xFF7A5900),
    onFixing = Color.White,
    fixingContainer = Color(0xFFFFE08A),
    onFixingContainer = Color(0xFF3F2E00),
    unavailable = Color(0xFF5F6663),
    onUnavailable = Color.White,
    unavailableContainer = Color(0xFFE1E5E3),
    onUnavailableContainer = Color(0xFF2B312F),
)

private val darkStatusColors = StatusColors(
    working = Color(0xFF8FD9A3),
    onWorking = Color(0xFF00391B),
    workingContainer = Color(0xFF1F5130),
    onWorkingContainer = Color(0xFFAEF2BF),
    fixing = Color(0xFFEBC15B),
    onFixing = Color(0xFF3F2E00),
    fixingContainer = Color(0xFF5A4300),
    onFixingContainer = Color(0xFFFFE08A),
    unavailable = Color(0xFFB7BEBB),
    onUnavailable = Color(0xFF2B312F),
    unavailableContainer = Color(0xFF3A413E),
    onUnavailableContainer = Color(0xFFDDE3E0),
)

private val LocalStatusColors = staticCompositionLocalOf { lightStatusColors }

object AdaptiveDpiTheme {
    val statusColors: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current
}

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
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) darkStatusColors else lightStatusColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkColors else lightColors,
            shapes = shapes,
            content = content,
        )
    }
}
