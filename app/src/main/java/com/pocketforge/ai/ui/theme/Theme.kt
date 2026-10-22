package com.pocketforge.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC1FF),
    onPrimary = Color(0xFF002F66),
    primaryContainer = Color(0xFF1D4583),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB7C6E0),
    onSecondary = Color(0xFF22303F),
    secondaryContainer = Color(0xFF38495A),
    onSecondaryContainer = Color(0xFFD3E4F7),
    tertiary = Color(0xFFD7C2EA),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE0E2EA),
    surface = Color(0xFF101318),
    onSurface = Color(0xFFE0E2EA),
    surfaceVariant = Color(0xFF41475C),
    onSurfaceVariant = Color(0xFFC1C7D8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1859A9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF525F70),
    background = Color(0xFFFBF8FD),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFBF8FD),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    error = Color(0xFFBA1A1A),
)

@Composable
fun PocketForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
