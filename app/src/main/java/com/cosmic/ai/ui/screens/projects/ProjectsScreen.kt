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
import androidx.documentfile.provider.DocumentFile
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.core.project.ProjectTechnologyDetector
import com.cosmic.ai.core.project.TechnologyFinding
import com.cosmic.ai.runtime.project.AndroidRuntimeProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var projectUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("Open a project folder to inspect its structure and technologies.") }
    var findings by remember { mutableStateOf<List<TechnologyFinding>>(emptyList()) }
    var runtimeStatus by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var scanning by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try { context.contentResolver.takePersistableUriPermission(uri, flags) } catch (_: SecurityException) { }
            projectUri = uri
            CosmicApplication.instance.agentRuntime.setProject(uri)
            scanning = true
            status = "Inspecting permitted project files…"
            scope.launch {
                val scan = withContext(Dispatchers.IO) { inspectProject(context, uri) }
                findings = scan.first
                status = if (scan.first.isEmpty()) "Folder is accessible; no recognized project languages or manifests found." else "${scan.second} files inspected · detection is based on project files, not assumptions."
                val languageNames = scan.first.filter { it.kind == TechnologyFinding.Kind.LANGUAGE }.map { it.name }
                runtimeStatus = languageNames.associateWith { AndroidRuntimeProbe().availableExecutables(it) }
                scanning = false
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Projects") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(projectUri?.lastPathSegment?.substringAfterLast(':') ?: "Project workspace", style = MaterialTheme.typography.titleLarge)
                            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Button(onClick = { launcher.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text(if (projectUri == null) "Open project folder" else "Change project") }
            }
            if (scanning) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (findings.isNotEmpty()) {
                item { Text("Detected technology", style = MaterialTheme.typography.titleMedium) }
                items(findings, key = { it.name }) { finding ->
                    ListItem(
                        headlineContent = { Text(finding.name) },
                        supportingContent = {
                            val installed = runtimeStatus[finding.name]
                            when {
                                finding.kind == TechnologyFinding.Kind.LANGUAGE && installed == null -> Text("Project evidence: ${finding.evidence.joinToString()}")
                                finding.kind == TechnologyFinding.Kind.LANGUAGE && installed.isNullOrEmpty() -> Text("Detected in project · no matching executable found in the Android app shell PATH")
                                finding.kind == TechnologyFinding.Kind.LANGUAGE -> Text("Detected · available here: ${installed!!.joinToString()}")
                                else -> Text("${finding.kind.name.lowercase().replace('_', ' ')} · ${finding.evidence.joinToString()}")
                            }
                        },
                        leadingContent = { Text(finding.kind.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    )
                    HorizontalDivider()
                }
            }
            if (projectUri != null) item {
                Text("Execution environment", style = MaterialTheme.typography.titleMedium)
                Text("Runtime checks only inspect executables visible to this Android app. A detected language does not imply that a compiler, dependency manager, container, or remote runner is available.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class ProjectScan(val findings: List<TechnologyFinding>, val fileCount: Int)

private fun inspectProject(context: android.content.Context, uri: Uri): ProjectScan {
    val root = DocumentFile.fromTreeUri(context, uri) ?: return ProjectScan(emptyList(), 0)
    val paths = mutableListOf<String>()
    val manifests = mutableMapOf<String, String>()
    val queue = ArrayDeque<Pair<DocumentFile, Int>>()
    queue.add(root to 0)
    var visited = 0
    while (queue.isNotEmpty() && visited < 500) {
        val (directory, depth) = queue.removeFirst()
        val children = try { directory.listFiles() } catch (_: SecurityException) { emptyArray() }
        for (file in children) {
            if (visited >= 500) break
            visited++
            val name = file.name ?: continue
            if (file.isDirectory && depth < 5) queue.add(file to depth + 1)
            else if (file.isFile) {
                paths += name
                if (name.lowercase() in setOf("package.json", "requirements.txt", "pyproject.toml", "pubspec.yaml", "pom.xml", "build.gradle", "build.gradle.kts", "composer.json")) {
                    try {
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var remaining = 256 * 1024
                            while (remaining > 0) {
                                val n = input.read(buffer, 0, minOf(buffer.size, remaining))
                                if (n <= 0) break
                                output.write(buffer, 0, n)
                                remaining -= n
                            }
                            manifests[name] = output.toString(Charsets.UTF_8.name())
                        }
                    } catch (_: Exception) { }
                }
            }
        }
    }
    return ProjectScan(ProjectTechnologyDetector.detect(paths, manifests), paths.size)
}
