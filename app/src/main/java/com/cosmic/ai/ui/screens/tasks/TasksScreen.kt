// language: kotlin, file: TasksScreen.kt, runtime: Compose, target: task state machine view
package com.cosmic.ai.ui.screens.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmic.ai.core.state.TaskStateMachine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(onBack: () -> Unit, machine: TaskStateMachine = remember { TaskStateMachine() }) {
    val tasks = remember { machine.all() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (tasks.isEmpty()) {
                item {
                    Text(
                        "No tasks yet. Agent runs create entries in the COSMIC CORE state machine.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(tasks) { t ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier = Modifier.padding(12.dp)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium)
                        Text(t.phase.name, color = MaterialTheme.colorScheme.primary)
                        Text(t.instruction.take(120), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
