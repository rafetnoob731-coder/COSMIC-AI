package com.cosmic.ai.ui.screens.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.ProviderEntity
import com.cosmic.ai.data.local.db.entity.SessionEntity
import com.cosmic.ai.domain.agent.AgentMode
import com.cosmic.ai.domain.provider.ChatMessage
import com.cosmic.ai.domain.provider.ProviderConfig
import com.cosmic.ai.domain.provider.ProviderType
import com.cosmic.ai.runtime.agent.AgentUiEvent
import com.cosmic.ai.core.commands.CommandRegistry
import com.cosmic.ai.core.commands.CosmicCommand
import com.cosmic.ai.ui.navigation.Routes
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(onNavigate: (String) -> Unit, onSettings: () -> Unit, initialSessionId: String? = null, onOpenConversation: (String) -> Unit = {}) {
    val app = CosmicApplication.instance
    val runtime = app.agentRuntime
    val db = app.database
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val providers by db.providerDao().observeAll().collectAsState(initial = emptyList())
    var providerId by remember { mutableStateOf<String?>(null) }
    var sessionId by remember(initialSessionId) { mutableStateOf(initialSessionId ?: UUID.randomUUID().toString()) }
    val sessions by db.sessionDao().observeAll().collectAsState(initial = emptyList())
    var conversationSearch by remember { mutableStateOf("") }
    var conversationActions by remember { mutableStateOf(false) }
    val storedMessages by db.messageDao().observeForSession(sessionId).collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var attachmentName by remember { mutableStateOf<String?>(null) }
    var attachmentText by remember { mutableStateOf<String?>(null) }
    var streaming by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AgentMode.AGENT) }
    var modeMenu by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var executionId by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()
    val selectedProvider = providers.firstOrNull { it.id == providerId }
        ?: providers.firstOrNull { it.isDefault }
        ?: providers.firstOrNull()
    val messages = storedMessages.map { it.toChatMessage() }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var remaining = 1_000_000
                        while (remaining > 0) {
                            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            remaining -= read
                        }
                        if (remaining == 0 && stream.read() >= 0) error("Text attachment exceeds 1 MB")
                        out.toString(Charsets.UTF_8.name())
                    } ?: error("File could not be opened")
                }
                attachmentText = content
                attachmentName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected text file"
                status = "Attached ${attachmentName}"
            } catch (error: Exception) {
                attachmentText = null
                attachmentName = null
                status = "Attachment unavailable: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    LaunchedEffect(providers) {
        if (providerId == null || providers.none { it.id == providerId }) {
            providerId = providers.firstOrNull { it.isDefault }?.id ?: providers.firstOrNull()?.id
        }
    }
    LaunchedEffect(sessionId) {
        if (db.sessionDao().getById(sessionId) == null) {
            val now = System.currentTimeMillis()
            db.sessionDao().upsert(SessionEntity(sessionId, "New conversation", selectedProvider?.id,
                selectedProvider?.defaultModel, null, now, now))
        }
    }
    LaunchedEffect(Unit) {
        runtime.events.collect { event ->
            when (event) {
                is AgentUiEvent.ExecutionStarted -> { executionId = event.taskId; status = "${event.provider} · ${event.model} · ${event.taskId.take(8)}"; busy = true }
                is AgentUiEvent.ExecutionRejected -> { status = event.message; busy = false }
                is AgentUiEvent.MessageAdded -> if (event.message.role == "assistant") streaming = ""
                is AgentUiEvent.StreamDelta -> streaming += event.text
                is AgentUiEvent.Step -> status = when (event.step.type) {
                    com.cosmic.ai.domain.agent.StepType.TOOL_CALL -> "Using ${event.step.toolName ?: "approved tool"}"
                    com.cosmic.ai.domain.agent.StepType.TOOL_RESULT -> "Received ${event.step.toolName ?: "tool result"}"
                    com.cosmic.ai.domain.agent.StepType.ERROR -> "Execution encountered an error"
                    com.cosmic.ai.domain.agent.StepType.SUMMARY -> "Reviewing the result"
                    com.cosmic.ai.domain.agent.StepType.REASONING -> "Working on the request"
                }
                is AgentUiEvent.NeedsApproval -> { status = "Approval required: ${event.toolName}"; busy = false }
                is AgentUiEvent.Completed -> { status = "Completed · ${event.taskId.take(8)}"; busy = false; streaming = "" }
                is AgentUiEvent.Failed -> { status = event.message; executionId = event.taskId; busy = false }
                is AgentUiEvent.Cancelled -> { status = "Cancelled${executionId?.let { " · ${it.take(8)}" } ?: ""}"; busy = false }
            }
        }
    }
    LaunchedEffect(messages.size, streaming) {
        val count = messages.size + if (streaming.isNotBlank()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxHeight(),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 24.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("COSMIC", style = MaterialTheme.typography.titleLarge)
                        Text("AI workspace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                NavigationDrawerItem(
                    label = { Text("New conversation") }, selected = false,
                    icon = { Icon(Icons.Outlined.Add, null) },
                    onClick = { scope.launch { drawerState.close() }; if (!busy) sessionId = UUID.randomUUID().toString() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                OutlinedTextField(
                    value = conversationSearch, onValueChange = { conversationSearch = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("Search conversations") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(16.dp)
                )
                Text("WORKSPACE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 6.dp))
                listOf(
                    Triple("Conversations", Routes.SESSIONS, Icons.Outlined.ChatBubbleOutline),
                    Triple("Automations", Routes.WORKFLOWS, Icons.Outlined.AccountTree),
                    Triple("Projects", Routes.PROJECTS, Icons.Outlined.FolderOpen),
                    Triple("Activity", Routes.BACKGROUND, Icons.Outlined.History)
                ).forEach { (label, route, icon) ->
                    NavigationDrawerItem(label = { Text(label) }, selected = false, icon = { Icon(icon, null) },
                        onClick = { scope.launch { drawerState.close() }; onNavigate(route) }, modifier = Modifier.padding(horizontal = 12.dp))
                }
                val recent = sessions.filter { it.title.contains(conversationSearch, ignoreCase = true) }.take(8)
                if (recent.isNotEmpty()) {
                    Text("RECENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp))
                    recent.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.title, maxLines = 1) }, selected = item.id == sessionId,
                            icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                            onClick = { scope.launch { drawerState.close() }; onOpenConversation(item.id) },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Text("WORKSPACE TOOLS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 4.dp))
                listOf(
                    Triple("Models", Routes.SETTINGS, Icons.Outlined.Tune),
                    Triple("Integrations", Routes.MCP, Icons.Outlined.Extension),
                    Triple("Memory", Routes.MEMORY, Icons.Outlined.Memory),
                    Triple("Settings", Routes.SETTINGS, Icons.Outlined.Settings)
                ).forEach { (label, route, icon) ->
                    NavigationDrawerItem(label = { Text(label) }, selected = false, icon = { Icon(icon, null) },
                        onClick = { scope.launch { drawerState.close() }; if (label == "Models") onSettings() else onNavigate(route) },
                        modifier = Modifier.padding(horizontal = 12.dp))
                }
                ListItem(
                    headlineContent = { Text("Local workspace") },
                    supportingContent = { Text("No account required") },
                    leadingContent = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("COSMIC", style = MaterialTheme.typography.titleLarge)
                            Box {
                                TextButton(onClick = { providerMenu = true }, contentPadding = PaddingValues(0.dp)) {
                                    Text(selectedProvider?.let { "${it.name} · ${it.defaultModel}" } ?: "Connect a provider",
                                        style = MaterialTheme.typography.labelMedium)
                                    Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(18.dp))
                                }
                                DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                                    providers.forEach { item ->
                                        DropdownMenuItem(
                                            text = { Text("${item.name} · ${item.defaultModel}") },
                                            onClick = { providerId = item.id; providerMenu = false }
                                        )
                                    }
                                    if (providers.isEmpty()) DropdownMenuItem(
                                        text = { Text("No providers configured") }, onClick = { providerMenu = false; onSettings() })
                                }
                            }
                        }
                    },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Navigation")
                    } },
                    actions = {
                        IconButton(onClick = { if (!busy) sessionId = UUID.randomUUID().toString() }) {
                            Icon(Icons.Outlined.AddComment, contentDescription = "New conversation")
                        }
                        IconButton(onClick = { conversationActions = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Conversation actions")
                        }
                        DropdownMenu(expanded = conversationActions, onDismissRequest = { conversationActions = false }) {
                            DropdownMenuItem(text = { Text("Open execution console") }, leadingIcon = { Icon(Icons.Outlined.Terminal, null) },
                                onClick = { conversationActions = false; onNavigate(Routes.TERMINAL) })
                            DropdownMenuItem(text = { Text("Open execution activity") }, leadingIcon = { Icon(Icons.Outlined.History, null) },
                                onClick = { conversationActions = false; onNavigate(Routes.BACKGROUND) })
                        }
                    }
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                    .imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            TextButton(onClick = { modeMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                                Icon(Icons.Outlined.ExpandMore, null, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                                AgentMode.entries.forEach { option -> DropdownMenuItem(
                                    text = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) },
                                    onClick = { mode = option; modeMenu = false }) }
                            }
                        }
                        Text(selectedProvider?.defaultModel ?: "No model", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (busy) TextButton(onClick = { runtime.cancel() }) { Text("Stop") }
                    }
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp), tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        attachmentName?.let { name ->
                            AssistChip(onClick = { attachmentName = null; attachmentText = null }, label = { Text("$name · remove") },
                                leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null) })
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { attachmentPicker.launch(arrayOf("text/*", "application/json", "application/xml", "application/yaml")) }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = "Attach text or source file")
                        }
                        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Message COSMIC") }, maxLines = 6, shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.surface,
                                focusedBorderColor = MaterialTheme.colorScheme.surface))
                        FilledIconButton(
                            onClick = {
                                if ((input.isBlank() && attachmentText.isNullOrBlank()) || busy) return@FilledIconButton
                                val typed = input.trim()
                                val text = if (attachmentText != null) buildString {
                                    append(typed)
                                    append("\n\nAttached file: ")
                                    append(attachmentName ?: "selected file")
                                    append("\n```text\n")
                                    append(attachmentText)
                                    append("\n```")
                                }.trim() else typed
                                val cmd = CommandRegistry.parse(text)
                                if (cmd != null) {
                                    input = ""
                                    when (cmd) {
                                        is CosmicCommand.OpenBugs -> onNavigate(Routes.BUGS)
                                        is CosmicCommand.Help -> status = CommandRegistry.bugsHelpText()
                                        CosmicCommand.Unknown -> status = "Unknown command. Try /bugs help"
                                    }
                                    return@FilledIconButton
                                }
                                val entity = selectedProvider
                                if (entity == null) {
                                    status = "Connect a provider in Settings before sending a message."
                                    return@FilledIconButton
                                }
                                val config = entity.toProviderConfig() ?: run {
                                    status = "This provider configuration is invalid. Reconfigure it in Settings."
                                    return@FilledIconButton
                                }
                                input = ""
                                attachmentName = null
                                attachmentText = null
                                streaming = ""
                                status = "Starting"
                                executionId = null
                                scope.launch {
                                    db.sessionDao().upsert(SessionEntity(sessionId, text.take(64), entity.id,
                                        config.defaultModel, null, System.currentTimeMillis(), System.currentTimeMillis()))
                                    busy = true
                                    runtime.run(sessionId, text, config, config.defaultModel, mode)
                                }
                            },
                            enabled = !busy && (input.isNotBlank() || !attachmentText.isNullOrBlank())
                        ) { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send") }
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (messages.isEmpty() && streaming.isBlank()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 54.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(18.dp))
                        Text("What would you like to accomplish?", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Describe an outcome. COSMIC can help plan the work and use the tools you approve.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(24.dp))
                        listOf("Help me understand this project", "Research a technical question", "Plan a reliable implementation").forEach { suggestion ->
                            OutlinedButton(onClick = { input = suggestion }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(suggestion) }
                        }
                    }
                }
                if (status != "Ready") item(key = "execution_status") {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                itemsIndexed(messages, key = { index, msg -> "${index}-${msg.role}-${msg.toolCallId.orEmpty()}" }) { _, msg -> MessageBubble(msg) }
                if (streaming.isNotBlank()) item { MessageBubble(ChatMessage("assistant", streaming)) }
            }
        }
    }
}

private fun MessageEntity.toChatMessage() = ChatMessage(role, content, toolCallId)

private fun ProviderEntity.toProviderConfig(): ProviderConfig? = runCatching {
    ProviderConfig(id, name, ProviderType.valueOf(type), baseUrl, apiKeyAlias, defaultModel)
}.getOrNull()

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val clipboard = LocalClipboardManager.current
    val parts = remember(msg.content) { msg.content.split("```") }
    val isUser = msg.role == "user"
    val bg = if (isUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background
    val fg = MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(color = bg, shape = RoundedCornerShape(if (isUser) 20.dp else 10.dp), modifier = Modifier.widthIn(max = 720.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                if (!isUser) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (msg.role == "tool") "TOOL RESULT" else "COSMIC", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(bottom = 5.dp))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(msg.content)) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy response", modifier = Modifier.size(16.dp))
                    }
                }
                parts.forEachIndexed { index, segment ->
                    if (index % 2 == 1) {
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(segment.substringBefore('\n').takeIf { it.isNotBlank() } ?: "Code", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { clipboard.setText(AnnotatedString(segment.substringAfter('\n', segment))) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", modifier = Modifier.size(15.dp))
                                    }
                                }
                                Text(segment.substringAfter('\n', segment), color = fg, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else if (segment.isNotBlank()) {
                        Text(segment.trim(), color = fg, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (index < parts.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
