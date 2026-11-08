// language: kotlin, file: SettingsScreen.kt, runtime: Compose, target: providers + MCP + permissions
package com.cosmic.ai.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.cosmic.ai.ui.theme.CosmicBackdrop
import com.cosmic.ai.ui.theme.CodexPalette
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Bolt
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAbout: () -> Unit = {}, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val db = CosmicApplication.instance.database
    val store = CosmicApplication.instance.secureStore
    val scope = rememberCoroutineScope()
    val providers by db.providerDao().observeAll().collectAsState(initial = emptyList())
    var section by remember { mutableStateOf<Int?>(null) }
    val entries = listOf(
        Triple("AI Providers", "Configure providers and credentials", Icons.Outlined.Cloud) to 0,
        Triple("Model Management", "Select models and provider defaults", Icons.Outlined.Tune) to 0,
        Triple("MCP Servers", "Connected tools and protocol servers", Icons.Outlined.Extension) to 1,
        Triple("Agent Permissions", "Control file, network, and terminal access", Icons.Outlined.Security) to 2,
        Triple("Appearance", "Theme and interface preferences", Icons.Outlined.Palette) to 3,
        Triple("Search", "DuckDuckGo Instant Answers", Icons.Outlined.Search) to 4,
        Triple("Agents", "Specialist roles and available tools", Icons.Outlined.Groups) to 5,
        Triple("Memory", "Browse persisted session memory", Icons.Outlined.Memory) to 6,
        Triple("About COSMIC CODEX", "Version, project, and license information", Icons.Outlined.Info) to 7
    )
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(title = { Text("Settings") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val selectedSection = section
            if (selectedSection == null) {
                CosmicBackdrop(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text("Configure your workspace", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                        entries.forEach { (entry, target) ->
                            ListItem(
                                headlineContent = { Text(entry.first) },
                                supportingContent = { Text(entry.second) },
                                leadingContent = { Icon(entry.third, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    when (target) {
                                        5 -> onNavigate(com.cosmic.ai.ui.navigation.Routes.AGENTS)
                                        6 -> onNavigate(com.cosmic.ai.ui.navigation.Routes.MEMORY)
                                        7 -> onAbout()
                                        else -> section = target
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
                        }
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
                        4 -> DuckDuckGoSearchSection()
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
    val presets = remember { ProviderFactory.presets() }
    val configuration = LocalConfiguration.current
    val dialogHeight = (configuration.screenHeightDp - 64).coerceIn(220, 600).dp
    val maskedApiKeys by produceState(initialValue = emptyMap<String, String>(), providers) {
        value = withContext(Dispatchers.IO) { providers.associate { it.id to runCatching { store.mask(it.apiKeyAlias) }.getOrDefault("Unavailable") } }
    }
    var showAdd by remember { mutableStateOf(false) }
    var selectedPreset by remember(presets) { mutableStateOf(presets.first()) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(selectedPreset.baseUrl) }
    var model by remember { mutableStateOf(selectedPreset.defaultModel) }
    var apiKey by remember { mutableStateOf("") }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var discoveredModels by remember { mutableStateOf<List<ProviderModel>>(emptyList()) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var modelFilter by remember { mutableStateOf("") }
    val visibleModels = remember(discoveredModels, modelFilter) {
        discoveredModels.filter { it.id.contains(modelFilter, ignoreCase = true) || it.displayName.contains(modelFilter, ignoreCase = true) }
    }

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
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) { Text("Default", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Text(p.type, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(p.baseUrl, style = MaterialTheme.typography.bodySmall)
                    Text("Model · ${p.defaultModel}", style = MaterialTheme.typography.bodySmall)
                    Text("Key · ${maskedApiKeys[p.id] ?: "Loading securely…"}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.removeSecret(p.apiKeyAlias) }
                                db.providerDao().delete(p.id)
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(presets, key = { it.label }) { preset ->
                    FilterChip(
                        selected = selectedPreset.label == preset.label,
                        onClick = {
                            selectedPreset = preset
                            baseUrl = preset.baseUrl
                            model = preset.defaultModel
                            name = preset.label
                            discoveredModels = emptyList()
                        },
                        label = { Text(preset.label) }
                    )
                }
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
                                    modelFilter = ""
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
                    if (modelMenuExpanded) {
                        Dialog(
                            onDismissRequest = { modelMenuExpanded = false },
                            properties = DialogProperties(usePlatformDefaultWidth = false)
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 560.dp).height(dialogHeight),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 6.dp
                            ) {
                                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Select a model", style = MaterialTheme.typography.titleLarge)
                                    OutlinedTextField(
                                        value = modelFilter,
                                        onValueChange = { modelFilter = it },
                                        label = { Text("Filter ${discoveredModels.size} discovered models") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    androidx.compose.foundation.lazy.LazyColumn(
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(visibleModels.take(500), key = { it.id }) { discovered ->
                                            ListItem(
                                                headlineContent = { Text(discovered.displayName) },
                                                supportingContent = { if (discovered.displayName != discovered.id) Text(discovered.id) },
                                                modifier = Modifier.fillMaxWidth().clickable {
                                                    model = discovered.id
                                                    modelMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                    TextButton(onClick = { modelMenuExpanded = false }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                                }
                            }
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
                            val result = withContext(Dispatchers.IO) { adapter.testConnection(cfg, apiKey) }
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
                        withContext(Dispatchers.IO) { store.putSecret(alias, apiKey) }
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
private fun DuckDuckGoSearchSection() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DuckDuckGo Instant Answers", style = MaterialTheme.typography.titleMedium)
        Text("No API key is required. COSMIC queries DuckDuckGo's documented Instant Answer API over HTTPS and returns only source URLs included in its response.")
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Text(
                "This API provides topic summaries, definitions, and related-topic answers. It does not provide a complete list of organic web search results. Queries may return no results; COSMIC CODEX does not silently substitute another search engine.",
                modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("Existing Brave credentials, if any, are no longer read by COSMIC CODEX. They remain in encrypted storage so this migration does not silently delete user data.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun McpSection() {
    val mcp = remember { CosmicApplication.instance.mcpManager }
    val states by mcp.states.collectAsState()
    val tools by mcp.tools.collectAsState()
    val servers by mcp.configurations.collectAsState()
    var name by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(McpTransport.STDIO) }

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
                        TextButton(onClick = {
                            mcp.removeConfiguration(s.id)
                            CosmicApplication.instance.mcpServerConfigStore.save(mcp.configurations.value)
                        }) { Text("Remove") }
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
                mcp.upsertConfiguration(cfg)
                CosmicApplication.instance.mcpServerConfigStore.save(mcp.configurations.value)
                mcp.connect(cfg)
                name = ""; command = ""; url = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add & connect") }
    }
}

@Composable
private fun PermissionsSection() {
    val context = LocalContext.current
    val preferences = remember(context) { com.cosmic.ai.data.local.datastore.AppPreferences(context) }
    val scope = rememberCoroutineScope()
    val fileRead by preferences.allowFileRead.collectAsState(initial = true)
    val fileWrite by preferences.allowFileWrite.collectAsState(initial = false)
    val terminal by preferences.allowTerminal.collectAsState(initial = false)
    val network by preferences.allowNetwork.collectAsState(initial = true)
    val approval by preferences.requireApproval.collectAsState(initial = true)
    val autoApprove by preferences.autoApprove.collectAsState(initial = false)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Tool permissions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Agent tools respect these gates. Destructive actions can require explicit approval.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (autoApprove) CodexPalette.Purple.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (autoApprove) CodexPalette.Purple else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = if (autoApprove) CodexPalette.Cyan else CodexPalette.Purple,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Auto-Approval Mode", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Automatically authorize file writes and terminal commands without confirmation prompts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoApprove,
                    onCheckedChange = { value -> scope.launch { preferences.setPermission("auto_approve", value) } }
                )
            }
        }
        PermRow("File read", fileRead) { value -> scope.launch { preferences.setPermission("file_read", value) } }
        PermRow("File write", fileWrite) { value -> scope.launch { preferences.setPermission("file_write", value) } }
        PermRow("Terminal", terminal) { value -> scope.launch { preferences.setPermission("terminal", value) } }
        PermRow("Network", network) { value -> scope.launch { preferences.setPermission("network", value) } }
        PermRow("Require approval for destructive", approval) { value -> scope.launch { preferences.setPermission("approval", value) } }
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
    val context = LocalContext.current
    val preferences = remember(context) { com.cosmic.ai.data.local.datastore.AppPreferences(context) }
    val mode by preferences.theme.collectAsState(initial = "system")
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Choose how COSMIC follows your display preference.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = { Text(when (value) { "system" -> "Match Android appearance"; "light" -> "Use the light workspace"; else -> "Use the dark workspace" }) },
                leadingContent = { RadioButton(selected = mode == value, onClick = { scope.launch { preferences.setTheme(value) } }) },
                modifier = Modifier.fillMaxWidth().clickable { scope.launch { preferences.setTheme(value) } }
            )
        }
    }
}
