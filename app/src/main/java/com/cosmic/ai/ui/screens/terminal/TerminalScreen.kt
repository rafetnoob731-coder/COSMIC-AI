// language: kotlin, file: TerminalScreen.kt, runtime: Compose, target: local shell
package com.cosmic.ai.ui.screens.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmic.ai.runtime.tools.RunCommandTool
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val tool = remember { RunCommandTool(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Text(
                output.ifBlank { "Shell sandbox — commands run under app UID." },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Command") },
                singleLine = true,
                enabled = !running
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (command.isBlank()) return@Button
                    running = true
                    scope.launch {
                        val result = tool.performRun("""{"command":${org.json.JSONObject.quote(command)}}""")
                        output += "\n$ $command\n" + when (result) {
                            is com.cosmic.ai.runtime.tools.ToolResult.Success -> result.output
                            is com.cosmic.ai.runtime.tools.ToolResult.Failure -> "ERROR: ${result.error}"
                            else -> "pending"
                        } + "\n"
                        running = false
                        command = ""
                    }
                },
                enabled = !running && command.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (running) "Running…" else "Execute")
            }
        }
    }
}
