package com.cosmic.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color

private val CodexDark = darkColorScheme(
    primary = Color(0xFF9E33FF), onPrimary = Color.White,
    secondary = Color(0xFF24D8F2), onSecondary = Color(0xFF06121A),
    tertiary = Color(0xFF30E8B3), background = Color(0xFF070811),
    onBackground = Color(0xFFF5F5FA), surface = Color(0xFF141626),
    onSurface = Color(0xFFF5F5FA), surfaceVariant = Color(0xFF1A1D32),
    onSurfaceVariant = Color(0xFF9BA0B8), outline = Color(0xFF302A4A),
    error = Color(0xFFFF647C), onError = Color.White
)

private val CodexLight = lightColorScheme(
    primary = Color(0xFF6D28D9), onPrimary = Color.White,
    secondary = Color(0xFF078EA8), onSecondary = Color.White,
    tertiary = Color(0xFF178B6D), background = Color(0xFFF7F6FB),
    onBackground = Color(0xFF181522), surface = Color.White,
    onSurface = Color(0xFF181522), surfaceVariant = Color(0xFFF0EDF7),
    onSurfaceVariant = Color(0xFF696477), outline = Color(0xFFDAD5E5),
    error = Color(0xFFB4233D), onError = Color.White
)

@Composable
fun CosmicTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode.lowercase()) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val target = if (dark) CodexDark else CodexLight
    val animatedScheme = target.copy(
        primary = animateColorAsState(target.primary, tween(220), label = "theme-primary").value,
        secondary = animateColorAsState(target.secondary, tween(220), label = "theme-secondary").value,
        background = animateColorAsState(target.background, tween(220), label = "theme-background").value,
        surface = animateColorAsState(target.surface, tween(220), label = "theme-surface").value,
        surfaceVariant = animateColorAsState(target.surfaceVariant, tween(220), label = "theme-surface-variant").value,
        onBackground = animateColorAsState(target.onBackground, tween(220), label = "theme-text").value,
        onSurface = animateColorAsState(target.onSurface, tween(220), label = "theme-surface-text").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, tween(220), label = "theme-muted").value,
        outline = animateColorAsState(target.outline, tween(220), label = "theme-outline").value
    )
    MaterialTheme(colorScheme = animatedScheme, typography = CosmicTypography, content = content)
}
