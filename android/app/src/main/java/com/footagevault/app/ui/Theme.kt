package com.footagevault.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same palette as the PC app (Google Photos-like Material 3 blue).
private val Light = lightColorScheme(
    primary = Color(0xFF0B57D0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD), onPrimaryContainer = Color(0xFF041E49),
    secondaryContainer = Color(0xFFD3E3FD), onSecondaryContainer = Color(0xFF041E49),
    background = Color.White, onBackground = Color(0xFF1F1F1F),
    surface = Color.White, onSurface = Color(0xFF1F1F1F), onSurfaceVariant = Color(0xFF444746),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAFD), surfaceContainer = Color(0xFFF0F4F9),
    surfaceContainerHigh = Color(0xFFE9EEF6), surfaceContainerHighest = Color(0xFFDDE3EA),
    outline = Color(0xFF747775), outlineVariant = Color(0xFFC4C7C5),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC),
    tertiaryContainer = Color(0xFFFFDF99), onTertiaryContainer = Color(0xFF261A00),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0), onPrimaryContainer = Color(0xFFD3E3FD),
    secondaryContainer = Color(0xFF0842A0), onSecondaryContainer = Color(0xFFD3E3FD),
    background = Color(0xFF131314), onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131314), onSurface = Color(0xFFE3E3E3), onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceContainerLowest = Color(0xFF0E0E0F), surfaceContainerLow = Color(0xFF1B1B1C), surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C), surfaceContainerHighest = Color(0xFF333537),
    outline = Color(0xFF8E918F), outlineVariant = Color(0xFF444746),
    error = Color(0xFFF2B8B5), errorContainer = Color(0xFF601410),
    tertiaryContainer = Color(0xFF5C4300), onTertiaryContainer = Color(0xFFFFDF99),
)
/** Pure black: OLED pixels switch off, which looks deep and saves battery. */
private val Black = Dark.copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0A0A0A), surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A), surfaceContainerHighest = Color(0xFF242424),
)

@Composable
fun isDark(mode: String) = when (mode) { "light" -> false; "dark", "black" -> true; else -> isSystemInDarkTheme() }

@Composable
fun VaultTheme(mode: String = "system", content: @Composable () -> Unit) {
    val scheme = when {
        mode == "black" -> Black
        isDark(mode) -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
