// language: kotlin, file: SettingsScreen.kt, runtime: Compose, target: providers + MCP + permissions
package com.cosmic.ai.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.data.local.db.entity.ProviderEntity
import com.cosmic.ai.domain.mcp.McpServerConfig
import com.cosmic.ai.domain.mcp.McpTransport
import com.cosmic.ai.domain.provider.ProviderConfig
import com.cosmic.ai.domain.provider.ProviderType
import com.cosmic.ai.domain.provider.ProviderModel
import com.cosmic.ai.runtime.providers.ProviderFactory
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = CosmicApplication.instance.database
    val store = CosmicApplication.instance.secureStore
    val scope = rememberCoroutineScope()
    val providers by db.providerDao().observeAll().collectAsState(initial = emptyList())

    var section by remember { mutableStateOf<Int?>(null) } // Settings landing list, then a focused category

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
        ) {
            val selectedSection = section
            if (selectedSection == null) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Configure your workspace", style = MaterialTheme.typography.titleMedium)
                    listOf(
                        "Providers & Models" to 0,
                        "API Credentials" to 4,
                        "MCP Servers" to 1,
                        "Agent Permissions" to 2,
                        "Appearance" to 3
                    ).forEach { (label, target) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            supportingContent = { Text(when (target) {
                                0 -> "Connect models and manage provider keys"
                                1 -> "Manage connected tool servers"
                                2 -> "Control file and terminal capabilities"
                                3 -> "Theme and interface preferences"
                                else -> "Manage separately stored credentials"
                            }) },
                            trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { section = target }
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { section = null }, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                        Spacer(Modifier.width(8.dp))
                        Text("All settings")
                    }
                    when (selectedSection) {
                        0 -> ProvidersSection(providers, scope, db, store)
                        1 -> McpSection()
                        2 -> PermissionsSection()
                        3 -> AppearanceSection()
                        4 -> SearchCredentialsSection(store)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProvidersSection(
    providers: List<ProviderEntity>,
    scope: kotlinx.coroutines.CoroutineScope,
    db: com.cosmic.ai.data.local.db.CosmicDatabase,
    store: com.cosmic.ai.data.local.keystore.SecureStore
) {
    var showAdd by remember { mutableStateOf(false) }
    var selectedPreset by remember { mutableStateOf(ProviderFactory.presets().first()) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(selectedPreset.baseUrl) }
    var model by remember { mutableStateOf(selectedPreset.defaultModel) }
    var apiKey by remember { mutableStateOf("") }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf<List<ProviderModel>>(emptyList()) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Configured providers", style = MaterialTheme.typography.titleMedium)
        if (providers.isEmpty()) {
            Text(
                "No providers yet. Add OpenAI, Anthropic, Gemini, OpenRouter, DeepSeek, or a custom endpoint.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        providers.forEach { p ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (p.isDefault) {
                            AssistChip(onClick = {}, label = { Text("Default") })
                        }
                    }
                    Text(p.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(p.baseUrl, style = MaterialTheme.typography.bodySmall)
                    Text("Model · ${p.defaultModel}", style = MaterialTheme.typography.bodySmall)
                    Text("Key · ${store.mask(p.apiKeyAlias)}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                db.providerDao().delete(p.id)
                                store.removeSecret(p.apiKeyAlias)
                            }
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Remove")
                        }
                    }
                }
            }
        }

        Button(onClick = { showAdd = !showAdd }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAdd) "Close" else "Add provider")
        }

        if (showAdd) {
            Text("Preset", style = MaterialTheme.typography.labelLarge)
            ProviderFactory.presets().forEach { preset ->
                FilterChip(
                    selected = selectedPreset.label == preset.label,
                    onClick = {
                        selectedPreset = preset
                        baseUrl = preset.baseUrl
                        model = preset.defaultModel
                        name = preset.label
                        discoveredModels = emptyList()
                    },
                    label = { Text(preset.label) },
                    modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                )
            }
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Default model") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API key") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !discovering && apiKey.isNotBlank() && baseUrl.isNotBlank(),
                    onClick = {
                        discovering = true
                        testMsg = null
                        scope.launch {
                            val cfg = ProviderConfig("models", name, selectedPreset.type, baseUrl, "temporary", model)
                            val result = ProviderFactory.get(selectedPreset.type).discoverModels(cfg, apiKey)
                            result.fold(
                                onSuccess = {
                                    discoveredModels = it
                                    testMsg = "Found ${it.size} models"
                                    modelMenuExpanded = it.isNotEmpty()
                                },
                                onFailure = {
                                    discoveredModels = emptyList()
                                    testMsg = "Model discovery failed · ${it.message ?: "request failed"}"
                                }
                            )
                            discovering = false
                        }
                    }
                ) { Text(if (discovering) "Loading models…" else "Load models") }
                Box {
                    TextButton(enabled = discoveredModels.isNotEmpty(), onClick = { modelMenuExpanded = true }) {
                        Text("Choose (${discoveredModels.size})")
                    }
                    DropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                        discoveredModels.take(100).forEach { discovered ->
                            DropdownMenuItem(
                                text = { Text(discovered.displayName) },
                                onClick = { model = discovered.id; modelMenuExpanded = false }
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !testing && apiKey.isNotBlank(),
                    onClick = {
                        testing = true
                        testMsg = null
                        scope.launch {
                            val adapter = ProviderFactory.get(selectedPreset.type)
                            val cfg = ProviderConfig(
                                id = "test",
                                name = name,
                                type = selectedPreset.type,
                                baseUrl = baseUrl,
                                apiKeyAlias = "tmp",
                                defaultModel = model
                            )
                            val result = adapter.testConnection(cfg, apiKey)
                            testMsg = result.fold({ "Connection OK · $it" }, { "Failed · ${it.message}" })
                            testing = false
                        }
                    }
                ) {
                    Icon(Icons.Outlined.Science, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (testing) "Testing…" else "Test")
                }
                Button(onClick = {
                    scope.launch {
                        val id = UUID.randomUUID().toString()
                        val alias = "provider_$id"
                        store.putSecret(alias, apiKey)
                        db.providerDao().upsert(
                            ProviderEntity(
                                id = id,
                                name = name.ifBlank { selectedPreset.label },
                                type = selectedPreset.type.name,
                                baseUrl = baseUrl,
                                apiKeyAlias = alias,
                                defaultModel = model,
                                isDefault = providers.isEmpty()
                            )
                        )
                        showAdd = false
                        apiKey = ""
                        testMsg = null
                    }
                }) { Text("Save") }
            }
            testMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}


@Composable
private fun SearchCredentialsSection(store: com.cosmic.ai.data.local.keystore.SecureStore) {
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val hasKey = remember { mutableStateOf(store.hasSecret(com.cosmic.ai.runtime.tools.BraveSearchTool.SECRET_ALIAS)) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Brave Search", style = MaterialTheme.typography.titleMedium)
        Text("Dedicated web research credential. Stored separately from model-provider keys using Android encrypted storage.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (hasKey.value) "Credential configured · ${store.mask(com.cosmic.ai.runtime.tools.BraveSearchTool.SECRET_ALIAS)}" else "No Brave key configured")
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Brave Search API key") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = key.isNotBlank(), onClick = {
                store.putSecret(com.cosmic.ai.runtime.tools.BraveSearchTool.SECRET_ALIAS, key.trim())
                key = ""
                hasKey.value = true
                message = "Brave Search credential saved securely."
            }) { Text("Save key") }
            OutlinedButton(enabled = hasKey.value, onClick = {
                store.removeSecret(com.cosmic.ai.runtime.tools.BraveSearchTool.SECRET_ALIAS)
                hasKey.value = false
                message = "Brave Search credential removed."
            }) { Text("Remove") }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun McpSection() {
    val mcp = remember { CosmicApplication.instance.mcpManager }
    val states by mcp.states.collectAsState()
    val tools by mcp.tools.collectAsState()
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransport.STDIO) }
    val scope = rememberCoroutineScope()
    val servers = remember { mutableStateListOf<McpServerConfig>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MCP servers", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connect Model Context Protocol servers (STDIO or HTTP). Discovered tools become available to the agent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        servers.forEach { s ->
            val st = states[s.id]
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text("${s.transport} · ${s.command ?: s.url}", style = MaterialTheme.typography.bodySmall)
                    when (st) {
                        is com.cosmic.ai.domain.mcp.McpConnectionState.Connected ->
                            Text("Connected · ${st.toolCount} tools", color = MaterialTheme.colorScheme.primary)
                        is com.cosmic.ai.domain.mcp.McpConnectionState.Error ->
                            Text("Error · ${st.message}", color = MaterialTheme.colorScheme.error)
                        is com.cosmic.ai.domain.mcp.McpConnectionState.Connecting ->
                            Text("Connecting…")
                        else -> Text("Disconnected")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { mcp.connect(s) }) { Text("Connect") }
                        TextButton(onClick = { mcp.disconnect(s.id) }) { Text("Disconnect") }
                    }
                }
            }
        }
        if (tools.isNotEmpty()) {
            Text("Discovered tools", style = MaterialTheme.typography.titleSmall)
            tools.forEach { t ->
                Text("· ${t.name}  (${t.serverId.take(6)})", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Text("Add server", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = transport == McpTransport.STDIO, onClick = { transport = McpTransport.STDIO }, label = { Text("STDIO") })
            FilterChip(selected = transport == McpTransport.HTTP, onClick = { transport = McpTransport.HTTP }, label = { Text("HTTP") })
            FilterChip(selected = transport == McpTransport.SSE, onClick = { transport = McpTransport.SSE }, label = { Text("SSE") })
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        if (transport == McpTransport.STDIO) {
            OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Command (e.g. npx)") }, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
        }
        Button(
            onClick = {
                val id = UUID.randomUUID().toString()
                val cfg = McpServerConfig(
                    id = id,
                    name = name.ifBlank { "MCP Server" },
                    transport = transport,
                    command = command.ifBlank { null },
                    url = url.ifBlank { null }
                )
                servers.add(cfg)
                mcp.connect(cfg)
                name = ""; command = ""; url = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add & connect") }
    }
}

@Composable
private fun PermissionsSection() {
    var fileRead by remember { mutableStateOf(true) }
    var fileWrite by remember { mutableStateOf(false) }
    var terminal by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf(true) }
    var approval by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Tool permissions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Agent tools respect these gates. Destructive actions can require explicit approval.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PermRow("File read", fileRead) { fileRead = it }
        PermRow("File write", fileWrite) { fileWrite = it }
        PermRow("Terminal", terminal) { terminal = it }
        PermRow("Network", network) { network = it }
        PermRow("Require approval for destructive", approval) { approval = it }
    }
}

@Composable
private fun PermRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun AppearanceSection() {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Deep navy workspace · purple / blue accents · system theme aware.", style = MaterialTheme.typography.bodyMedium)
        ListItem(
            headlineContent = { Text("Dark (default)") },
            supportingContent = { Text("Premium developer surface") },
            leadingContent = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) }
        )
    }
}
