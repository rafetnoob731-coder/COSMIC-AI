package com.cosmic.ai.ui.screens.agents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cosmic.ai.agents.AgentProfile
import com.cosmic.ai.agents.AgentRole
import com.cosmic.ai.agents.SpecialistAgents
import com.cosmic.ai.ui.navigation.Routes
import com.cosmic.ai.ui.theme.CodexPalette
import com.cosmic.ai.ui.theme.CosmicBackdrop
import com.cosmic.ai.ui.theme.CosmicBottomNavigation
import com.cosmic.ai.ui.theme.CosmicPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = { CosmicBottomNavigation(Routes.AGENTS, onNavigate) },
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { onNavigate(Routes.SETTINGS) }) { Icon(Icons.Outlined.Info, contentDescription = "Agent configuration status") } }
            )
        }
    ) { padding ->
        CosmicBackdrop(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                item {
                    Text("Specialist roles", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("These capability profiles are registered in COSMIC CORE. Independent agent handoffs and per-agent execution status are not available in this build.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(SpecialistAgents.all, key = { it.role.name }) { agent -> AgentCard(agent) }
            }
        }
    }
}

@Composable
private fun AgentCard(agent: AgentProfile) {
    val (icon, tint) = agentPresentation(agent.role)
    CosmicPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = tint.copy(alpha = .15f), modifier = Modifier.size(48.dp)) {
                Icon(icon, null, tint = tint, modifier = Modifier.padding(11.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(agent.displayName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Surface(shape = MaterialTheme.shapes.small, color = CodexPalette.Green.copy(alpha = .12f)) {
                        Text("Configured", modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = CodexPalette.Green)
                    }
                }
                Text(agent.role.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = tint)
                Text(agent.systemPrompt.substringBefore('.').plus('.'), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Tools · ${agent.allowedTools.joinToString(", ")}", maxLines = 2, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                agent.preferredModelHint?.let { Text("Model hint · $it", style = MaterialTheme.typography.labelSmall, color = CodexPalette.Cyan) }
            }
        }
    }
}

private fun agentPresentation(role: AgentRole): Pair<ImageVector, Color> = when (role) {
    AgentRole.ORCHESTRATOR -> Icons.Outlined.AutoAwesome to Color(0xFFB96CFF)
    AgentRole.ARCHITECT -> Icons.Outlined.ChangeHistory to Color(0xFF49B8FF)
    AgentRole.BUILDER -> Icons.Outlined.Build to Color(0xFF59E4A9)
    AgentRole.DEBUGGER -> Icons.Outlined.BugReport to Color(0xFFFF6675)
    AgentRole.TESTER -> Icons.Outlined.Science to Color(0xFFBD67FF)
    AgentRole.REVIEWER -> Icons.Outlined.Search to Color(0xFF28D6F2)
    AgentRole.RESEARCHER -> Icons.Outlined.Language to Color(0xFF50AEFF)
}
