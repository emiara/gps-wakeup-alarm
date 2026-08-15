package dev.emiara.gpswakeup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

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

/**
 * OpenDyslexic — weighted letter bottoms and distinct shapes, so characters are harder to
 * flip or confuse. Bundled rather than downloaded so it works offline.
 *
 * Used only for the plain-language status text. That is the one thing you read while barely
 * awake; setting the whole interface in it makes everything wider and harder to scan.
 */
val OpenDyslexic = FontFamily(
    Font(R.font.opendyslexic_regular, FontWeight.Normal),
    Font(R.font.opendyslexic_bold, FontWeight.Bold),
)

@Composable
fun WakeupTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
