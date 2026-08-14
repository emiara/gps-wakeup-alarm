package dev.emiara.gpswakeup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AlarmRed = Color(0xFFC62828)
val NightAmber = Color(0xFFFFB74D)
val OkGreen = Color(0xFF66BB6A)
val WarnAmber = Color(0xFFFFA726)

/**
 * Permanently dark. You'll be opening this on a night bus — a white screen is the last
 * thing anyone wants at 4am.
 */
private val DarkColors = darkColorScheme(
    primary = NightAmber,
    onPrimary = Color(0xFF1A1A1A),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF101012),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1A1A1E),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF26262C),
    onSurfaceVariant = Color(0xFFBFBAC4),
    error = AlarmRed,
)

@Composable
fun WakeupTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
