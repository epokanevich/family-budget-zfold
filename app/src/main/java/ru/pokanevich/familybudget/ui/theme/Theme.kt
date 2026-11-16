package ru.pokanevich.familybudget.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class AppThemeMode(val prefKey: String, val label: String) {
    SYSTEM("SYSTEM", "Авто"),
    LIGHT("LIGHT", "Светлая"),
    DARK("DARK", "Тёмная");

    companion object {
        fun fromPref(raw: String): AppThemeMode =
            entries.firstOrNull { it.prefKey == raw } ?: SYSTEM
    }
}

@Immutable
data class FamilyPalette(
    val isDark: Boolean,
    val background: Color,
    val surfaceCard: Color,
    val surfaceElevated: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val outlineSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accentEmerald: Color,
    val accentEmeraldSoft: Color,
    val accentBlue: Color,
    val accentBlueSoft: Color,
    val accentCoral: Color,
    val accentCoralSoft: Color,
    val accentAmber: Color,
    val accentAmberSoft: Color,
    val eugeneColor: Color,
    val eugeneSoft: Color,
    val anzhelikaColor: Color,
    val anzhelikaSoft: Color
)

private val LightFamilyPalette = FamilyPalette(
    isDark = false,
    background = Color(0xFFF3F5F8),
    surfaceCard = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFEDF1F5),
    heroStart = Color(0xFF065F46),
    heroEnd = Color(0xFF0F766E),
    outlineSubtle = Color(0xFFE2E8F0),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF94A3B8),
    accentEmerald = Color(0xFF059669),
    accentEmeraldSoft = Color(0xFFD1FAE5),
    accentBlue = Color(0xFF2563EB),
    accentBlueSoft = Color(0xFFDBEAFE),
    accentCoral = Color(0xFFE11D48),
    accentCoralSoft = Color(0xFFFFE4E6),
    accentAmber = Color(0xFFD97706),
    accentAmberSoft = Color(0xFFFEF3C7),
    eugeneColor = Color(0xFF0284C7),
    eugeneSoft = Color(0xFFE0F2FE),
    anzhelikaColor = Color(0xFFDB2777),
    anzhelikaSoft = Color(0xFFFCE7F3)
)

private val DarkFamilyPalette = FamilyPalette(
    isDark = true,
    background = Color(0xFF0B111E),
    surfaceCard = Color(0xFF141E30),
    surfaceElevated = Color(0xFF1D293F),
    heroStart = Color(0xFF0B3B34),
    heroEnd = Color(0xFF122C49),
    outlineSubtle = Color(0xFF24324B),
    textPrimary = Color(0xFFF1F5F9),
    textSecondary = Color(0xFF94A3B8),
    textMuted = Color(0xFF64748B),
    accentEmerald = Color(0xFF34D399),
    accentEmeraldSoft = Color(0xFF0D382D),
    accentBlue = Color(0xFF60A5FA),
    accentBlueSoft = Color(0xFF132A4A),
    accentCoral = Color(0xFFFB7185),
    accentCoralSoft = Color(0xFF3E1423),
    accentAmber = Color(0xFFFBBF24),
    accentAmberSoft = Color(0xFF38270D),
    eugeneColor = Color(0xFF38BDF8),
    eugeneSoft = Color(0xFF0E2F47),
    anzhelikaColor = Color(0xFFF472B6),
    anzhelikaSoft = Color(0xFF3B1530)
)

val LocalFamilyPalette = staticCompositionLocalOf { LightFamilyPalette }

private val FamilyTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
fun FamilyBudgetTheme(
    themeMode: AppThemeMode,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val palette = if (isDark) DarkFamilyPalette else LightFamilyPalette

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = palette.accentEmerald,
            onPrimary = Color(0xFF042F21),
            primaryContainer = palette.accentEmeraldSoft,
            onPrimaryContainer = palette.accentEmerald,
            secondary = palette.accentBlue,
            secondaryContainer = palette.accentBlueSoft,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surfaceCard,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.outlineSubtle,
            error = palette.accentCoral,
            errorContainer = palette.accentCoralSoft
        )
    } else {
        lightColorScheme(
            primary = palette.accentEmerald,
            onPrimary = Color.White,
            primaryContainer = palette.accentEmeraldSoft,
            onPrimaryContainer = Color(0xFF064E3B),
            secondary = palette.accentBlue,
            secondaryContainer = palette.accentBlueSoft,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surfaceCard,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.outlineSubtle,
            error = palette.accentCoral,
            errorContainer = palette.accentCoralSoft
        )
    }

    CompositionLocalProvider(LocalFamilyPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FamilyTypography,
            content = content
        )
    }
}
