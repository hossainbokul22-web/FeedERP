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
    primary = Color(0xFF017079), onPrimary = Color.White,
    primaryContainer = Color(0xFFDFF3F4), onPrimaryContainer = Color(0xFF00363B),
    secondary = Color(0xFF20B7C0), onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFFC4EEF1), onSecondaryContainer = Color(0xFF00363B),
    tertiary = Color(0xFF3B6EA5), onTertiary = Color.White,
    background = Color(0xFFE3F3F4), onBackground = Color(0xFF000000),
    surface = Color(0xFFE9F6F7), onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFD2E8EA), onSurfaceVariant = Color(0xFF2B4447),
    surfaceContainerLowest = Color(0xFFF4FBFB), surfaceContainerLow = Color(0xFFEDF8F8),
    surfaceContainer = Color(0xFFE3F3F4), surfaceContainerHigh = Color(0xFFDFF3F4),
    surfaceContainerHighest = Color(0xFFD6ECEE),
    outline = Color(0xFF5E7A7D), outlineVariant = Color(0xFFB7D3D5),
    error = Color(0xFFB3261E), onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF20B7C0), onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF01575E), onPrimaryContainer = Color(0xFFDFF3F4),
    secondary = Color(0xFF6FD6DD), onSecondary = Color(0xFF00363B),
    secondaryContainer = Color(0xFF014E54), onSecondaryContainer = Color(0xFFDFF3F4),
    tertiary = Color(0xFFA4C8FF), onTertiary = Color(0xFF00315E),
    background = Color(0xFF000000), onBackground = Color(0xFFDFF3F4),
    surface = Color(0xFF0B1415), onSurface = Color(0xFFDFF3F4),
    surfaceVariant = Color(0xFF1C2E30), onSurfaceVariant = Color(0xFFB7D3D5),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF0B1415),
    surfaceContainer = Color(0xFF101B1C), surfaceContainerHigh = Color(0xFF162426),
    surfaceContainerHighest = Color(0xFF1D2F31),
    outline = Color(0xFF7FA0A3), outlineVariant = Color(0xFF2A4143),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(32.dp),
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
