package com.cosmic.ai.ui.screens.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.ui.navigation.Routes
import com.cosmic.ai.ui.theme.CodexPalette
import com.cosmic.ai.ui.theme.CosmicBackdrop
import com.cosmic.ai.ui.theme.CosmicBottomNavigation
import com.cosmic.ai.ui.theme.CosmicPanel

private val runningStates = setOf("QUEUED", "STARTING", "RUNNING", "WAITING_FOR_MODEL", "WAITING_FOR_TOOL", "COMPLETING")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val app = CosmicApplication.instance
    val tasks by app.database.taskDao().observeAll().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Running", "Completed", "Failed")
    val visible = remember(tasks, filter) {
        when (filter) {
            "Running" -> tasks.filter { it.status in runningStates }
            "Completed" -> tasks.filter { it.status == "COMPLETED" }
            "Failed" -> tasks.filter { it.status == "FAILED" || it.status == "RECOVERY_REQUIRED" || it.status == "UNKNOWN" }
            else -> tasks
        }
    }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = { CosmicBottomNavigation(Routes.TASKS, onNavigate) },
        topBar = {
            TopAppBar(title = { Text("Tasks") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            })
        }
    ) { padding ->
        CosmicBackdrop(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ScrollableTabRow(selectedTabIndex = filters.indexOf(filter).coerceAtLeast(0), edgePadding = 0.dp, containerColor = Color.Transparent, divider = {}) {
                        filters.forEach { label -> Tab(selected = filter == label, onClick = { filter = label }, text = { Text(label) }) }
                    }
                }
                if (tasks.isNotEmpty()) item {
                    CosmicPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(15.dp)) {
                            Text("Task Activity", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(3.dp))
                            Text("${tasks.size} persisted execution${if (tasks.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            TaskActivityGraph(tasks, Modifier.fillMaxWidth().height(70.dp))
                        }
                    }
                }
                if (visible.isEmpty()) item {
                    CosmicPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text(if (tasks.isEmpty()) "No tasks yet" else "No ${filter.lowercase()} tasks", style = MaterialTheme.typography.titleMedium)
                            Text("Agent executions appear here when they are persisted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(visible, key = { it.id }) { task -> TaskCard(task, onCancel = { app.agentRuntime.cancelTask(task.id) }, onOpen = { onNavigate(Routes.BACKGROUND) }) }
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskEntity, onCancel: () -> Unit, onOpen: () -> Unit) {
    val running = task.status in runningStates
    val failed = task.status in setOf("FAILED", "RECOVERY_REQUIRED", "UNKNOWN")
    val accent = when { running -> CodexPalette.Cyan; failed -> Color(0xFFFF657A); task.status == "COMPLETED" -> CodexPalette.Green; else -> CodexPalette.Purple }
    CosmicPanel(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(
                imageVector = when { running -> Icons.Outlined.HourglassTop; failed -> Icons.Outlined.ErrorOutline; task.status == "COMPLETED" -> Icons.Outlined.CheckCircle; else -> Icons.Outlined.PauseCircle },
                contentDescription = null, tint = accent, modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.progressMessage ?: "Agent task", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text("${task.status.lowercase().replace('_', ' ')} · ${task.id.take(8)} · ${durationLabel(task.createdAt, task.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent)
                if (failed) Text(task.progressMessage ?: "Recovery or retry may require review.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
            }
            if (running) IconButton(onClick = onCancel) { Icon(Icons.Outlined.Cancel, contentDescription = "Cancel task", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun TaskActivityGraph(tasks: List<TaskEntity>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val buckets = LongArray(12)
        val now = System.currentTimeMillis()
        tasks.forEach { task ->
            val ageHours = ((now - task.createdAt).coerceAtLeast(0) / 3_600_000).toInt()
            val index = (11 - ageHours.coerceAtMost(11)).coerceIn(0, 11)
            buckets[index]++
        }
        val maxValue = (buckets.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val step = size.width / (buckets.size - 1)
        val path = Path()
        buckets.forEachIndexed { index, value ->
            val point = Offset(index * step, size.height - (value / maxValue * (size.height * .82f)) - size.height * .08f)
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, CodexPalette.Purple, style = Stroke(width = 2.dp.toPx()))
        buckets.forEachIndexed { index, value ->
            val point = Offset(index * step, size.height - (value / maxValue * (size.height * .82f)) - size.height * .08f)
            drawCircle(CodexPalette.Cyan, radius = if (value > 0) 2.7.dp.toPx() else 1.5.dp.toPx(), center = point)
        }
    }
}

private fun durationLabel(start: Long, end: Long): String {
    val seconds = ((end - start).coerceAtLeast(0) / 1000).toInt()
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
}
