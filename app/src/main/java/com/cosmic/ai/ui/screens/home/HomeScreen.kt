// language: kotlin, file: HomeScreen.kt, runtime: Compose, target: COSMIC STUDIO home
package com.cosmic.ai.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cosmic.ai.ui.navigation.Routes

private data class HomeTile(val title: String, val subtitle: String, val icon: ImageVector, val route: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNavigate: (String) -> Unit, onSettings: () -> Unit) {
    val tiles = listOf(
        HomeTile("Agent", "Chat · Plan · Build", Icons.Outlined.SmartToy, Routes.AGENT),
        HomeTile("Projects", "SAF project folders", Icons.Outlined.Folder, Routes.PROJECTS),
        HomeTile("Terminal", "Sandboxed shell", Icons.Outlined.Terminal, Routes.TERMINAL),
        HomeTile("Agents", "Specialist roles", Icons.Outlined.Groups, Routes.AGENTS),
        HomeTile("Tasks", "Task state machine", Icons.Outlined.ViewTimeline, Routes.TASKS),
        HomeTile("Sessions", "Conversation history", Icons.Outlined.History, Routes.SESSIONS),
        HomeTile("Workflows", "COSMIC FLOW", Icons.Outlined.AccountTree, Routes.WORKFLOWS),
        HomeTile("MCP", "Tool servers", Icons.Outlined.Extension, Routes.MCP),
        HomeTile("Memory", "Four-layer memory", Icons.Outlined.Memory, Routes.MEMORY),
        HomeTile("Bug Center", "Report and track issues", Icons.Outlined.BugReport, Routes.BUGS),
        HomeTile("Settings", "Providers · security", Icons.Outlined.Settings, Routes.SETTINGS)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("COSMIC AI", fontWeight = FontWeight.Bold)
                        Text("One Intelligence. Infinite Possibilities.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tiles) { tile ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clickable { onNavigate(tile.route) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Icon(tile.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text(tile.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(tile.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
