// language: kotlin, file: GitTools.kt, runtime: ProcessBuilder, target: COSMIC TOOLS git
package com.cosmic.ai.tools.git

import android.content.Context
import com.cosmic.ai.domain.provider.ToolDefinition
import com.cosmic.ai.runtime.tools.Tool
import com.cosmic.ai.runtime.tools.ToolResult
import com.cosmic.ai.domain.agent.PermissionPolicy
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class GitStatusTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "git_status",
        description = "Run git status in the app working directory (or project if mapped). Returns porcelain status.",
        parametersJsonSchema = """{"type":"object","properties":{},"required":[]}"""
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowTerminal) return ToolResult.Failure("Terminal permission denied")
        return runGit(listOf("status", "--porcelain"))
    }

    private suspend fun runGit(args: List<String>): ToolResult = withContext(Dispatchers.IO) {
        try {
            val cmd = listOf("git") + args
            val p = ProcessBuilder(cmd).directory(context.filesDir).redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val code = p.waitFor()
            if (code != 0 && out.contains("not a git repository")) {
                ToolResult.Failure("Not a git repository in app files dir. Clone or init first.")
            } else {
                ToolResult.Success("exit=$code\n$out")
            }
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: "git failed")
        }
    }
}

class GitDiffTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "git_diff",
        description = "Show git diff. Optional path argument.",
        parametersJsonSchema = """{"type":"object","properties":{"path":{"type":"string"}},"required":[]}"""
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowTerminal) return ToolResult.Failure("Terminal permission denied")
        val path = JSONObject(argsJson).optString("path", "")
        val args = if (path.isBlank()) listOf("diff") else listOf("diff", "--", path)
        return withContext(Dispatchers.IO) {
            try {
                val p = ProcessBuilder(listOf("git") + args).directory(context.filesDir).redirectErrorStream(true).start()
                val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
                val code = p.waitFor()
                ToolResult.Success("exit=$code\n$out")
            } catch (e: Exception) {
                ToolResult.Failure(e.message ?: "git diff failed")
            }
        }
    }
}

class GitLogTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "git_log",
        description = "Show recent git commits (oneline, max 20).",
        parametersJsonSchema = """{"type":"object","properties":{"n":{"type":"integer"}},"required":[]}"""
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowTerminal) return ToolResult.Failure("Terminal permission denied")
        val n = JSONObject(argsJson).optInt("n", 20).coerceIn(1, 50)
        return withContext(Dispatchers.IO) {
            try {
                val p = ProcessBuilder("git", "log", "--oneline", "-n", n.toString())
                    .directory(context.filesDir).redirectErrorStream(true).start()
                val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
                ToolResult.Success(out.ifBlank { "(empty)" })
            } catch (e: Exception) {
                ToolResult.Failure(e.message ?: "git log failed")
            }
        }
    }
}
