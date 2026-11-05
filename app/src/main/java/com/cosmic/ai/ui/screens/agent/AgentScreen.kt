// language: kotlin, file: AgentScreen.kt, runtime: Compose, target: main workspace
package com.cosmic.ai.ui.screens.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.domain.agent.AgentMode
import com.cosmic.ai.domain.provider.ChatMessage
import com.cosmic.ai.domain.provider.ProviderConfig
import com.cosmic.ai.domain.provider.ProviderType
import com.cosmic.ai.runtime.agent.AgentUiEvent
import com.cosmic.ai.core.commands.CommandRegistry
import com.cosmic.ai.core.commands.CosmicCommand
import com.cosmic.ai.ui.navigation.Routes
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    onNavigate: (String) -> Unit,
    onSettings: () -> Unit
) {
    val runtime = CosmicApplication.instance.agentRuntime
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var streaming by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AgentMode.AGENT) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        runtime.events.collectLatest { event ->
            when (event) {
                is AgentUiEvent.MessageAdded -> {
                    messages = messages + event.message
                    streaming = ""
                }
                is AgentUiEvent.StreamDelta -> {
                    streaming += event.text
                }
                is AgentUiEvent.Step -> {
                    status = "${event.step.type}: ${event.step.toolName ?: event.step.content.take(40)}"
                }
                is AgentUiEvent.NeedsApproval -> {
                    status = "Approval required: ${event.toolName}"
                    busy = false
                }
                is AgentUiEvent.Completed -> {
                    status = "Completed"
                    busy = false
                }
                is AgentUiEvent.Failed -> {
                    status = "Failed: ${event.message}"
                    busy = false
                }
                is AgentUiEvent.Cancelled -> {
                    status = "Cancelled"
                    busy = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("COSMIC AI", style = MaterialTheme.typography.titleLarge)
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigate(Routes.SESSIONS) }) {
                        Icon(Icons.Outlined.History, contentDescription = "Sessions")
                    }
                    IconButton(onClick = { onNavigate(Routes.PROJECTS) }) {
                        Icon(Icons.Outlined.Folder, contentDescription = "Projects")
                    }
                    IconButton(onClick = { onNavigate(Routes.TERMINAL) }) {
                        Icon(Icons.Outlined.Terminal, contentDescription = "Terminal")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mode == AgentMode.CHAT,
                        onClick = { mode = AgentMode.CHAT },
                        label = { Text("Chat") }
                    )
                    FilterChip(
                        selected = mode == AgentMode.AGENT,
                        onClick = { mode = AgentMode.AGENT },
                        label = { Text("Agent") }
                    )
                    FilterChip(
                        selected = mode == AgentMode.PLAN,
                        onClick = { mode = AgentMode.PLAN },
                        label = { Text("Plan") }
                    )
                    Spacer(Modifier.weight(1f))
                    if (busy) {
                        TextButton(onClick = { runtime.cancel() }) {
                            Text("Stop")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Instruct the agent…") },
                        maxLines = 5,
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (input.isBlank() || busy) return@FilledIconButton
                            val text = input.trim()
                            val cmd = CommandRegistry.parse(text)
                            if (cmd != null) {
                                input = ""
                                when (cmd) {
                                    is CosmicCommand.OpenBugs -> {
                                        onNavigate(Routes.BUGS)
                                    }
                                    is CosmicCommand.Help -> {
                                        status = CommandRegistry.bugsHelpText().lines().first()
                                        messages = messages + com.cosmic.ai.domain.provider.ChatMessage("assistant", CommandRegistry.bugsHelpText())
                                    }
                                    CosmicCommand.Unknown -> {
                                        messages = messages + com.cosmic.ai.domain.provider.ChatMessage("assistant", "Unknown command. Try /bugs help")
                                    }
                                }
                                return@FilledIconButton
                            }
                            input = ""
                            busy = true
                            status = "Running"
                            // For spine: use a demo provider config; real config comes from Settings/Room
                            val config = ProviderConfig(
                                id = "default",
                                name = "OpenAI Compatible",
                                type = ProviderType.OPENAI_COMPATIBLE,
                                baseUrl = "https://api.openai.com/v1",
                                apiKeyAlias = "provider_default",
                                defaultModel = "gpt-4o-mini"
                            )
                            val sessionId = "session_active"
                            runtime.run(sessionId, text, config, config.defaultModel, mode)
                        },
                        enabled = !busy && input.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
            if (streaming.isNotBlank()) {
                item {
                    MessageBubble(ChatMessage("assistant", streaming))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val bg = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bg,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Text(
                text = msg.content,
                color = fg,
                modifier = Modifier.padding(12.dp),
                fontFamily = if (msg.role == "tool") FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}
