package com.sensocrypt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val SensoDark = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF00391E),
    secondary = Color(0xFF7FC8FF),
    tertiary = Color(0xFFFFD54F),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF131922),
    surfaceVariant = Color(0xFF1C242F),
    onSurface = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFFA8B3C2),
    error = Color(0xFFFF6B6B),
)

private val SensoLight = lightColorScheme(
    primary = Color(0xFF0F9D6E),
    onPrimary = Color.White,
    secondary = Color(0xFF2F7FCC),
    tertiary = Color(0xFFB8860B),
    background = Color(0xFFF7F9FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEAEFF3),
    onSurface = Color(0xFF15191E),
    onSurfaceVariant = Color(0xFF4B5563),
    error = Color(0xFFB3261E),
)

/** Success/warning don't have a home in Material3's default ColorScheme -- carried
 * separately since the trust verdicts this app shows need a distinct "good" green from
 * onSurface. */
data class SensoStatusColors(val success: Color, val warning: Color)

private val SensoDarkStatus = SensoStatusColors(success = Color(0xFF3DDC97), warning = Color(0xFFFFB84D))
private val SensoLightStatus = SensoStatusColors(success = Color(0xFF0F9D6E), warning = Color(0xFF9A6300))

val LocalSensoStatusColors = staticCompositionLocalOf { SensoDarkStatus }

val SensoTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 15.sp),
    labelLarge = Typography().labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
)

@Composable
fun SensoCryptTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SensoDark else SensoLight
    val statusColors = if (darkTheme) SensoDarkStatus else SensoLightStatus
    androidx.compose.runtime.CompositionLocalProvider(LocalSensoStatusColors provides statusColors) {
        MaterialTheme(colorScheme = colorScheme, typography = SensoTypography, content = content)
    }
}
