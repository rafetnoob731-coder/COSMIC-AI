package com.cosmic.ai.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.ui.navigation.Routes
import com.cosmic.ai.ui.theme.*

private data class QuickLink(val title: String, val icon: ImageVector, val route: String, val tint: Color)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onSettings: () -> Unit,
    onOpenConversation: (String) -> Unit = {}
) {
    val database = CosmicApplication.instance.database
    val sessions by database.sessionDao().observeAll().collectAsState(initial = emptyList())
    val providers by database.providerDao().observeAll().collectAsState(initial = emptyList())
    val tasks by database.taskDao().observeAll().collectAsState(initial = emptyList())
    val quickLinks = listOf(
        QuickLink("Projects", Icons.Outlined.Folder, Routes.PROJECTS, CodexPalette.Cyan),
        QuickLink("Agents", Icons.Outlined.Groups, Routes.AGENTS, CodexPalette.Purple),
        QuickLink("Terminal", Icons.Outlined.Terminal, Routes.TERMINAL, CodexPalette.Green),
        QuickLink("Tools", Icons.Outlined.Extension, Routes.MCP, Color(0xFF78A8FF))
    )
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("COSMIC CODEX", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = { CosmicBottomNavigation(Routes.HOME, onNavigate) }
    ) { padding ->
        CosmicBackdrop(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CosmicLogo(Modifier.size(174.dp))
                        Text("Build. Code. Innovate.", style = MaterialTheme.typography.bodyMedium, color = CodexPalette.Cyan)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(7.dp), shape = RoundedCornerShape(50), color = if (providers.isNotEmpty()) CodexPalette.Green else MaterialTheme.colorScheme.onSurfaceVariant) {}
                            Spacer(Modifier.width(7.dp))
                            Text(if (providers.isNotEmpty()) "${providers.size} provider${if (providers.size == 1) "" else "s"} configured" else "Connect an AI provider to begin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeAction(
                            title = "New Chat", subtitle = "Start a conversation", icon = Icons.Outlined.ChatBubbleOutline,
                            tint = Color(0xFF3EB8FF), onClick = { onNavigate(Routes.AGENT) }
                        )
                        HomeAction(
                            title = "New Coding Task", subtitle = "Let agents build", icon = Icons.Outlined.AutoAwesome,
                            tint = CodexPalette.Green, onClick = { onNavigate(Routes.AGENT) }
                        )
                    }
                }
                item {
                    CosmicSectionTitle("Quick Access")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickLinks.forEach { link ->
                            CosmicPanel(Modifier.weight(1f).height(88.dp).clickable { onNavigate(link.route) }) {
                                Column(Modifier.fillMaxSize().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(link.icon, contentDescription = null, tint = link.tint, modifier = Modifier.size(23.dp))
                                    Text(link.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
                item {
                    CosmicSectionTitle("Recent Conversations", if (sessions.isNotEmpty()) "See all" else null) {
                        onNavigate(Routes.SESSIONS)
                    }
                }
                if (sessions.isEmpty()) {
                    item { Text("Your conversations will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                } else {
                    items(sessions.take(4), key = { it.id }) { session ->
                        CosmicPanel(Modifier.fillMaxWidth().clickable { onOpenConversation(session.id) }) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(color = CodexPalette.Violet.copy(alpha = .25f), shape = RoundedCornerShape(10.dp)) {
                                    Icon(Icons.Outlined.ChatBubbleOutline, null, tint = CodexPalette.Cyan, modifier = Modifier.padding(9.dp).size(17.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(session.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(session.model ?: "Conversation", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(relativeTime(session.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                val activeTasks = tasks.filter { it.status in setOf("QUEUED", "STARTING", "RUNNING", "WAITING_FOR_MODEL", "WAITING_FOR_TOOL", "COMPLETING") }
                if (activeTasks.isNotEmpty()) {
                    item { CosmicSectionTitle("Active Tasks", "See all") { onNavigate(Routes.TASKS) } }
                    items(activeTasks.take(3), key = { it.id }) { task ->
                        CosmicPanel(Modifier.fillMaxWidth().clickable { onNavigate(Routes.TASKS) }) {
                            ListItem(
                                headlineContent = { Text(task.progressMessage ?: "Agent task") },
                                supportingContent = { Text("${task.status.lowercase().replace('_', ' ')} · ${task.id.take(8)}") },
                                leadingContent = { Icon(Icons.Outlined.AutoAwesome, null, tint = CodexPalette.Cyan) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeAction(title: String, subtitle: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    CosmicPanel(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = tint.copy(alpha = .17f), shape = RoundedCornerShape(12.dp)) {
                Icon(icon, null, tint = tint, modifier = Modifier.padding(10.dp).size(23.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}
