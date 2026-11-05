package com.cosmic.ai.ui.screens.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.ui.theme.CodexPalette
import com.cosmic.ai.ui.theme.CosmicBackdrop
import com.cosmic.ai.ui.theme.CosmicPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(onBack: () -> Unit, onSettings: () -> Unit = {}) {
    val app = CosmicApplication.instance
    val mcp = app.mcpManager
    val states by mcp.states.collectAsState()
    val mcpTools by mcp.tools.collectAsState()
    val localTools = remember { app.agentRuntime.registeredToolDefinitions() }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Tools", "MCP Servers")
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(title = { Text("Tools & MCP") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            })
        }
    ) { padding ->
        CosmicBackdrop(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 16.dp, containerColor = Color.Transparent, divider = {}) {
                    tabs.forEachIndexed { index, title -> Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) }) }
                }
                if (selectedTab == 0) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { Text("Registered COSMIC tools", style = MaterialTheme.typography.titleMedium) }
                        item { Text("These tools are registered in the current agent runtime. Each call is subject to the security gateway and the active permission policy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(localTools, key = { it.name }) { tool ->
                            CosmicPanel(Modifier.fillMaxWidth()) {
                                ListItem(
                                    headlineContent = { Text(tool.name) }, supportingContent = { Text(tool.description) },
                                    leadingContent = { Icon(toolIcon(tool.name), null, tint = CodexPalette.Cyan) },
                                    trailingContent = { Text("Registered", style = MaterialTheme.typography.labelSmall, color = CodexPalette.Green) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }
                        if (mcpTools.isNotEmpty()) item { Text("MCP tools · ${mcpTools.size}", style = MaterialTheme.typography.titleSmall) }
                        items(mcpTools, key = { "${it.serverId}:${it.name}" }) { tool ->
                            CosmicPanel(Modifier.fillMaxWidth()) {
                                ListItem(headlineContent = { Text(tool.name) }, supportingContent = { Text(tool.description) }, leadingContent = { Icon(Icons.Outlined.Extension, null, tint = CodexPalette.Purple) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("Model Context Protocol", style = MaterialTheme.typography.titleMedium)
                                    Text("Connected servers and discovered tools", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Configure MCP") }
                            }
                        }
                        if (states.isEmpty()) item {
                            CosmicPanel(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp)) {
                                    Text("No MCP connections", style = MaterialTheme.typography.titleSmall)
                                    Text("Add a supported server in Settings → MCP Servers. COSMIC will only show live connection states here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = onSettings) { Text("Open MCP settings") }
                                }
                            }
                        }
                        items(states.entries.toList(), key = { it.key }) { (id, state) ->
                            CosmicPanel(Modifier.fillMaxWidth()) {
                                ListItem(
                                    headlineContent = { Text(id.take(18)) }, supportingContent = { Text(state.toString()) },
                                    leadingContent = { Icon(Icons.Outlined.Language, null, tint = if (state is com.cosmic.ai.domain.mcp.McpConnectionState.Connected) CodexPalette.Green else CodexPalette.Purple) },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun toolIcon(name: String) = when {
    name.contains("file") -> Icons.Outlined.FolderOpen
    name.contains("command") -> Icons.Outlined.Terminal
    name.contains("search") -> Icons.Outlined.Search
    name.startsWith("git") -> Icons.Outlined.AccountTree
    else -> Icons.Outlined.Build
}
