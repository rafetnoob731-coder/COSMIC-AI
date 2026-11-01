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
    secondary = Color(0xFF3B82F6),
    onSecondary = Color(0xFF0F172A),
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFEF4444),
    onError = Color(0xFFFFFFFF)
)

private val CosmicLight = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2563EB),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF5B21B6),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun CosmicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) CosmicDark else CosmicLight
    MaterialTheme(
        colorScheme = scheme,
        typography = CosmicTypography,
        content = content
    )
}
