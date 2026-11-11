package com.local.comicreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.local.comicreader.data.ThemeMode

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF00294D),
    secondary = Color(0xFF565E71),
    surface = Color(0xFFF8F9FC),
    background = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1A1B20),
    onBackground = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF00325D),
    primaryContainer = Color(0xFF004886),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFBEC6DC),
    surface = Color(0xFF121318),
    background = Color(0xFF121318),
    onSurface = Color(0xFFE2E2E9),
    onBackground = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC5C6D0)
)

@Composable
fun NewmanTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content
    )
}
