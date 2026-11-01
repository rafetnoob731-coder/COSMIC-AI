// language: kotlin, file: SessionsScreen.kt, runtime: Compose + Room, target: session list
package com.cosmic.ai.ui.screens.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.data.local.db.entity.SessionEntity
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit
) {
    val db = CosmicApplication.instance.database
    val sessions by db.sessionDao().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val id = UUID.randomUUID().toString()
                            val now = System.currentTimeMillis()
                            db.sessionDao().upsert(
                                SessionEntity(id, "New session", null, null, null, now, now)
                            )
                        }
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = "New")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sessions, key = { it.id }) { s ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSession(s.id) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(s.title, style = MaterialTheme.typography.titleMedium)
                            Text(s.model ?: "no model", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = {
                            scope.launch {
                                db.messageDao().deleteForSession(s.id)
                                db.sessionDao().delete(s.id)
                            }
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }
}
