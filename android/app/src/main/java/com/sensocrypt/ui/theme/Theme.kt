package com.sensocrypt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SensoDark = darkColorScheme(
    primary = Color(0xFF3DDC97),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF0B0F14),
)

private val SensoLight = lightColorScheme(
    primary = Color(0xFF0F9D6E),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun SensoCryptTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SensoDark else SensoLight
    MaterialTheme(colorScheme = colorScheme, content = content)
}
