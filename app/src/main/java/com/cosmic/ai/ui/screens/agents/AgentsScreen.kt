// language: kotlin, file: AgentsScreen.kt, runtime: Compose, target: specialist agent roster
package com.cosmic.ai.ui.screens.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmic.ai.agents.SpecialistAgents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CODEX AGENTS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Specialist agents share COSMIC CORE. Permissions and tools are role-scoped.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(SpecialistAgents.all) { agent ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(agent.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(agent.role.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(agent.systemPrompt, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Tools: ${agent.allowedTools.joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        agent.preferredModelHint?.let {
                            Text("Hint model: $it", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
