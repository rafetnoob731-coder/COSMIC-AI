// language: kotlin, file: WelcomeScreen.kt, runtime: Compose, target: first launch
package com.cosmic.ai.ui.screens.welcome

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onSettings: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val scale by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1220), Color(0xFF1E1B4B), Color(0xFF0B1220))
                )
            )
    ) {
        IconButton(
            onClick = onSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = Color(0xFFA78BFA))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .scale(scale)
                    .background(
                        Brush.radialGradient(listOf(Color(0xFF7C3AED), Color(0xFF3B82F6))),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "C",
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                "COSMIC AI",
                style = MaterialTheme.typography.displayLarge,
                color = Color(0xFFE2E8F0),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Mobile-first autonomous coding agent.\nProjects, tools, terminal, sessions — under your control.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7C3AED),
                    contentColor = Color.White
                )
            ) {
                Text("Get Started", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
