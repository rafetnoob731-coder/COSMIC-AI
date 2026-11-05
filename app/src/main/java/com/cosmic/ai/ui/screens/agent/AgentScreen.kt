package com.cosmic.ai.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
fun AgentScreen(onNavigate: (String) -> Unit, onSettings: () -> Unit) {
    val app = CosmicApplication.instance
    val runtime = app.agentRuntime
    val db = app.database
    val scope = rememberCoroutineScope()
    val providers by db.providerDao().observeAll().collectAsState(initial = emptyList())
    var providerId by remember { mutableStateOf<String?>(null) }
    var sessionId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val storedMessages by db.messageDao().observeForSession(sessionId).collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AgentMode.AGENT) }
    var modeMenu by remember { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState()
    val selectedProvider = providers.firstOrNull { it.id == providerId }
        ?: providers.firstOrNull { it.isDefault }
        ?: providers.firstOrNull()
    val messages = storedMessages.map { it.toChatMessage() }

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
                is AgentUiEvent.ExecutionStarted -> { status = "${event.provider} · ${event.model}"; busy = true }
                is AgentUiEvent.ExecutionRejected -> { status = event.message; busy = false }
                is AgentUiEvent.MessageAdded -> if (event.message.role == "assistant") streaming = ""
                is AgentUiEvent.StreamDelta -> streaming += event.text
                is AgentUiEvent.Step -> status = "${event.step.type}: ${event.step.toolName ?: event.step.content.take(50)}"
                is AgentUiEvent.NeedsApproval -> { status = "Approval required: ${event.toolName}"; busy = false }
                is AgentUiEvent.Completed -> { status = "Completed"; busy = false; streaming = "" }
                is AgentUiEvent.Failed -> { status = event.message; busy = false }
                is AgentUiEvent.Cancelled -> { status = "Cancelled"; busy = false }
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
            ModalDrawerSheet {
                Text("COSMIC AI", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp))
                NavigationDrawerItem(label = { Text("New Chat") }, selected = false,
                    icon = { Icon(Icons.Outlined.Add, null) }, onClick = {
                        scope.launch { drawerState.close() }
                        if (!busy) sessionId = UUID.randomUUID().toString()
                    })
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                listOf(
                    "Conversations" to Routes.SESSIONS, "Projects" to Routes.PROJECTS,
                    "Agents" to Routes.AGENTS, "Tasks" to Routes.TASKS, "Workflows" to Routes.WORKFLOWS,
                    "Memory" to Routes.MEMORY, "MCP" to Routes.MCP, "Terminal" to Routes.TERMINAL,
                    "Bug Center" to Routes.BUGS, "Settings" to Routes.SETTINGS
                ).forEach { (label, route) ->
                    NavigationDrawerItem(label = { Text(label) }, selected = false,
                        onClick = { scope.launch { drawerState.close() }; onNavigate(route) })
                }
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
                        IconButton(onClick = { onNavigate(Routes.SESSIONS) }) {
                            Icon(Icons.Outlined.History, contentDescription = "Conversation history")
                        }
                    }
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                        Text(status.take(70), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        if (busy) TextButton(onClick = { runtime.cancel() }) { Text("Stop") }
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                            placeholder = { Text("Message COSMIC") }, maxLines = 6, shape = RoundedCornerShape(22.dp))
                        FilledIconButton(
                            onClick = {
                                if (input.isBlank() || busy) return@FilledIconButton
                                val text = input.trim()
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
                                streaming = ""
                                scope.launch {
                                    db.sessionDao().upsert(SessionEntity(sessionId, text.take(64), entity.id,
                                        config.defaultModel, null, System.currentTimeMillis(), System.currentTimeMillis()))
                                    busy = true
                                    status = "Starting"
                                    runtime.run(sessionId, text, config, config.defaultModel, mode)
                                }
                            },
                            enabled = !busy && input.isNotBlank()
                        ) { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send") }
                    }
                }
            }
        ) { padding ->
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (messages.isEmpty() && streaming.isBlank()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("What would you like to work on?", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Ask a question or give COSMIC a task.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val isUser = msg.role == "user"
    val bg = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 620.dp)) {
            Text(msg.content, color = fg, modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                fontFamily = if (msg.role == "tool") FontFamily.Monospace else FontFamily.Default)
        }
    }
}
