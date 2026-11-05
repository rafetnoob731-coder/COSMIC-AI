// language: kotlin, file: Theme.kt, runtime: Compose, target: Material3
package com.cosmic.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CosmicDark = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1E1B4B),
    secondary = Color(0xFF8B8FA8),
    onSecondary = Color(0xFF0F172A),
    tertiary = Color(0xFF8B7BD8),
    background = Color(0xFF101114),
    onBackground = Color(0xFFF3F3F5),
    surface = Color(0xFF202127),
    onSurface = Color(0xFFF3F3F5),
    surfaceVariant = Color(0xFF26272E),
    onSurfaceVariant = Color(0xFF9699A3),
    error = Color(0xFFF87171),
    onError = Color(0xFFFFFFFF)
)

private val CosmicLight = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2563EB),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF5B21B6),
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF3F3F5),
    onSurfaceVariant = Color(0xFF62646D),
    error = Color(0xFFB42318),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun CosmicTheme(
    mode: String = "system",
    content: @Composable () -> Unit
) {
    val darkTheme = when (mode.lowercase()) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val scheme = if (darkTheme) CosmicDark else CosmicLight
    MaterialTheme(
        colorScheme = scheme,
        typography = CosmicTypography,
        content = content
    )
}
