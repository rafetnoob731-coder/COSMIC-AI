// language: kotlin, file: CosmicNavGraph.kt, runtime: Compose Navigation, target: COSMIC STUDIO
package com.cosmic.ai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.cosmic.ai.ui.screens.agent.AgentScreen
import com.cosmic.ai.ui.screens.agents.AgentsScreen
import com.cosmic.ai.ui.screens.background.BackgroundTasksScreen
import com.cosmic.ai.ui.screens.home.HomeScreen
import com.cosmic.ai.ui.screens.home.AboutScreen
import com.cosmic.ai.ui.screens.welcome.SplashScreen
import com.cosmic.ai.ui.screens.mcp.McpScreen
import com.cosmic.ai.ui.screens.memory.MemoryScreen
import com.cosmic.ai.ui.screens.projects.ProjectsScreen
import com.cosmic.ai.ui.screens.sessions.SessionsScreen
import com.cosmic.ai.ui.screens.settings.SettingsScreen
import com.cosmic.ai.ui.screens.tasks.TasksScreen
import com.cosmic.ai.ui.screens.terminal.TerminalScreen
import com.cosmic.ai.ui.screens.welcome.WelcomeScreen
import com.cosmic.ai.ui.screens.update.UpdateScreen
import com.cosmic.ai.ui.screens.bugs.BugCenterScreen
import com.cosmic.ai.core.update.UpdateDecision
import com.cosmic.ai.core.update.UpdateEvaluation
import com.cosmic.ai.update.UpdateChecker
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.cosmic.ai.ui.screens.workflows.WorkflowsScreen

object Routes {
    const val WELCOME = "welcome"
    const val SPLASH = "splash"
    const val ABOUT = "about"
    const val HOME = "home"
    const val AGENT = "agent"
    const val PROJECTS = "projects"
    const val TERMINAL = "terminal"
    const val AGENTS = "agents"
    const val TASKS = "tasks"
    const val SESSIONS = "sessions"
    const val WORKFLOWS = "workflows"
    const val MCP = "mcp"
    const val MEMORY = "memory"
    const val BACKGROUND = "activity"
    const val SETTINGS = "settings"
    const val UPDATE = "update"
    const val BUGS = "bugs"
}

@Composable
fun CosmicNavGraph(navController: NavHostController) {
    val context = LocalContext.current
    var updateEval by remember { mutableStateOf<UpdateEvaluation?>(null) }
    var updateChecked by remember { mutableStateOf(false) }

    // Startup update check — failure does not crash
    LaunchedEffect(Unit) {
        try {
            val checker = UpdateChecker(context)
            updateEval = checker.check()
        } catch (_: Exception) {
            updateEval = null
        } finally {
            updateChecked = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        enterTransition = { fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 24 } },
        exitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 32 } },
        popEnterTransition = { fadeIn(tween(180)) + slideInHorizontally(tween(180)) { -it / 32 } },
        popExitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { it / 24 } }
    ) {
        composable(Routes.SPLASH) {
            SplashScreen()
            LaunchedEffect(updateChecked) {
                if (updateChecked) {
                    val destination = if (updateEval?.decision in setOf(UpdateDecision.MANDATORY, UpdateDecision.OPTIONAL)) Routes.UPDATE else Routes.HOME
                    navController.navigate(destination) { popUpTo(Routes.SPLASH) { inclusive = true } }
                }
            }
        }
        composable(Routes.UPDATE) {
            val ev = updateEval
            if (ev != null) {
                UpdateScreen(
                    evaluation = ev,
                    onLater = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.UPDATE) { inclusive = true }
                        }
                    },
                    onUpdatedOrSkipNetwork = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.UPDATE) { inclusive = true }
                        }
                    }
                )
            }
        }
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onGetStarted = { navController.navigate(Routes.HOME) { popUpTo(Routes.WELCOME) { inclusive = true } } },
                onSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onNavigate = { navController.navigate(it) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConversation = { id -> navController.navigate("${Routes.AGENT}/$id") }
            )
        }
        composable(Routes.AGENT) {
            AgentScreen(
                onNavigate = { navController.navigate(it) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenConversation = { id -> navController.navigate("${Routes.AGENT}/$id") }
            )
        }
        composable("${Routes.AGENT}/{sessionId}") { entry ->
            AgentScreen(
                onNavigate = { navController.navigate(it) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                initialSessionId = entry.arguments?.getString("sessionId"),
                onOpenConversation = { id -> navController.navigate("${Routes.AGENT}/$id") }
            )
        }
        composable(Routes.PROJECTS) { ProjectsScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }) }
        composable(Routes.TERMINAL) { TerminalScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.AGENTS) { AgentsScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }) }
        composable(Routes.TASKS) { TasksScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }) }
        composable(Routes.SESSIONS) {
            SessionsScreen(
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> navController.navigate("${Routes.AGENT}/$id") }
            )
        }
        composable(Routes.WORKFLOWS) { WorkflowsScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.MCP) { McpScreen(onBack = { navController.popBackStack() }, onSettings = { navController.navigate(Routes.SETTINGS) }) }
        composable(Routes.MEMORY) { MemoryScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.BACKGROUND) { BackgroundTasksScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }, onAbout = { navController.navigate(Routes.ABOUT) }, onNavigate = { navController.navigate(it) }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }) }
        composable(Routes.BUGS) {
            BugCenterScreen(onBack = { navController.popBackStack() })
        }
    }
}
