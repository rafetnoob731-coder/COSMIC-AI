// language: kotlin, file: BugCenterScreen.kt, runtime: Compose, target: Bug Center
package com.cosmic.ai.ui.screens.bugs

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cosmic.ai.bugs.*
import com.cosmic.ai.core.commands.CommandRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugCenterScreen(
    onBack: () -> Unit,
    initialTab: String? = null
) {
    val context = LocalContext.current
    val repo = remember { BugRepository(context) }
    val bugs by repo.bugs.collectAsState()
    var tab by remember {
        mutableStateOf(
            when (initialTab) {
                "new" -> 1
                "my" -> 2
                else -> 0
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bug Center") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Dashboard") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Report") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("My Reports") })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Help") })
            }
            when (tab) {
                0 -> Dashboard(bugs)
                1 -> ReportForm(repo) { tab = 2 }
                2 -> MyReports(bugs)
                3 -> HelpTab()
            }
        }
    }
}

@Composable
private fun Dashboard(bugs: List<BugReport>) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(40.dp))
        Text("COSMIC Bug Center", style = MaterialTheme.typography.titleLarge)
        Text(
            "Report issues in the app, agents, providers, MCP, filesystem, or terminal. Reports are stored on-device. Configure a remote API to sync with developers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Local reports: ${bugs.size}")
                val open = bugs.count { it.status != BugStatus.FIXED && it.status != BugStatus.RELEASED && it.status != BugStatus.REJECTED }
                Text("Open: $open")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportForm(repo: BugRepository, onSubmitted: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var expected by remember { mutableStateOf("") }
    var actual by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(BugCategory.OTHER) }
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val versionName = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
    val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = category.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Category") },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                BugCategory.entries.forEach { c ->
                    DropdownMenuItem(text = { Text(c.name) }, onClick = { category = c; expanded = false })
                }
            }
        }
        OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        OutlinedTextField(value = steps, onValueChange = { steps = it }, label = { Text("Steps to reproduce") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(value = expected, onValueChange = { expected = it }, label = { Text("Expected") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = actual, onValueChange = { actual = it }, label = { Text("Actual") }, modifier = Modifier.fillMaxWidth())
        Text("App $versionName · $device", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                error = null
                val report = BugReport(
                    title = title.trim(),
                    category = category,
                    description = description.trim(),
                    steps = steps.trim(),
                    expected = expected.trim(),
                    actual = actual.trim(),
                    appVersion = versionName ?: "?",
                    deviceInfo = device
                )
                repo.submit(report).fold(
                    onSuccess = {
                        message = "Submitted ${it.id}"
                        title = ""; description = ""; steps = ""; expected = ""; actual = ""
                        onSubmitted()
                    },
                    onFailure = { error = it.message }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Submit report") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "Diagnostics: API keys and tokens are never attached. Remote API is optional.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MyReports(bugs: List<BugReport>) {
    if (bugs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("No reports yet. Use the Report tab or type /bugs new in chat.")
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(bugs, key = { it.id }) { b ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(b.id, style = MaterialTheme.typography.labelMedium)
                    Text(b.title, style = MaterialTheme.typography.titleMedium)
                    Text("${b.category} · ${b.status}", style = MaterialTheme.typography.bodySmall)
                    b.developerUpdate?.let { Text("Update: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun HelpTab() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(CommandRegistry.bugsHelpText(), style = MaterialTheme.typography.bodyLarge)
    }
}
