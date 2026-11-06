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
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.data.local.datastore.AppPreferences
import com.cosmic.ai.data.local.db.SecurityAuditRepository
import com.cosmic.ai.security.ApprovalAuthorization
import java.util.UUID
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.runtime.tools.ToolRegistry
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
    val preferences = remember(context) { AppPreferences(context) }
    val terminalAllowed by preferences.allowTerminal.collectAsState(initial = false)
    val requireApproval by preferences.requireApproval.collectAsState(initial = true)
    val tools = remember(context) { ToolRegistry(context, CosmicApplication.instance.permissionGateway) }
    val history = remember { mutableStateListOf<String>() }
    val audit = remember { SecurityAuditRepository(CosmicApplication.instance.database) }
    val pendingApprovals by CosmicApplication.instance.agentRuntime.pendingApprovals().collectAsState(initial = emptyList())
    var terminalRequestId by remember { mutableStateOf<String?>(null) }
    var terminalCommand by remember { mutableStateOf<String?>(null) }
    var approvalDetails by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    fun auditScope(commandText: String): String {
        val json = """{"command":${org.json.JSONObject.quote(commandText)}}"""
        return "command sha256:${tools.approvalScope("run_command", json).take(16)}"
    }
    fun runCommand(value: String, authorization: ApprovalAuthorization? = null) {
        if (value.isBlank() || running || pendingApprovals.any { it.agentId == "terminal" }) return
        val submitted = value.trim()
        history.add(submitted)
        command = ""
        running = true
        scope.launch {
            val args = """{"command":${org.json.JSONObject.quote(submitted)}}"""
            val policy = PermissionPolicy(
                allowFileRead = false,
                allowFileWrite = false,
                allowTerminal = terminalAllowed,
                allowNetwork = false,
                requireApprovalForDestructive = requireApproval
            )
            val operationId = authorization?.requestId ?: UUID.randomUUID().toString()
            val result = tools.execute("run_command", args, policy, null, authorization,
                if (authorization == null) null else "terminal:${authorization.requestId}",
                if (authorization == null) null else authorization.requestId,
                onAuthorized = {
                    audit.record(operationId, if (authorization == null) null else "terminal:${authorization.requestId}",
                        "user", "run_command", "execute", auditScope(submitted), "EXECUTION_STARTED")
                })
            when (result) {
                is ToolResult.Success -> {
                    output += if (output.isBlank()) "$ $submitted\n${result.output}\n" else "\n$ $submitted\n${result.output}\n"
                    lastExitCode = Regex("""exit=(-?\d+)""").find(result.output)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    lastSucceeded = lastExitCode == 0
                    audit.record(operationId, null, "user", "run_command", "execute", auditScope(submitted), "EXECUTION_COMPLETED", "exit=${lastExitCode ?: "unknown"}")
                }
                is ToolResult.Unknown -> {
                    output += "\n$ $submitted\nOUTCOME UNKNOWN: ${result.reason}\n"
                    lastExitCode = null
                    lastSucceeded = false
                    audit.record(operationId, null, "user", "run_command", "execute", auditScope(submitted), "OUTCOME_UNKNOWN", error = result.reason)
                }
                is ToolResult.Failure -> {
                    output += "\n$ $submitted\nERROR: ${result.error}\n"
                    lastExitCode = null
                    lastSucceeded = false
                    audit.record(operationId, null, "user", "run_command", "execute", auditScope(submitted), "EXECUTION_FAILED", error = result.error)
                }
                is ToolResult.NeedsApproval -> {
                    val request = CosmicApplication.instance.agentRuntime.requestTerminalApproval(submitted, result.reason)
                    terminalCommand = submitted
                    terminalRequestId = request.id
                    lastExitCode = null
                    lastSucceeded = false
                }
            }
            running = false
        }
    }
    LaunchedEffect(terminalRequestId) {
        val requestId = terminalRequestId ?: return@LaunchedEffect
        CosmicApplication.instance.agentRuntime.events.collect { event ->
            if (event is com.cosmic.ai.runtime.agent.AgentUiEvent.ApprovalResolved && event.requestId == requestId) {
                if (event.status == "COMPLETED") {
                    output += "\n$ ${terminalCommand ?: "approved command"}\n${event.result.orEmpty()}\n"
                    lastSucceeded = Regex("""exit=(-?\d+)""").find(event.result.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() == 0
                } else if (event.status in setOf("DENIED", "CANCELLED", "EXPIRED", "FAILED", "UNKNOWN")) {
                    output += "\n${event.status}: ${event.message}\n"
                    lastSucceeded = false
                }
                if (event.status in setOf("DENIED", "CANCELLED", "EXPIRED", "FAILED", "UNKNOWN", "COMPLETED")) {
                    terminalRequestId = null
                    terminalCommand = null
                }
            }
        }
    }
    val activeRequest = pendingApprovals.firstOrNull { it.agentId == "terminal" }
    if (activeRequest != null) {
        LaunchedEffect(activeRequest.id) {
            approvalDetails = CosmicApplication.instance.agentRuntime.approvalDetails(activeRequest.id)
            terminalRequestId = activeRequest.id
        }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Terminal permission required") },
            text = { Text("${approvalDetails.ifBlank { "Loading exact operation…" }}\n\nReason: ${activeRequest.reason}\n\nThis approval is bound to this exact command and expires in ten minutes.") },
            confirmButton = {
                if (activeRequest.status == "PENDING") {
                    TextButton(onClick = { CosmicApplication.instance.agentRuntime.resolveApproval(activeRequest.id, false) }) { Text("Deny") }
                    TextButton(onClick = { CosmicApplication.instance.agentRuntime.resolveApproval(activeRequest.id, true) }) { Text("Allow once") }
                } else {
                    TextButton(onClick = { CosmicApplication.instance.agentRuntime.continueApprovedApproval(activeRequest.id) }) { Text("Continue") }
                }
            },
            dismissButton = {
                TextButton(onClick = { CosmicApplication.instance.agentRuntime.cancelApproval(activeRequest.id) }) { Text("Cancel") }
            }
        )
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
