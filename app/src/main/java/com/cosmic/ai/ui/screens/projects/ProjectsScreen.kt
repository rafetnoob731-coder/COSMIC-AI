// language: kotlin, file: ProjectsScreen.kt, runtime: Compose + SAF, target: project picker
package com.cosmic.ai.ui.screens.projects

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var projectUri by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("No project open") }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // still usable for session
            }
            projectUri = uri
            CosmicApplication.instance.agentRuntime.setProject(uri)
            status = "Open: ${uri.lastPathSegment ?: uri.toString()}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(status, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launcher.launch(null) }) {
                Text("Open Project Folder")
            }
            Text(
                "Uses Android Storage Access Framework. Persistable URI permissions are requested.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
