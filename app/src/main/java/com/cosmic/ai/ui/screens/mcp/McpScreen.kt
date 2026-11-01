// language: kotlin, file: McpScreen.kt, runtime: Compose, target: dedicated MCP studio
package com.cosmic.ai.ui.screens.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(onBack: () -> Unit) {
    val mcp = CosmicApplication.instance.mcpManager
    val states by mcp.states.collectAsState()
    val tools by mcp.tools.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Model Context Protocol", style = MaterialTheme.typography.titleMedium)
                Text("Configure servers under Settings → MCP. Live tools appear here.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("Connection states", style = MaterialTheme.typography.titleSmall)
            }
            items(states.entries.toList()) { (id, st) ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(id.take(8) + "…", style = MaterialTheme.typography.labelMedium)
                        Text(st.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text("Tools (${tools.size})", style = MaterialTheme.typography.titleSmall) }
            items(tools) { t ->
                Text("· ${t.name} — ${t.description.take(80)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
