package com.example.ui.theme

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.R

enum class ThemeMode(val label: String, @StringRes val labelRes: Int) {
    SYSTEM("System", R.string.theme_mode_system),
    DARK("Dark", R.string.theme_mode_dark),
    LIGHT("Light", R.string.theme_mode_light);

    companion object {
        fun fromRaw(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: DARK
    }
}

private val ExamForgeDarkColorScheme = darkColorScheme(
    primary = AmberForgeLight,
    onPrimary = Color(0xFF1F1400),
    primaryContainer = AmberContainerDark,
    onPrimaryContainer = Color(0xFFFDE68A),
    secondary = MintMasteryLight,
    onSecondary = Color(0xFF04261B),
    secondaryContainer = MintContainerDark,
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = IndigoSparkLight,
    onTertiary = Color(0xFF111338),
    tertiaryContainer = IndigoContainerDark,
    onTertiaryContainer = Color(0xFFC7D2FE),
    error = CoralErrorLight,
    onError = Color(0xFF2B0609),
    errorContainer = CoralContainerDark,
    onErrorContainer = Color(0xFFFECACA),
    background = ObsidianBackground,
    onBackground = ObsidianOnSurface,
    surface = ObsidianSurface,
    onSurface = ObsidianOnSurface,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = ObsidianOnSurfaceMuted,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceVariant,
    surfaceContainerHigh = ObsidianSurfaceHigh,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF232C3D)
)

private val ExamForgeLightColorScheme = lightColorScheme(
    primary = AmberForgeDark,
    onPrimary = Color.White,
    primaryContainer = AmberContainerLight,
    onPrimaryContainer = Color(0xFF451A03),
    secondary = MintMastery,
    onSecondary = Color.White,
    secondaryContainer = MintContainerLight,
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = IndigoSpark,
    onTertiary = Color.White,
    tertiaryContainer = IndigoContainerLight,
    onTertiaryContainer = Color(0xFF1E1B4B),
    error = CoralError,
    onError = Color.White,
    errorContainer = CoralContainerLight,
    onErrorContainer = Color(0xFF7F1D1D),
    background = ParchmentBackground,
    onBackground = ParchmentOnSurface,
    surface = ParchmentSurface,
    onSurface = ParchmentOnSurface,
    surfaceVariant = ParchmentSurfaceVariant,
    onSurfaceVariant = ParchmentOnSurfaceMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = ParchmentBackground,
    surfaceContainer = ParchmentSurfaceVariant,
    surfaceContainerHigh = ParchmentSurfaceHigh,
    outline = ParchmentOutline,
    outlineVariant = Color(0xFFDFDBD0)
)

val ExamForgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun ExamForgeTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val colorScheme = if (isDark) ExamForgeDarkColorScheme else ExamForgeLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExamForgeTypography,
        shapes = ExamForgeShapes,
        content = content
    )
}
