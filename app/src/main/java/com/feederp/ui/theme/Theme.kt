package com.feederp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** গাঢ় সবুজ-টিল + উষ্ণ অ্যাম্বার অ্যাকসেন্ট। সম্পূর্ণ লাইট ও ডার্ক স্কিম। */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B6B57), onPrimary = Color.White,
    primaryContainer = Color(0xFFBFEFE2), onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFFB26A00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB3), onSecondaryContainer = Color(0xFF3A2400),
    tertiary = Color(0xFF3B6EA5), onTertiary = Color.White,
    background = Color(0xFFF3F8F6), onBackground = Color(0xFF16201D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF16201D),
    surfaceVariant = Color(0xFFDCE8E3), onSurfaceVariant = Color(0xFF3F4945),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7FBF9),
    surfaceContainer = Color(0xFFEDF4F1), surfaceContainerHigh = Color(0xFFE6EFEB),
    surfaceContainerHighest = Color(0xFFE0EAE6),
    outline = Color(0xFF6F7B77), outlineVariant = Color(0xFFBFC9C4),
    error = Color(0xFFB3261E), onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5ED3B4), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005143), onPrimaryContainer = Color(0xFFBFEFE2),
    secondary = Color(0xFFFFB95A), onSecondary = Color(0xFF462B00),
    secondaryContainer = Color(0xFF654000), onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary = Color(0xFFA4C8FF), onTertiary = Color(0xFF00315E),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF121A17), onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4945), onSurfaceVariant = Color(0xFFBEC9C4),
    surfaceContainerLowest = Color(0xFF0A100E), surfaceContainerLow = Color(0xFF161E1B),
    surfaceContainer = Color(0xFF1A2320), surfaceContainerHigh = Color(0xFF222C28),
    surfaceContainerHighest = Color(0xFF2B3632),
    outline = Color(0xFF88938F), outlineVariant = Color(0xFF3F4945),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
)

private val Base = Typography()
private val AppTypography = Typography(
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = Base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

enum class ThemeMode(val labelBn: String) { SYSTEM("ফোনের মতো"), LIGHT("লাইট"), DARK("ডার্ক") }

@Composable
fun FeedErpTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = AppShapes, typography = AppTypography, content = content,
    )
}
