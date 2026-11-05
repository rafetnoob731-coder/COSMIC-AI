// language: kotlin, file: ToolRegistry.kt, runtime: agent tools, target: permission-gated execution
package com.cosmic.ai.runtime.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.security.PermissionGateway
import com.cosmic.ai.tools.filesystem.RollbackFileTool
import com.cosmic.ai.tools.filesystem.WriteFileWithCheckpointTool
import com.cosmic.ai.tools.filesystem.DiffFileTool
import com.cosmic.ai.tools.git.GitStatusTool
import com.cosmic.ai.tools.git.GitDiffTool
import com.cosmic.ai.tools.git.GitLogTool
import com.cosmic.ai.domain.provider.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Failure(val error: String) : ToolResult()
    data class NeedsApproval(val reason: String, val toolName: String, val args: String) : ToolResult()
}

interface Tool {
    val definition: ToolDefinition
    suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult
}

class ListFilesTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "list_files",
        description = "List files and directories under a path relative to the current project root.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string","description":"Relative path, empty for root"}},"required":[]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowFileRead) return ToolResult.Failure("File read permission denied")
        if (projectUri == null) return ToolResult.Failure("No project open")
        return withContext(Dispatchers.IO) {
            try {
                val path = JSONObject(argsJson).optString("path", "")
                val root = DocumentFile.fromTreeUri(context, projectUri)
                    ?: return@withContext ToolResult.Failure("Cannot open project URI")
                val target = if (path.isBlank()) root else resolvePath(root, path)
                    ?: return@withContext ToolResult.Failure("Path not found: $path")
                if (!target.isDirectory) return@withContext ToolResult.Failure("Not a directory")
                val entries = target.listFiles().map { f ->
                    val type = if (f.isDirectory) "dir" else "file"
                    val size = if (f.isFile) f.length() else 0L
                    "$type\t${f.name}\t$size"
                }.sorted()
                ToolResult.Success(entries.joinToString("\n").ifEmpty { "(empty)" })
            } catch (e: Exception) {
                ToolResult.Failure(e.message ?: "list_files failed")
            }
        }
    }
}

class ReadFileTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "read_file",
        description = "Read the full text content of a file relative to the project root. Binary files return an error.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowFileRead) return ToolResult.Failure("File read permission denied")
        if (projectUri == null) return ToolResult.Failure("No project open")
        return withContext(Dispatchers.IO) {
            try {
                val path = JSONObject(argsJson).getString("path")
                val root = DocumentFile.fromTreeUri(context, projectUri)
                    ?: return@withContext ToolResult.Failure("Cannot open project URI")
                val file = resolvePath(root, path)
                    ?: return@withContext ToolResult.Failure("File not found: $path")
                if (!file.isFile) return@withContext ToolResult.Failure("Not a file")
                if (file.length() > 2_000_000) return@withContext ToolResult.Failure("File too large (>2MB)")
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    ToolResult.Success(text)
                } ?: ToolResult.Failure("Cannot open stream")
            } catch (e: Exception) {
                ToolResult.Failure(e.message ?: "read_file failed")
            }
        }
    }
}

class WriteFileTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "write_file",
        description = "Create or overwrite a text file relative to the project root. Requires write permission and may require approval.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowFileWrite) return ToolResult.Failure("File write permission denied")
        if (projectUri == null) return ToolResult.Failure("No project open")
        if (policy.requireApprovalForDestructive) {
            return ToolResult.NeedsApproval("Write to project file", "write_file", argsJson)
        }
        return performWrite(argsJson, projectUri)
    }

    suspend fun performWrite(argsJson: String, projectUri: Uri): ToolResult = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(argsJson)
            val path = json.getString("path")
            val content = json.getString("content")
            val root = DocumentFile.fromTreeUri(context, projectUri)
                ?: return@withContext ToolResult.Failure("Cannot open project URI")
            val segments = path.split("/").filter { it.isNotBlank() }
            if (segments.isEmpty()) return@withContext ToolResult.Failure("Invalid path")
            var dir = root
            for (i in 0 until segments.size - 1) {
                val next = dir.findFile(segments[i]) ?: dir.createDirectory(segments[i])
                if (next == null || !next.isDirectory) return@withContext ToolResult.Failure("Cannot create dir ${segments[i]}")
                dir = next
            }
            val name = segments.last()
            var file = dir.findFile(name)
            if (file == null) {
                file = dir.createFile("text/plain", name)
                    ?: return@withContext ToolResult.Failure("Cannot create file")
            }
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: return@withContext ToolResult.Failure("Cannot open output stream")
            ToolResult.Success("Wrote ${content.length} bytes to $path")
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: "write_file failed")
        }
    }
}

class RunCommandTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "run_command",
        description = "Execute a shell command in the app sandbox. Dangerous commands require approval. Working directory is app files dir unless a project is open.",
        parametersJsonSchema = """
            {"type":"object","properties":{"command":{"type":"string"},"timeout_sec":{"type":"integer"}},"required":["command"]}
        """.trimIndent()
    )

    private val dangerous = listOf("rm ", "rm\t", "mkfs", "dd ", ">", "chmod 777", "su ", "reboot", "shutdown")

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowTerminal) return ToolResult.Failure("Terminal permission denied")
        val command = JSONObject(argsJson).getString("command")
        val isDangerous = dangerous.any { command.contains(it, ignoreCase = true) }
        if (isDangerous && policy.requireApprovalForDestructive) {
            return ToolResult.NeedsApproval("Potentially destructive command", "run_command", argsJson)
        }
        return performRun(argsJson)
    }

    suspend fun performRun(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(argsJson)
            val command = json.getString("command")
            val timeout = json.optInt("timeout_sec", 30).coerceIn(1, 120)
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .directory(context.filesDir)
                .start()
            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val deadline = System.currentTimeMillis() + timeout * 1000L
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    output.appendLine(line)
                    if (output.length > 100_000) {
                        process.destroy()
                        return@withContext ToolResult.Failure("Output truncated (>100KB)")
                    }
                } else if (!process.isAlive) break
                else Thread.sleep(50)
            }
            if (process.isAlive) {
                process.destroy()
                return@withContext ToolResult.Failure("Timed out after ${timeout}s")
            }
            val code = process.waitFor()
            ToolResult.Success("exit=$code\n${output}")
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: "run_command failed")
        }
    }
}

fun resolvePath(root: DocumentFile, relative: String): DocumentFile? {
    var current: DocumentFile = root
    relative.split("/").filter { it.isNotBlank() && it != "." }.forEach { segment ->
        if (segment == "..") return null // no traversal above root
        current = current.findFile(segment) ?: return null
    }
    return current
}

class ToolRegistry(context: Context, searchApiKeyReader: () -> String? = { null }, gateway: PermissionGateway = PermissionGateway()) {
    private val tools: Map<String, Tool> = listOf(
        ListFilesTool(context),
        ReadFileTool(context),
        WriteFileTool(context),
        RunCommandTool(context),
        GitStatusTool(context),
        GitDiffTool(context),
        GitLogTool(context),
        BraveSearchTool(searchApiKeyReader)
    ).associateBy { it.definition.name }

    fun definitions(): List<ToolDefinition> = tools.values.map { it.definition }

    fun get(name: String): Tool? = tools[name]
}
