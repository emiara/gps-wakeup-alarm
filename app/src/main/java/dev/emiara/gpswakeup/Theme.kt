package dev.emiara.gpswakeup

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

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
 */
private val OpenDyslexic = FontFamily(
    Font(R.font.opendyslexic_regular, FontWeight.Normal),
    Font(R.font.opendyslexic_bold, FontWeight.Bold),
)

/**
 * OpenDyslexic runs wide and tall, so every style gets extra line height — cramped lines
 * undo most of the benefit of the typeface.
 */
private fun Typography.dyslexic(): Typography {
    fun androidx.compose.ui.text.TextStyle.fix() = copy(
        fontFamily = OpenDyslexic,
        lineHeight = if (fontSize.isSpecified) fontSize * 1.5f else lineHeight,
        letterSpacing = 0.sp,
    )
    return Typography(
        displayLarge = displayLarge.fix(),
        displayMedium = displayMedium.fix(),
        displaySmall = displaySmall.fix(),
        headlineLarge = headlineLarge.fix(),
        headlineMedium = headlineMedium.fix(),
        headlineSmall = headlineSmall.fix(),
        titleLarge = titleLarge.fix(),
        titleMedium = titleMedium.fix(),
        titleSmall = titleSmall.fix(),
        bodyLarge = bodyLarge.fix(),
        bodyMedium = bodyMedium.fix(),
        bodySmall = bodySmall.fix(),
        labelLarge = labelLarge.fix(),
        labelMedium = labelMedium.fix(),
        labelSmall = labelSmall.fix(),
    )
}

@Composable
fun WakeupTheme(content: @Composable () -> Unit) {
    val useDyslexic by UiPrefs.dyslexiaFont.collectAsState()
    val typography = remember(useDyslexic) {
        if (useDyslexic) Typography().dyslexic() else Typography()
    }
    MaterialTheme(colorScheme = DarkColors, typography = typography, content = content)
}
