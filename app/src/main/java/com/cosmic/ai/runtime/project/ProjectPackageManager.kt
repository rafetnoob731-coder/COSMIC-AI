package com.cosmic.ai.runtime.project

import android.content.Context
import com.cosmic.ai.core.project.ProjectBuildPlan
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.security.ApprovalAuthorization

class ProjectPackageManager(
    private val context: Context,
    private val adapter: ProjectExecutionAdapter = LocalProjectExecutionAdapter(context),
    private val probe: AndroidRuntimeProbe = AndroidRuntimeProbe()
) {
    fun createInstallPlan(
        manifestPath: String,
        workingDirectory: String,
        manager: String
    ): ProjectBuildPlan {
        val normalizedManager = manager.trim().lowercase()
        require(normalizedManager.matches(Regex("^[a-z0-9_-]{1,32}$"))) { "Invalid package manager name: $manager" }
        val argv = when (normalizedManager) {
            "pnpm" -> listOf("pnpm", "install")
            "yarn" -> listOf("yarn", "install")
            "npm" -> listOf("npm", "install")
            "cargo" -> listOf("cargo", "fetch")
            "go" -> listOf("go", "mod", "download")
            "pip", "pip3" -> listOf("python3", "-m", "pip", "install", "-r", manifestPath.substringAfterLast('/'))
            else -> listOf(normalizedManager, "install")
        }
        return ProjectBuildPlan(
            manifest = manifestPath,
            workingDirectory = workingDirectory,
            action = ProjectBuildPlan.Action.BUILD,
            argv = argv,
            requiresNetwork = true,
            requiresApproval = true
        )
    }

    suspend fun isManagerAvailable(manager: String): Boolean {
        val exe = when (manager.trim().lowercase()) {
            "pip", "pip3" -> "python3"
            else -> manager.trim()
        }
        return probe.isAvailable(exe)
    }

    suspend fun install(
        workspace: ProjectWorkspaceProvider,
        plan: ProjectBuildPlan,
        policy: PermissionPolicy,
        authorization: ApprovalAuthorization? = null,
        timeoutSeconds: Int = 120,
        onOutputChunk: ((String) -> Unit)? = null
    ): ProjectExecutionReport {
        return adapter.execute(
            workspace = workspace,
            plan = plan,
            policy = policy,
            authorization = authorization,
            timeoutSeconds = timeoutSeconds,
            onOutputChunk = onOutputChunk
        )
    }
}
