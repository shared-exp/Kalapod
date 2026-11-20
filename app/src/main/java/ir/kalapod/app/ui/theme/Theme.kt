package ir.kalapod.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.kalapod.app.R

val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

// Warm light background + olive/dark green primary (SPEC §2).
private val Light = lightColorScheme(
    primary = Color(0xFF3F5F2A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E6C2),
    onPrimaryContainer = Color(0xFF112006),
    secondary = Color(0xFF6B6A3A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1EBC4),
    onSecondaryContainer = Color(0xFF222005),
    tertiary = Color(0xFFA0522D),
    onTertiary = Color.White,
    background = Color(0xFFFBF8F1),
    onBackground = Color(0xFF1D1C17),
    surface = Color(0xFFFBF8F1),
    onSurface = Color(0xFF1D1C17),
    surfaceVariant = Color(0xFFEDE8DA),
    onSurfaceVariant = Color(0xFF4A473D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EA),
    surfaceContainer = Color(0xFFF2EEE3),
    surfaceContainerHigh = Color(0xFFECE8DC),
    outline = Color(0xFF7B776B),
    outlineVariant = Color(0xFFCCC7B8),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9C98F),
    onPrimary = Color(0xFF1B3409),
    primaryContainer = Color(0xFF2E4A1C),
    onPrimaryContainer = Color(0xFFD7E6C2),
    secondary = Color(0xFFD4CE9E),
    onSecondary = Color(0xFF373512),
    secondaryContainer = Color(0xFF4E4C26),
    onSecondaryContainer = Color(0xFFF1EBC4),
    tertiary = Color(0xFFE6A47E),
    onTertiary = Color(0xFF4A1F06),
    background = Color(0xFF151511),
    onBackground = Color(0xFFE7E3D9),
    surface = Color(0xFF151511),
    onSurface = Color(0xFFE7E3D9),
    surfaceVariant = Color(0xFF3A382F),
    onSurfaceVariant = Color(0xFFCCC7B8),
    surfaceContainerLowest = Color(0xFF10100C),
    surfaceContainerLow = Color(0xFF1D1D18),
    surfaceContainer = Color(0xFF22211C),
    surfaceContainerHigh = Color(0xFF2C2B25),
    outline = Color(0xFF959184),
    outlineVariant = Color(0xFF4A473D),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

private fun style(size: Int, weight: FontWeight, line: Int) =
    TextStyle(fontFamily = Vazirmatn, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

// Slightly larger than Material defaults: users are busy shopkeepers, not power users.
private val AppTypography = Typography(
    displaySmall = style(34, FontWeight.Bold, 44),
    headlineLarge = style(30, FontWeight.Bold, 40),
    headlineMedium = style(26, FontWeight.Bold, 36),
    headlineSmall = style(22, FontWeight.Bold, 32),
    titleLarge = style(20, FontWeight.Bold, 30),
    titleMedium = style(17, FontWeight.Medium, 26),
    titleSmall = style(15, FontWeight.Medium, 22),
    bodyLarge = style(17, FontWeight.Normal, 28),
    bodyMedium = style(15, FontWeight.Normal, 24),
    bodySmall = style(13, FontWeight.Normal, 20),
    labelLarge = style(16, FontWeight.Medium, 22),
    labelMedium = style(13, FontWeight.Medium, 18),
    labelSmall = style(12, FontWeight.Medium, 16),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App root theme. Always RTL, regardless of device language. */
@Composable
fun KalapodTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
