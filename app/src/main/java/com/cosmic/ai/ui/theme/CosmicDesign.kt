package com.cosmic.ai.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cosmic.ai.R
import com.cosmic.ai.ui.navigation.Routes
import kotlin.random.Random

object CodexPalette {
    val Night = Color(0xFF070811)
    val Space = Color(0xFF0B0D1B)
    val Panel = Color(0xFF141626)
    val PanelRaised = Color(0xFF1A1D32)
    val Line = Color(0xFF302A4A)
    val Violet = Color(0xFF7C26FF)
    val Purple = Color(0xFFAD35FF)
    val Cyan = Color(0xFF18D9F3)
    val Muted = Color(0xFF9BA0B8)
    val Green = Color(0xFF30E8B3)
}

@Composable
fun CosmicBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val stars = remember { List(68) { Random(9127 + it).nextFloat() to Random(2713 + it).nextFloat() } }
    Box(modifier.background(Brush.verticalGradient(listOf(CodexPalette.Night, CodexPalette.Space, Color(0xFF0E1024))))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.radialGradient(listOf(Color(0x332A24A6), Color.Transparent), center = Offset(size.width * .5f, size.height * .42f), radius = size.width * .66f), radius = size.width * .66f, center = Offset(size.width * .5f, size.height * .42f))
            stars.forEachIndexed { index, point ->
                val radius = if (index % 13 == 0) 1.8f else if (index % 4 == 0) 1.1f else .65f
                drawCircle(Color.White.copy(alpha = if (index % 7 == 0) .75f else .28f), radius, Offset(point.first * size.width, point.second * size.height))
            }
            drawArc(Brush.horizontalGradient(listOf(CodexPalette.Purple.copy(alpha = .35f), CodexPalette.Cyan.copy(alpha = .35f))), 198f, 145f, false, Offset(-size.width * .12f, size.height * .46f), androidx.compose.ui.geometry.Size(size.width * 1.25f, size.height * .34f), style = Stroke(1.dp.toPx()))
        }
        content()
    }
}

@Composable
fun CosmicBottomNavigation(selectedRoute: String, onNavigate: (String) -> Unit) {
    val items = listOf(
        Triple("Home", Routes.HOME, Icons.Outlined.Home),
        Triple("Chat", Routes.AGENT, Icons.AutoMirrored.Outlined.Chat),
        Triple("Projects", Routes.PROJECTS, Icons.Outlined.Folder),
        Triple("Agents", Routes.AGENTS, Icons.Outlined.Groups),
        Triple("Tasks", Routes.TASKS, Icons.Outlined.ViewTimeline)
    )
    NavigationBar(containerColor = Color(0xF2111221), tonalElevation = 0.dp, windowInsets = WindowInsets.navigationBars) {
        items.forEach { (label, route, icon) ->
            NavigationBarItem(
                selected = selectedRoute == route,
                onClick = { if (selectedRoute != route) onNavigate(route) },
                icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(21.dp)) },
                label = { Text(label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = CodexPalette.Cyan,
                    selectedTextColor = CodexPalette.Cyan,
                    indicatorColor = CodexPalette.Violet.copy(alpha = .27f),
                    unselectedIconColor = Color(0xFFB7B7C6),
                    unselectedTextColor = Color(0xFFB7B7C6)
                )
            )
        }
    }
}

@Composable
fun CosmicPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        color = CodexPalette.Panel.copy(alpha = .94f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CodexPalette.Line.copy(alpha = .8f)),
        tonalElevation = 0.dp
    ) { Column(content = content) }
}

@Composable
fun CosmicLogo(modifier: Modifier = Modifier, description: String = "COSMIC CODEX") {
    androidx.compose.foundation.Image(
        painter = painterResource(R.drawable.cosmic_codex_full_logo),
        contentDescription = description,
        modifier = modifier
    )
}

@Composable
fun CosmicSectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(action, color = CodexPalette.Cyan) }
    }
}

@Composable
fun CosmicGradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CodexPalette.Violet, contentColor = Color.White, disabledContainerColor = CodexPalette.Violet.copy(alpha = .4f)),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}
