// language: kotlin, file: BackgroundTasksScreen.kt, runtime: Compose + Room, target: task list
package com.cosmic.ai.ui.screens.background

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.runtime.background.AgentTaskWork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundTasksScreen(onBack: () -> Unit) {
    val db = CosmicApplication.instance.database
    val tasks by db.taskDao().observeAll().collectAsState(initial = emptyList())
    val toolExecutions by db.toolExecutionDao().observeAll().collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
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
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (tasks.isEmpty()) {
                item {
                    Text(
                        "Agent and background executions will appear here with their persisted status and recovery state.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(tasks, key = { it.id }) { t ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(t.status.replace('_', ' '), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text("${((t.updatedAt - t.createdAt).coerceAtLeast(0L) / 1000)}s", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(t.progressMessage ?: "No current stage", style = MaterialTheme.typography.bodySmall)
                        Text("Execution ${t.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Conversation ${t.sessionId.take(8)}… · ${t.modelName ?: "model unavailable"}", style = MaterialTheme.typography.bodySmall)
                        val uncertain = toolExecutions.filter { it.taskId == t.id && it.state == "UNKNOWN" }
                        uncertain.forEach { tool ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(6.dp))
                                Text("${tool.toolName} has an unknown outcome. COSMIC will not replay it automatically.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        toolExecutions.filter { it.taskId == t.id && it.state != "UNKNOWN" }.forEach { tool ->
                            Text("${tool.toolName} · ${tool.state.lowercase()}", style = MaterialTheme.typography.labelSmall)
                            tool.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3) }
                            tool.resultJson?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4) }
                        }
                        if (t.status in setOf("RECOVERY_REQUIRED", "RETRYABLE", "QUEUED")) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { AgentTaskWork.enqueue(CosmicApplication.instance, t.id) }) {
                                Text(if (t.status == "QUEUED") "Resume queued task" else "Resume task")
                            }
                        }
                    }
                }
            }
        }
    }
}
