package com.cosmic.ai.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cosmic.ai.ui.navigation.Routes
import com.cosmic.ai.ui.theme.CodexPalette
import com.cosmic.ai.ui.theme.CosmicBackdrop
import com.cosmic.ai.ui.theme.CosmicGradientButton
import com.cosmic.ai.ui.theme.CosmicLogo
import com.cosmic.ai.ui.theme.CosmicPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val version = remember(context) { try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0" } catch (_: Exception) { "0.1.0" } }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(title = { Text("About") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent))
        }
    ) { padding ->
        CosmicBackdrop(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(padding).systemBarsPadding().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(12.dp))
                CosmicLogo(Modifier.fillMaxWidth(.76f))
                Text("v$version", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("AI-Powered Engineering Workspace for Android", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                CosmicPanel(Modifier.fillMaxWidth()) {
                    LinkRow("GitHub", "github.com/rafetnoob731-coder/COSMIC-AI", Icons.Outlined.Code) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rafetnoob731-coder/COSMIC-AI")))
                    }
                    HorizontalDivider(color = CodexPalette.Line)
                    LinkRow("License", "Apache License 2.0", Icons.Outlined.VerifiedUser, enabled = false) { }
                    HorizontalDivider(color = CodexPalette.Line)
                    LinkRow("Project website", "Not configured", Icons.Outlined.Public, enabled = false) { }
                }
                CosmicPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("One Intelligence. Infinite Possibilities.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        listOf("Multi-provider AI", "Permission-controlled tools", "Local project workspace", "Persistent task history", "Open-source Android client").forEach { feature ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(modifier = Modifier.size(21.dp), shape = CircleShape, color = CodexPalette.Cyan.copy(alpha = .16f)) {
                                    Icon(Icons.Outlined.VerifiedUser, null, tint = CodexPalette.Cyan, modifier = Modifier.padding(3.dp))
                                }
                                Spacer(Modifier.width(9.dp))
                                Text(feature, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        CosmicGradientButton("Build Bigger with COSMIC CODEX", Modifier.fillMaxWidth()) { onNavigate(Routes.AGENT) }
                    }
                }
                Text("© 2026 COSMIC CODEX contributors", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, detail: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) }, supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, null, tint = if (enabled) CodexPalette.Cyan else MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
