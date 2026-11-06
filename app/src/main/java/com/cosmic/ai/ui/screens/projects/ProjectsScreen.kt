// language: kotlin, runtime: Compose + SAF, target: project workspace
package com.cosmic.ai.ui.screens.projects

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.data.local.datastore.AppPreferences
import com.cosmic.ai.core.project.ProjectTechnologyDetector
import com.cosmic.ai.core.project.TechnologyFinding
import com.cosmic.ai.runtime.project.AndroidRuntimeProbe
import com.cosmic.ai.ui.navigation.Routes
import com.cosmic.ai.ui.theme.CosmicBackdrop
import com.cosmic.ai.ui.theme.CosmicBottomNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }
    val savedProject by preferences.currentProjectUri.collectAsState(initial = null)
    var projectUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("Open a project folder to inspect its structure and technologies.") }
    var findings by remember { mutableStateOf<List<TechnologyFinding>>(emptyList()) }
    var runtimeStatus by remember { mutableStateOf<Map<String, com.cosmic.ai.core.project.RuntimeInspection>>(emptyMap()) }
    var fileCount by remember { mutableIntStateOf(0) }
    var buildPlans by remember { mutableStateOf<List<com.cosmic.ai.core.project.ProjectBuildPlan>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var executionReport by remember { mutableStateOf<com.cosmic.ai.runtime.project.ProjectExecutionReport?>(null) }
    var executing by remember { mutableStateOf(false) }
    val adapter = remember { com.cosmic.ai.runtime.project.LocalProjectExecutionAdapter(context) }
    val packageManager = remember { com.cosmic.ai.runtime.project.ProjectPackageManager(context, adapter) }

    fun runPlan(plan: com.cosmic.ai.core.project.ProjectBuildPlan) {
        val uri = projectUri ?: return
        executing = true
        scope.launch {
            val workspace = com.cosmic.ai.runtime.project.SafProjectWorkspaceProvider(context, uri, "selected-project")
            val policy = com.cosmic.ai.domain.agent.PermissionPolicy(
                allowFileRead = true,
                allowFileWrite = true,
                allowTerminal = true,
                allowNetwork = true,
                requireApprovalForDestructive = false
            )
            executionReport = adapter.execute(workspace, plan, policy)
            executing = false
        }
    }
    fun inspect(uri: Uri) {
        projectUri = uri
        CosmicApplication.instance.agentRuntime.setProject(uri)
        scanning = true
        status = "Inspecting permitted project files…"
        scope.launch {
            preferences.setCurrentProjectUri(uri.toString())
            val scan = withContext(Dispatchers.IO) { inspectProject(context, uri) }
            findings = scan.findings
            fileCount = scan.fileCount
            buildPlans = scan.plans
            status = if (scan.error != null) scan.error else if (scan.findings.isEmpty()) "Folder is accessible; no recognized project languages or manifests found." else "${scan.fileCount} files inspected · project technologies detected from manifests and source files."
            val languageNames = scan.findings.filter { it.kind == TechnologyFinding.Kind.LANGUAGE }.map { it.name }
            runtimeStatus = withContext(Dispatchers.IO) {
                val probe = AndroidRuntimeProbe()
                buildMap {
                    for (language in languageNames) put(language, probe.inspect(language, languageDetected = true))
                }
            }
            scanning = false
        }
    }
    LaunchedEffect(savedProject) {
        val value = savedProject
        if (value != null && projectUri == null) {
            val uri = Uri.parse(value)
            val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri && it.isReadPermission }
            if (permission == null) {
                status = "Previously selected folder access was revoked. Select the project folder again."
            } else inspect(uri)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { context.contentResolver.takePersistableUriPermission(uri, flags) } catch (_: SecurityException) { }
            inspect(uri)
        }
    }

    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, bottomBar = { CosmicBottomNavigation(Routes.PROJECTS, onNavigate) }, topBar = {
        TopAppBar(title = { Text("Projects") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        CosmicBackdrop(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(projectUri?.lastPathSegment?.substringAfterLast(':') ?: "Project workspace", style = MaterialTheme.typography.titleLarge)
                            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (fileCount > 0) Text("$fileCount files · ${findings.count { it.kind == TechnologyFinding.Kind.LANGUAGE }} languages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                Button(onClick = { launcher.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text(if (projectUri == null) "Open project folder" else "Change project") }
            }
            if (projectUri != null) item {
                OutlinedButton(onClick = { onNavigate(Routes.HTML_PREVIEW) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Preview index.html")
                }
            }
            if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (findings.isNotEmpty()) {
                item { Text("Detected technology", style = MaterialTheme.typography.titleMedium) }
                items(findings, key = { it.name }) { finding ->
                    ListItem(
                        headlineContent = { Text(finding.name) },
                        supportingContent = {
                            val runtime = runtimeStatus[finding.name]
                            when {
                                finding.kind != TechnologyFinding.Kind.LANGUAGE -> Text("${finding.kind.name.lowercase().replace('_', ' ')} · ${finding.evidence.joinToString()}")
                                runtime == null -> Text("Language detected from ${finding.evidence.joinToString()} · runtime check unavailable")
                                else -> Text(
                                    "Detected in project · runtime ${if (runtime.runtimeInstalled) "installed" else "not found"}" +
                                        (runtime.version?.let { " ($it)" } ?: "") +
                                        " · execute ${if (runtime.executionSupported) "via WebView preview" else "unavailable"}" +
                                        " · build ${if (runtime.buildSupported) "available" else "unavailable"} · test ${if (runtime.testSupported) "available" else "unavailable"}" +
                                        (runtime.availableExecutables.takeIf { it.isNotEmpty() }?.let { " · tools: ${it.joinToString()}" } ?: "")
                                )
                            }
                        },
                        leadingContent = { Text(finding.kind.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    )
                    HorizontalDivider()
                }
            }
            if (buildPlans.isNotEmpty()) {
                item { Text("Build and test plan candidates", style = MaterialTheme.typography.titleMedium) }
                items(buildPlans, key = { "${it.manifest}:${it.action}:${it.argv.joinToString(" ")}" }) { plan ->
                    ListItem(
                        headlineContent = { Text(plan.action.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        supportingContent = {
                            Text("${if (plan.workingDirectory.isBlank()) "." else plan.workingDirectory}: ${plan.argv.joinToString(" ")}\nApproval required · ${if (plan.requiresNetwork) "network may be required" else "network not expected"} · command availability not verified")
                        },
                        trailingContent = {
                            TextButton(enabled = !executing, onClick = { runPlan(plan) }) {
                                Text("Run")
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
            if (executing) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Executing project plan…", style = MaterialTheme.typography.bodySmall)
            }
            executionReport?.let { report ->
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Execution Report", style = MaterialTheme.typography.titleMedium)
                            Text("Status: ${report.status.name} · ${report.durationMs}ms" + (report.exitCode?.let { " (exit $it)" } ?: ""))
                            report.errorMessage?.let {
                                Text(it, color = if (report.status == com.cosmic.ai.runtime.project.ExecutionStatus.SUCCESS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            if (report.stdout.isNotBlank()) {
                                Text("stdout:\n${report.stdout.take(500)}", style = MaterialTheme.typography.labelSmall)
                            }
                            if (report.stderr.isNotBlank()) {
                                Text("stderr:\n${report.stderr.take(500)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            if (projectUri != null) item {
                Text("Execution environment", style = MaterialTheme.typography.titleMedium)
                Text("Runtime checks only inspect executables visible to this Android app. A detected language does not imply that a compiler, dependency manager, container, or remote runner is available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
    }
}

private data class ProjectScan(
    val findings: List<TechnologyFinding>,
    val fileCount: Int,
    val plans: List<com.cosmic.ai.core.project.ProjectBuildPlan>,
    val error: String? = null
)

private data class ScanDirectory(val path: String, val depth: Int)

private suspend fun inspectProject(context: android.content.Context, uri: Uri): ProjectScan {
    val workspace = com.cosmic.ai.runtime.project.SafProjectWorkspaceProvider(context, uri, "selected-project")
    val paths = linkedSetOf<String>()
    val manifests = linkedMapOf<String, String>()
    val manifestNames = setOf(
        "package.json", "requirements.txt", "pyproject.toml", "pipfile", "setup.py", "cargo.toml", "go.mod",
        "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle.kts", "gradlew", "pubspec.yaml",
        "composer.json", "gemfile", "rakefile", "cmakelists.txt", "dockerfile", "docker-compose.yml", "docker-compose.yaml"
    )
    val queue = ArrayDeque<ScanDirectory>()
    queue.add(ScanDirectory("", 0))
    var visited = 0
    while (queue.isNotEmpty() && visited < 500) {
        val frame = queue.removeFirst()
        when (val result = workspace.list(frame.path)) {
            is com.cosmic.ai.runtime.project.WorkspaceResult.Failure -> {
                if (paths.isEmpty()) return ProjectScan(emptyList(), 0, emptyList(), result.message)
            }
            is com.cosmic.ai.runtime.project.WorkspaceResult.Success -> for (entry in result.value) {
                if (visited++ >= 500) break
                if (entry.directory) {
                    if (frame.depth < 5) queue.add(ScanDirectory(entry.relativePath, frame.depth + 1))
                } else {
                    paths += entry.relativePath
                    if (entry.relativePath.substringAfterLast('/').lowercase() in manifestNames) {
                    when (val content = workspace.read(entry.relativePath, 256 * 1024)) {
                        is com.cosmic.ai.runtime.project.WorkspaceResult.Success -> manifests[entry.relativePath] = content.value.toString(Charsets.UTF_8)
                        is com.cosmic.ai.runtime.project.WorkspaceResult.Failure -> Unit
                    }
                    }
                }
            }
        }
    }
    val findings = ProjectTechnologyDetector.detect(paths, manifests)
    val plans = com.cosmic.ai.core.project.ProjectBuildPlanner.create(paths, manifests)
    return ProjectScan(findings, paths.size, plans)
}
