// language: kotlin, file: BackgroundTasksScreen.kt, runtime: Compose + Room, target: task list
package com.cosmic.ai.ui.screens.background

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
import com.cosmic.ai.runtime.background.AgentTaskWork

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundTasksScreen(onBack: () -> Unit) {
    val tasks by CosmicApplication.instance.database.taskDao().observeAll()
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Background Tasks") },
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
                        "No persisted agent tasks yet. Tasks interrupted by process death are preserved here for review and recovery.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(tasks, key = { it.id }) { t ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(t.status, style = MaterialTheme.typography.titleMedium)
                        Text(t.progressMessage ?: "", style = MaterialTheme.typography.bodySmall)
                        Text("Session: ${t.sessionId.take(8)}…", style = MaterialTheme.typography.bodySmall)
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
