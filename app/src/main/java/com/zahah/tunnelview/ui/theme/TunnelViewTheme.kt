package com.zahah.tunnelview.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.R

private val BaseLightColorScheme = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF001356),
    secondary = Color(0xFF5E5CE6),
    onSecondary = Color.White,
    tertiary = Color(0xFF1EB980),
    onTertiary = Color.White,
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1C1F26),
    surface = Color.White,
    onSurface = Color(0xFF1C1F26),
    surfaceVariant = Color(0xFFE2E5F2),
    onSurfaceVariant = Color(0xFF444957),
    outline = Color(0xFF757B8A),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val BaseDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB7C4FF),
    onPrimary = Color(0xFF0B1C5C),
    primaryContainer = Color(0xFF1E2E7A),
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFFB5B1FF),
    onSecondary = Color(0xFF1F1B5B),
    tertiary = Color(0xFF78D0A5),
    onTertiary = Color(0xFF003821),
    background = Color(0xFF0E1118),
    onBackground = Color(0xFFE7EAF6),
    surface = Color(0xFF151923),
    onSurface = Color(0xFFE7EAF6),
    surfaceVariant = Color(0xFF2B3042),
    onSurfaceVariant = Color(0xFFC3C7D6),
    outline = Color(0xFF8D92A3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Immutable
enum class ThemeModeOption(
    val id: String,
    @StringRes val labelRes: Int,
) {
    SYSTEM(id = Prefs.DEFAULT_THEME_MODE, labelRes = R.string.theme_mode_option_system),
    LIGHT(id = "light", labelRes = R.string.theme_mode_option_light),
    DARK(id = "dark", labelRes = R.string.theme_mode_option_dark);

    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromId(id: String?): ThemeModeOption {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: SYSTEM
        }
    }
}

@Immutable
enum class ThemeColorOption(
    val id: String,
    @StringRes val labelRes: Int,
    val lightPrimary: Color,
    val lightOnPrimary: Color,
    val lightPrimaryContainer: Color,
    val lightOnPrimaryContainer: Color,
    val lightSecondary: Color,
    val lightOnSecondary: Color,
    val lightTertiary: Color,
    val lightOnTertiary: Color,
    val darkPrimary: Color,
    val darkOnPrimary: Color,
    val darkPrimaryContainer: Color,
    val darkOnPrimaryContainer: Color,
    val darkSecondary: Color,
    val darkOnSecondary: Color,
    val darkTertiary: Color,
    val darkOnTertiary: Color,
) {
    INDIGO(
        id = Prefs.DEFAULT_THEME_COLOR,
        labelRes = R.string.theme_color_option_indigo,
        lightPrimary = Color(0xFF3D5AFE),
        lightOnPrimary = Color.White,
        lightPrimaryContainer = Color(0xFFE0E7FF),
        lightOnPrimaryContainer = Color(0xFF001356),
        lightSecondary = Color(0xFF5E5CE6),
        lightOnSecondary = Color.White,
        lightTertiary = Color(0xFF1EB980),
        lightOnTertiary = Color.White,
        darkPrimary = Color(0xFFB7C4FF),
        darkOnPrimary = Color(0xFF0B1C5C),
        darkPrimaryContainer = Color(0xFF1E2E7A),
        darkOnPrimaryContainer = Color(0xFFDDE2FF),
        darkSecondary = Color(0xFFB5B1FF),
        darkOnSecondary = Color(0xFF1F1B5B),
        darkTertiary = Color(0xFF78D0A5),
        darkOnTertiary = Color(0xFF003821)
    ),
    PURPLE(
        id = "purple",
        labelRes = R.string.theme_color_option_purple,
        lightPrimary = Color(0xFF6750A4),
        lightOnPrimary = Color.White,
        lightPrimaryContainer = Color(0xFFEADDFF),
        lightOnPrimaryContainer = Color(0xFF21005D),
        lightSecondary = Color(0xFF625B71),
        lightOnSecondary = Color.White,
        lightTertiary = Color(0xFF7D5260),
        lightOnTertiary = Color.White,
        darkPrimary = Color(0xFFD0BCFF),
        darkOnPrimary = Color(0xFF371E73),
        darkPrimaryContainer = Color(0xFF4F378B),
        darkOnPrimaryContainer = Color(0xFFEADDFF),
        darkSecondary = Color(0xFFCCC2DC),
        darkOnSecondary = Color(0xFF332D41),
        darkTertiary = Color(0xFFEFB8C8),
        darkOnTertiary = Color(0xFF492532)
    ),
    EMERALD(
        id = "emerald",
        labelRes = R.string.theme_color_option_emerald,
        lightPrimary = Color(0xFF0F9D58),
        lightOnPrimary = Color.White,
        lightPrimaryContainer = Color(0xFFCFF7DE),
        lightOnPrimaryContainer = Color(0xFF00210C),
        lightSecondary = Color(0xFF0B7D43),
        lightOnSecondary = Color.White,
        lightTertiary = Color(0xFF1EB980),
        lightOnTertiary = Color.White,
        darkPrimary = Color(0xFF8CE2AD),
        darkOnPrimary = Color(0xFF00391A),
        darkPrimaryContainer = Color(0xFF00522A),
        darkOnPrimaryContainer = Color(0xFFB2F6C9),
        darkSecondary = Color(0xFF5ED192),
        darkOnSecondary = Color(0xFF00391C),
        darkTertiary = Color(0xFF9CF3C7),
        darkOnTertiary = Color(0xFF003823)
    ),
    SUNSET(
        id = "sunset",
        labelRes = R.string.theme_color_option_sunset,
        lightPrimary = Color(0xFFEF6C00),
        lightOnPrimary = Color.White,
        lightPrimaryContainer = Color(0xFFFFDCC2),
        lightOnPrimaryContainer = Color(0xFF2F1500),
        lightSecondary = Color(0xFFC25E00),
        lightOnSecondary = Color.White,
        lightTertiary = Color(0xFFFF8A65),
        lightOnTertiary = Color.White,
        darkPrimary = Color(0xFFFFB680),
        darkOnPrimary = Color(0xFF4D1F00),
        darkPrimaryContainer = Color(0xFF6C2F00),
        darkOnPrimaryContainer = Color(0xFFFFDCC2),
        darkSecondary = Color(0xFFFFB782),
        darkOnSecondary = Color(0xFF4E1F00),
        darkTertiary = Color(0xFFFFB59C),
        darkOnTertiary = Color(0xFF4B1400)
    );

    companion object {
        fun fromId(id: String?): ThemeColorOption {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: INDIGO
        }
    }
}

/**
 * Central Compose theme so dark mode renders legible foreground colors on top of dark surfaces.
 */
@Composable
fun TunnelViewTheme(
    themeModeId: String = Prefs.DEFAULT_THEME_MODE,
    colorOptionId: String = Prefs.DEFAULT_THEME_COLOR,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val mode = remember(themeModeId) { ThemeModeOption.fromId(themeModeId) }
    val useDarkTheme = mode.isDark(systemDark)
    val palette = remember(colorOptionId) { ThemeColorOption.fromId(colorOptionId) }
    val colors = remember(useDarkTheme, palette) {
        val base = if (useDarkTheme) BaseDarkColorScheme else BaseLightColorScheme
        if (useDarkTheme) {
            applyPalette(base, palette.darkPrimary, palette.darkOnPrimary, palette.darkPrimaryContainer, palette.darkOnPrimaryContainer, palette.darkSecondary, palette.darkOnSecondary, palette.darkTertiary, palette.darkOnTertiary)
        } else {
            applyPalette(base, palette.lightPrimary, palette.lightOnPrimary, palette.lightPrimaryContainer, palette.lightOnPrimaryContainer, palette.lightSecondary, palette.lightOnSecondary, palette.lightTertiary, palette.lightOnTertiary)
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = Shapes(),
        content = content,
    )
}

private fun applyPalette(
    base: ColorScheme,
    primary: Color,
    onPrimary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    onSecondary: Color,
    tertiary: Color,
    onTertiary: Color,
): ColorScheme {
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        tertiary = tertiary,
        onTertiary = onTertiary,
    )
}
