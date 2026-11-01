// language: kotlin, file: MemoryScreen.kt, runtime: Compose, target: memory inspection
package com.cosmic.ai.ui.screens.memory

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("COSMIC MEMORY") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "SESSION" to "Messages, tool calls, execution state",
                "PROJECT" to "Structure, conventions, known issues",
                "USER" to "Preferred models, providers, UI",
                "KNOWLEDGE" to "Imported docs and reusable references"
            ).forEach { (layer, desc) ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(layer, style = MaterialTheme.typography.titleMedium)
                        Text(desc, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(
                "Room-backed storage. Secrets never enter semantic memory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
