// language: kotlin, file: MainActivity.kt, runtime: Android 8+, target: Compose
package com.cosmic.ai.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.cosmic.ai.ui.theme.CosmicTheme
import com.cosmic.ai.ui.navigation.CosmicNavGraph
import com.cosmic.ai.data.local.datastore.AppPreferences
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { AppPreferences(applicationContext) }
            val themeMode by preferences.theme.collectAsState(initial = "system")
            CosmicTheme(mode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CosmicRoot()
                }
            }
        }
    }
}

@Composable
fun CosmicRoot() {
    val navController = rememberNavController()
    CosmicNavGraph(navController = navController)
}
