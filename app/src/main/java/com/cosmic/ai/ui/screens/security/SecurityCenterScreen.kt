package com.cosmic.ai.ui.screens.security

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.data.local.db.entity.SecurityAuditEventEntity
import com.cosmic.ai.ui.theme.CosmicBackdrop
import com.cosmic.ai.ui.theme.CosmicPanel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityCenterScreen(onBack: () -> Unit) {
    val runtime = CosmicApplication.instance.agentRuntime
    val audit by runtime.securityAudit().collectAsState(initial = emptyList())
    val pending by runtime.pendingApprovals().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var integrity by remember { mutableStateOf<String?>(null) }
    var decision by remember { mutableStateOf("ALL") }
    LaunchedEffect(runtime) {
        integrity = runCatching { runtime.verifySecurityAudit() }.fold(
            { if (it.valid) "Audit chain verified · ${it.verifiedEvents} events" else "Audit integrity failure at sequence ${it.firstInvalidSequence}" },
            { "Audit integrity could not be verified: ${it.javaClass.simpleName}" }
        )
    }
    val rows = remember(audit, query, decision) {
        audit.filter { row ->
            (decision == "ALL" || row.decision == decision) &&
                (query.isBlank() || listOf(row.agentId, row.taskId, row.toolName, row.action, row.resourceScope)
                    .any { it?.contains(query, ignoreCase = true) == true })
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Security Center") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
        })
    }) { padding ->
        CosmicBackdrop(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                integrity?.let {
                    Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (it.startsWith("Audit chain verified")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text("Search audit history") }, singleLine = true)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL", "REQUESTED", "APPROVED", "DENIED", "COMPLETED", "FAILED", "EXPIRED", "UNKNOWN").forEach { filter ->
                        FilterChip(selected = decision == filter, onClick = { decision = filter }, label = { Text(filter.lowercase().replaceFirstChar(Char::uppercase)) })
                    }
                }
                if (audit.isEmpty() && pending.isEmpty()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.Security, contentDescription = null)
                        Text("No security events are recorded yet", style = MaterialTheme.typography.titleMedium)
                        Text("Approval and tool execution events will appear here after they are persisted.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (pending.isNotEmpty()) {
                            item(key = "pending-heading") { Text("Pending approvals", style = MaterialTheme.typography.titleMedium) }
                            items(pending, key = { "approval-${it.id}" }) { request ->
                                val detail by produceState("Loading operation details…", request.id) {
                                    value = runtime.approvalDetails(request.id)
                                }
                                CosmicPanel(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(request.action, style = MaterialTheme.typography.titleSmall)
                                        Text("${request.agentId} · ${request.toolName} · ${request.riskLevel}", style = MaterialTheme.typography.labelMedium)
                                        Text(request.reason, style = MaterialTheme.typography.bodySmall)
                                        Text("Resource: ${request.resourceScope}", style = MaterialTheme.typography.bodySmall)
                                        Text(detail, style = MaterialTheme.typography.bodySmall)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            if (request.status == "PENDING") {
                                                TextButton(onClick = { runtime.resolveApproval(request.id, false) }) { Text("Deny") }
                                                if (request.toolName == "write_file") {
                                                    TextButton(onClick = { runtime.resolveApproval(request.id, true, true) }) { Text("Allow for task") }
                                                }
                                                Button(onClick = { runtime.resolveApproval(request.id, true) }) { Text("Allow once") }
                                            } else {
                                                Text("Approved · execution has not started", style = MaterialTheme.typography.labelMedium)
                                                Spacer(Modifier.weight(1f))
                                                Button(onClick = { runtime.continueApprovedApproval(request.id) }) { Text("Continue") }
                                            }
                                        }
                                        if (request.status in setOf("PENDING", "APPROVED")) {
                                            TextButton(onClick = { runtime.cancelApproval(request.id) }) { Text("Cancel approval") }
                                        }
                                    }
                                }
                            }
                        }
                        if (rows.isEmpty()) {
                            item(key = "no-audit-matches") { Text("No audit events match this filter", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium) }
                        } else {
                            item(key = "audit-heading") { Text("Audit history", style = MaterialTheme.typography.titleMedium) }
                            items(rows, key = { it.eventId }) { row -> AuditCard(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditCard(row: SecurityAuditEventEntity) {
    CosmicPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("#${row.sequence} · ${row.decision}", style = MaterialTheme.typography.titleSmall)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(row.occurredAt)), style = MaterialTheme.typography.labelSmall)
            }
            Text("${row.toolName} · ${row.action}", style = MaterialTheme.typography.bodyMedium)
            if (row.agentId != null || row.taskId != null) Text("Agent ${row.agentId ?: "unknown"} · Task ${row.taskId?.take(8) ?: "unknown"}", style = MaterialTheme.typography.labelSmall)
            if (row.resourceScope.isNotBlank()) Text("Resource: ${row.resourceScope}", style = MaterialTheme.typography.bodySmall)
            row.outcome?.takeIf(String::isNotBlank)?.let { Text("Outcome: $it", style = MaterialTheme.typography.bodySmall) }
            row.sanitizedError?.takeIf(String::isNotBlank)?.let { Text("Details: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
