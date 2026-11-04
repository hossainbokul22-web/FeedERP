package com.feederp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF2E7D32)
private val LightScheme = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = Color(0xFF8D6E00),
    background = Color(0xFFFAFAF5),
    surface = Color(0xFFFFFFFF),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF81C784),
    secondary = Color(0xFFE0C060),
)

@Composable
fun FeedErpTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, content = content)
}
