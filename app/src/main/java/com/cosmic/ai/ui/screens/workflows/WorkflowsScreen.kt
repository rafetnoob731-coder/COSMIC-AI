// language: kotlin, file: WorkflowsScreen.kt, runtime: Compose, target: COSMIC FLOW placeholder spine
package com.cosmic.ai.ui.screens.workflows

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("COSMIC FLOW") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Workflow engine", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sequences of agent + tool steps with approval checkpoints. V2 surface — structure reserved; triggers and history land with orchestration pass.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Example · Project monitor")
                    Text("1 Check repo  2 Detect changes  3 Run tests  4 Analyze  5 Report  6 Approval", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
