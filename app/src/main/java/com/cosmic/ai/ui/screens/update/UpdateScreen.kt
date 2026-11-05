// language: kotlin, file: UpdateScreen.kt, runtime: Compose, target: mandatory/optional update UI
package com.cosmic.ai.ui.screens.update

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cosmic.ai.core.update.UpdateDecision
import com.cosmic.ai.core.update.UpdateEvaluation
import com.cosmic.ai.core.update.UpdateManifest
import com.cosmic.ai.update.ApkDownloadManager
import com.cosmic.ai.update.DownloadProgress
import com.cosmic.ai.update.InstallationManager
import com.cosmic.ai.update.IntegrityVerifier
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun UpdateScreen(
    evaluation: UpdateEvaluation,
    onLater: () -> Unit,
    onUpdatedOrSkipNetwork: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manifest = evaluation.manifest
    val mandatory = evaluation.decision == UpdateDecision.MANDATORY

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloader = remember { ApkDownloadManager(context) }
    val installer = remember { InstallationManager(context) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                Icons.Outlined.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (mandatory) "PLEASE UPDATE APP" else "Update available",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (mandatory)
                    "A new version of COSMIC AI is required to continue using this application."
                else
                    "A newer version of COSMIC AI is available.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Current: ${evaluation.installedVersionName} (${evaluation.installedVersionCode})")
                    if (manifest != null) {
                        Text("Latest: ${manifest.latestVersion} (${manifest.versionCode})")
                        Text(manifest.releaseName, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Release notes", style = MaterialTheme.typography.titleSmall)
                        manifest.releaseNotes.forEach { note ->
                            Text("· $note", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (downloading) {
                val animated by animateFloatAsState(progress, label = "dl")
                LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (manifest == null) return@Button
                    error = null
                    downloading = true
                    status = "Downloading…"
                    scope.launch {
                        downloader.download(manifest.downloadUrl).collectLatest { event ->
                            when (event) {
                                is DownloadProgress.Running -> {
                                    progress = if (event.total > 0) event.bytes.toFloat() / event.total else 0f
                                    status = "Downloading ${(progress * 100).toInt()}%"
                                }
                                is DownloadProgress.Failed -> {
                                    downloading = false
                                    error = event.message
                                }
                                is DownloadProgress.Cancelled -> {
                                    downloading = false
                                    status = "Cancelled"
                                }
                                is DownloadProgress.Completed -> {
                                    status = "Verifying integrity…"
                                    val okHash = IntegrityVerifier.verifyHash(event.file, manifest.sha256)
                                    if (!okHash) {
                                        downloading = false
                                        error = "SHA-256 mismatch — APK rejected"
                                        event.file.delete()
                                        return@collectLatest
                                    }
                                    val okPkg = IntegrityVerifier.verifyPackageName(context, event.file, manifest.appId)
                                    if (!okPkg) {
                                        downloading = false
                                        error = "Package identity mismatch — APK rejected"
                                        event.file.delete()
                                        return@collectLatest
                                    }
                                    status = "Verified. Opening installer…"
                                    if (!installer.canRequestPackageInstalls()) {
                                        context.startActivity(installer.settingsUnknownSourcesIntent())
                                        error = "Grant install permission, then tap Update again"
                                        downloading = false
                                        return@collectLatest
                                    }
                                    context.startActivity(installer.createInstallIntent(event.file))
                                    downloading = false
                                    status = "Installer opened. Return after install."
                                }
                            }
                        }
                    }
                },
                enabled = !downloading && manifest != null,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(if (downloading) "Downloading…" else "UPDATE NOW")
            }
            if (!mandatory) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onLater,
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("REMIND ME LATER")
                }
            }
            if (evaluation.decision == UpdateDecision.NETWORK_ERROR) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onUpdatedOrSkipNetwork, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue offline")
                }
            }
        }
    }
}
