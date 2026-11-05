package com.cosmic.ai.ui.screens.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmic.ai.runtime.tools.RunCommandTool
import com.cosmic.ai.runtime.tools.ToolResult
import com.cosmic.ai.ui.theme.CodexPalette
import com.cosmic.ai.ui.theme.CosmicBackdrop
import com.cosmic.ai.ui.theme.CosmicPanel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var lastExitCode by remember { mutableStateOf<Int?>(null) }
    var lastSucceeded by remember { mutableStateOf(true) }
    val tool = remember { RunCommandTool(context) }
    val history = remember { mutableStateListOf<String>() }
    val scroll = rememberScrollState()
    fun runCommand(value: String) {
        if (value.isBlank() || running) return
        val submitted = value.trim()
        history.add(submitted)
        command = ""
        running = true
        scope.launch {
            val result = tool.performRun("""{"command":${org.json.JSONObject.quote(submitted)}}""")
            when (result) {
                is ToolResult.Success -> {
                    output += if (output.isBlank()) "$ $submitted\n${result.output}\n" else "\n$ $submitted\n${result.output}\n"
                    lastExitCode = Regex("""exit=(-?\d+)""").find(result.output)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    lastSucceeded = lastExitCode == 0
                }
                is ToolResult.Failure -> {
                    output += "\n$ $submitted\nERROR: ${result.error}\n"
                    lastExitCode = null
                    lastSucceeded = false
                }
                is ToolResult.NeedsApproval -> {
                    output += "\n$ $submitted\nApproval required: ${result.reason}\n"
                    lastExitCode = null
                    lastSucceeded = false
                }
            }
            running = false
        }
    }
    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(title = { Text("Terminal") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            })
        }
    ) { padding ->
        CosmicBackdrop(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Terminal, null, tint = CodexPalette.Cyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Android app shell", style = MaterialTheme.typography.labelMedium)
                    }
                    Text("App sandbox", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CosmicPanel(Modifier.weight(1f).fillMaxWidth().animateContentSize(tween(180))) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { output = ""; lastExitCode = null; lastSucceeded = true }) { Icon(Icons.Outlined.DeleteSweep, null); Spacer(Modifier.width(4.dp)); Text("Clear") }
                            TextButton(enabled = output.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(output)) }) { Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("Copy") }
                            if (history.isNotEmpty()) Text("${history.size} commands", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                        }
                        HorizontalDivider(color = CodexPalette.Line)
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(top = 12.dp)) {
                            Text(
                                output.ifBlank { "Commands run under the COSMIC app UID and Android sandbox. A full Linux development environment is not available here." },
                                color = if (lastSucceeded) Color(0xFFB6F5D3) else Color(0xFFFF8796),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = running, enter = fadeIn(tween(120))) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = command, onValueChange = { command = it }, modifier = Modifier.weight(1f),
                        label = { Text("Type a command") }, singleLine = true, enabled = !running,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { runCommand(command) })
                    )
                    FilledIconButton(
                        enabled = !running && command.isNotBlank(),
                        onClick = { runCommand(command) }
                    ) {
                        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.PlayArrow, contentDescription = "Execute command")
                    }
                }
            }
        }
    }
}
