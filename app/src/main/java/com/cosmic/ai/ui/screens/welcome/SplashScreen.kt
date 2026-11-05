package com.cosmic.ai.ui.screens.welcome

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cosmic.ai.ui.theme.CodexPalette
import com.cosmic.ai.ui.theme.CosmicBackdrop
import com.cosmic.ai.ui.theme.CosmicLogo

@Composable
fun SplashScreen() {
    val pulse by rememberInfiniteTransition(label = "codex-splash").animateFloat(
        initialValue = .98f, targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "logo-pulse"
    )
    CosmicBackdrop(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CosmicLogo(Modifier.fillMaxWidth(.78f).scale(pulse))
            Spacer(Modifier.height(10.dp))
            Text("AI-Powered Engineering Workspace", style = MaterialTheme.typography.bodyLarge, color = CodexPalette.Cyan, textAlign = TextAlign.Center)
            Spacer(Modifier.height(34.dp))
            CircularProgressIndicator(modifier = Modifier.size(26.dp), color = CodexPalette.Cyan, strokeWidth = 2.dp)
        }
    }
}
