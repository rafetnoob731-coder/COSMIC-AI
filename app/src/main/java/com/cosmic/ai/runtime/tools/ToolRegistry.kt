// language: kotlin, file: ToolRegistry.kt, runtime: agent tools, target: permission-gated execution
package com.cosmic.ai.runtime.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.security.PermissionGateway
import com.cosmic.ai.security.PermissionDecision
import com.cosmic.ai.security.PermissionLevel
import com.cosmic.ai.security.SecurityPolicy
import com.cosmic.ai.security.ApprovalAuthorization
import java.security.MessageDigest
import com.cosmic.ai.tools.filesystem.RollbackFileTool
import com.cosmic.ai.tools.filesystem.WriteFileWithCheckpointTool
import com.cosmic.ai.tools.filesystem.DiffFileTool
import com.cosmic.ai.tools.git.GitStatusTool
import com.cosmic.ai.tools.git.GitDiffTool
import com.cosmic.ai.tools.git.GitLogTool
import com.cosmic.ai.domain.provider.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Failure(val error: String) : ToolResult()
    data class Unknown(val reason: String) : ToolResult()
    data class NeedsApproval(val reason: String, val toolName: String, val args: String) : ToolResult()
}

internal interface Tool {
    val definition: ToolDefinition
    suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult
}

private fun normalizeDirectPath(path: String): String {
    var p = path.replace('\\', '/').trim()
    if (p.startsWith("/storage/emulated/0/")) {
        p = p.removePrefix("/storage/emulated/0/").trimStart('/')
    } else if (p.startsWith("/sdcard/")) {
        p = p.removePrefix("/sdcard/").trimStart('/')
    }
    return p
}

private fun workspaceFor(context: Context, projectUri: Uri?): com.cosmic.ai.runtime.project.ProjectWorkspaceProvider {
    if (projectUri != null) {
        return com.cosmic.ai.runtime.project.SafProjectWorkspaceProvider(context, projectUri, projectUri.toString())
    }
    val externalDir = android.os.Environment.getExternalStorageDirectory()
    val canWriteExternal = try {
        (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && android.os.Environment.isExternalStorageManager()) ||
            (externalDir != null && externalDir.canWrite())
    } catch (_: Throwable) {
        false
    }
    val root = if (canWriteExternal && externalDir != null) {
        externalDir
    } else {
        File(context.filesDir, "codex-workspaces/default").apply { mkdirs() }
    }
    return com.cosmic.ai.runtime.project.InternalProjectWorkspaceProvider.direct(root, "direct-workspace")
}

private fun com.cosmic.ai.runtime.project.WorkspaceResult.Failure.toToolFailure() = ToolResult.Failure(message)

internal class ListFilesTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "list_files",
        description = "List files and directories under a path relative to the current project root.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string","description":"Relative path, empty for root"}},"required":[]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowFileRead) return ToolResult.Failure("File read permission denied")
        val rawPath = runCatching { JSONObject(argsJson).optString("path", "") }.getOrElse { return ToolResult.Failure("Invalid directory path") }
        val path = normalizeDirectPath(rawPath)
        return when (val result = workspaceFor(context, projectUri).list(path)) {
            is com.cosmic.ai.runtime.project.WorkspaceResult.Failure -> result.toToolFailure()
            is com.cosmic.ai.runtime.project.WorkspaceResult.Success -> ToolResult.Success(
                result.value.joinToString("\n") { entry ->
                    val type = if (entry.directory) "dir" else "file"
                    val name = entry.relativePath.substringAfterLast('/')
                    "$type\t$name\t${entry.sizeBytes}"
                }.ifEmpty { "(empty)" }
            )
        }
    }
}

internal class ReadFileTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "read_file",
        description = "Read the full text content of a file relative to the project root. Binary files return an error.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowFileRead) return ToolResult.Failure("File read permission denied")
        val rawPath = runCatching { JSONObject(argsJson).getString("path") }.getOrElse { return ToolResult.Failure("Invalid file path") }
        val path = normalizeDirectPath(rawPath)
        return when (val result = workspaceFor(context, projectUri).read(path)) {
            is com.cosmic.ai.runtime.project.WorkspaceResult.Failure -> result.toToolFailure()
            is com.cosmic.ai.runtime.project.WorkspaceResult.Success -> ToolResult.Success(result.value.toString(Charsets.UTF_8))
        }
    }
}

internal class WriteFileTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "write_file",
        description = "Create or safely replace a text file relative to the project root. Requires write permission and approval according to policy.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowFileWrite) return ToolResult.Failure("File write permission denied")
        if (policy.requireApprovalForDestructive) return ToolResult.NeedsApproval("Write to project file", "write_file", argsJson)
        val json = runCatching { JSONObject(argsJson) }.getOrElse { return ToolResult.Failure("Invalid file arguments") }
        val rawPath = json.opt("path") as? String ?: return ToolResult.Failure("A file path is required")
        val path = normalizeDirectPath(rawPath)
        val content = json.opt("content") as? String ?: return ToolResult.Failure("File content is required")
        return when (val result = workspaceFor(context, projectUri).write(path, content.toByteArray(Charsets.UTF_8))) {
            is com.cosmic.ai.runtime.project.WorkspaceResult.Failure -> result.toToolFailure()
            is com.cosmic.ai.runtime.project.WorkspaceResult.Success -> ToolResult.Success("Wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $path")
        }
    }
}

internal class CreateDirectoryTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "create_directory",
        description = "Create a directory at a path relative to the selected project root. Requires write permission and approval according to policy.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowFileWrite) return ToolResult.Failure("Project write permission was not authorized")
        val rawPath = runCatching { JSONObject(argsJson).getString("path") }.getOrElse { return ToolResult.Failure("Invalid directory path arguments") }
        val path = normalizeDirectPath(rawPath)
        if (path.isBlank() || path.trim('/') == ".") return ToolResult.Failure("Project root cannot be recreated")
        return when (val result = workspaceFor(context, projectUri).createDirectory(path)) {
            is com.cosmic.ai.runtime.project.WorkspaceResult.Failure -> result.toToolFailure()
            is com.cosmic.ai.runtime.project.WorkspaceResult.Success -> ToolResult.Success("Created directory: $path")
        }
    }
}

internal class EditFileTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "edit_file",
        description = "Replace exact text in an existing UTF-8 project file. A unique match is required unless replace_all is explicitly true.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},"new_text":{"type":"string"},"replace_all":{"type":"boolean"}},"required":["path","old_text","new_text"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowFileWrite) return ToolResult.Failure("Project write permission was not authorized")
        val json = runCatching { JSONObject(argsJson) }.getOrElse { return ToolResult.Failure("Invalid edit arguments") }
        val rawPath = json.opt("path") as? String ?: return ToolResult.Failure("A project file path is required")
        val path = normalizeDirectPath(rawPath)
        val oldText = json.opt("old_text") as? String ?: return ToolResult.Failure("The exact text to replace is required")
        val newText = json.opt("new_text") as? String ?: return ToolResult.Failure("Replacement text is required")
        if (oldText.isEmpty()) return ToolResult.Failure("Refusing an empty-text edit")
        val workspace = workspaceFor(context, projectUri)
        val source = when (val read = workspace.read(path)) {
            is com.cosmic.ai.runtime.project.WorkspaceResult.Failure -> return read.toToolFailure()
            is com.cosmic.ai.runtime.project.WorkspaceResult.Success -> read.value.toString(Charsets.UTF_8)
        }
        val occurrences = Regex(Regex.escape(oldText)).findAll(source).count()
        if (occurrences == 0) return ToolResult.Failure("Requested text was not found; file was not changed")
        val replaceAll = json.optBoolean("replace_all", false)
        if (occurrences > 1 && !replaceAll) return ToolResult.Failure("Text matched $occurrences places; specify replace_all=true or a more specific edit")
        val updated = if (replaceAll) source.replace(oldText, newText) else source.replaceFirst(oldText, newText)
        return when (val write = workspace.write(path, updated.toByteArray(Charsets.UTF_8))) {
            is com.cosmic.ai.runtime.project.WorkspaceResult.Failure -> write.toToolFailure()
            is com.cosmic.ai.runtime.project.WorkspaceResult.Success -> ToolResult.Success("Edited $path (${if (replaceAll) occurrences else 1} replacement${if ((if (replaceAll) occurrences else 1) == 1) "" else "s"})")
        }
    }
}

internal class DeleteFileTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "delete_file",
        description = "Delete one file (not a directory) inside the selected project. Always requires explicit approval.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowFileWrite) return ToolResult.Failure("Project write permission was not authorized")
        val rawPath = runCatching { JSONObject(argsJson).getString("path") }.getOrElse { return ToolResult.Failure("Invalid delete path arguments") }
        val path = normalizeDirectPath(rawPath)
        return when (val result = workspaceFor(context, projectUri).deleteFile(path)) {
            is com.cosmic.ai.runtime.project.WorkspaceResult.Failure -> result.toToolFailure()
            is com.cosmic.ai.runtime.project.WorkspaceResult.Success -> ToolResult.Success("Deleted file: $path")
        }
    }
}

internal class RunCommandTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "run_command",
        description = "Execute a shell command in the app-private directory. This command cannot operate on a SAF project workspace.",
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

    private suspend fun performRun(argsJson: String): ToolResult = withContext(Dispatchers.IO) {
        var process: Process? = null
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val tooMuchOutput = AtomicBoolean(false)
        val streamError = AtomicReference<Throwable?>(null)
        fun drain(stream: InputStream, destination: ByteArrayOutputStream) {
            stream.use { input ->
                val chunk = ByteArray(4096)
                var kept = 0
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    val remaining = (100_000 - kept).coerceAtLeast(0)
                    val accepted = minOf(remaining, count)
                    if (accepted > 0) {
                        synchronized(destination) { destination.write(chunk, 0, accepted) }
                        kept += accepted
                    }
                    if (accepted < count) tooMuchOutput.set(true)
                }
            }
        }
        try {
            val json = JSONObject(argsJson)
            val command = json.getString("command").takeIf { it.isNotBlank() }
                ?: return@withContext ToolResult.Failure("Command is empty")
            val timeout = json.optInt("timeout_sec", 30).coerceIn(1, 120)
            val child = ProcessBuilder("sh", "-c", command)
                .directory(context.filesDir)
                .start()
            process = child
            val outThread = Thread({ try { drain(child.inputStream, stdout) } catch (error: Exception) { streamError.compareAndSet(null, error) } }, "codex-stdout").apply { isDaemon = true; start() }
            val errThread = Thread({ try { drain(child.errorStream, stderr) } catch (error: Exception) { streamError.compareAndSet(null, error) } }, "codex-stderr").apply { isDaemon = true; start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout.toLong())
            while (child.isAlive && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (tooMuchOutput.get()) {
                    child.destroyForcibly()
                    child.waitFor()
                    return@withContext ToolResult.Unknown("Command output exceeded the 100 KB capture limit; side effects may have occurred")
                }
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(1L))
                child.waitFor(minOf(100L, remainingMillis.coerceAtLeast(1L)), TimeUnit.MILLISECONDS)
            }
            if (child.isAlive) {
                child.destroyForcibly()
                child.waitFor()
                return@withContext ToolResult.Unknown("Command timed out after ${timeout}s; side effects may have occurred")
            }
            outThread.join(1_000)
            errThread.join(1_000)
            if (tooMuchOutput.get()) return@withContext ToolResult.Unknown("Command output exceeded the 100 KB capture limit; side effects may have occurred")
            streamError.get()?.let { return@withContext ToolResult.Failure("Could not capture command output (${it.javaClass.simpleName})") }
            val exitCode = child.exitValue()
            val report = "exit=$exitCode\nstdout:\n${stdout.toString(Charsets.UTF_8.name())}\nstderr:\n${stderr.toString(Charsets.UTF_8.name())}"
            if (exitCode == 0) ToolResult.Success(report) else ToolResult.Failure(report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolResult.Failure("Command execution failed (${error.javaClass.simpleName})")
        } finally {
            if (process?.isAlive == true) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
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

class ToolRegistry(context: Context, private val gateway: PermissionGateway = PermissionGateway()) {
    private val tools: Map<String, Tool> = listOf(
        ListFilesTool(context),
        ReadFileTool(context),
        WriteFileTool(context),
        EditFileTool(context),
        CreateDirectoryTool(context),
        DeleteFileTool(context),
        RunCommandTool(context),
        GitStatusTool(context),
        GitDiffTool(context),
        GitLogTool(context),
        DuckDuckGoSearchTool()
    ).associateBy { it.definition.name }

    fun definitions(): List<ToolDefinition> = tools.values.map { it.definition }

    /** The only supported dispatch path: classify and authorize immediately before execution. */
    fun approvalScope(name: String, argsJson: String): String = MessageDigest.getInstance("SHA-256")
        .digest((name + "\u0000" + argsJson).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    suspend fun execute(
        name: String, argsJson: String, policy: PermissionPolicy, projectUri: Uri?,
        approval: ApprovalAuthorization? = null, approvalTaskId: String? = null, approvalCallId: String? = null,
        onAuthorized: suspend () -> Unit = {}
    ): ToolResult {
        val tool = tools[name] ?: return ToolResult.Failure("Unknown tool: $name")
        validateArguments(tool.definition, argsJson)?.let { return ToolResult.Failure(it) }
        if (name == "run_command" && projectUri != null) {
            return ToolResult.Failure("Project-scoped command execution is unavailable for SAF workspaces; command was not run")
        }
        val level = when (name) {
            "list_files", "read_file" -> PermissionLevel.READ
            "write_file", "edit_file", "create_directory" -> PermissionLevel.WRITE
            "delete_file" -> PermissionLevel.ADMIN
            "run_command", "git_status", "git_diff", "git_log" -> PermissionLevel.EXECUTE
            "duckduckgo_instant_answer" -> PermissionLevel.NETWORK
            else -> PermissionLevel.ADMIN
        }
        if (name in setOf("list_files", "read_file", "write_file", "edit_file", "create_directory", "delete_file")) {
            val path = runCatching { JSONObject(argsJson).optString("path", "") }.getOrElse {
                return ToolResult.Failure("Invalid file path arguments")
            }
            if (name != "list_files" || path.isNotBlank()) {
                when (val pathDecision = gateway.validateRelativePath(path)) {
                    PermissionDecision.Allow -> Unit
                    is PermissionDecision.Deny -> return ToolResult.Failure(pathDecision.reason)
                    is PermissionDecision.NeedsApproval -> return ToolResult.Failure("File path validation failed closed")
                }
            }
        }
        val detail = when (name) {
            "run_command" -> runCatching { JSONObject(argsJson).optString("command") }.getOrDefault("invalid command arguments")
            "write_file", "edit_file", "create_directory", "delete_file" -> runCatching { JSONObject(argsJson).optString("path") }.getOrDefault("invalid file arguments")
            else -> name
        }
        val securityPolicy = SecurityPolicy(
            allowRead = true,
            allowWrite = true,
            allowExecute = true,
            allowAdmin = false,
            allowNetwork = true,
            requireApprovalRead = !policy.allowFileRead,
            requireApprovalWrite = policy.requireApprovalForDestructive || !policy.allowFileWrite,
            // The tool's command classifier adds destructive approval after this category gate.
            requireApprovalExecute = !policy.allowTerminal,
            requireApprovalAdmin = true,
            requireApprovalNetwork = !policy.allowNetwork
        )
        val scopeHash = approvalScope(name, argsJson)
        if (approval != null && (approval.action != name || approval.scopeHash != scopeHash || approval.expiresAt <= System.currentTimeMillis())) {
            return ToolResult.Failure("Approval scope mismatch or expiration")
        }
        when (val decision = gateway.check(level, name, detail, securityPolicy, approval, approvalTaskId, approvalCallId, scopeHash)) {
            PermissionDecision.Allow -> Unit
            is PermissionDecision.Deny -> return ToolResult.Failure(decision.reason)
            is PermissionDecision.NeedsApproval -> return ToolResult.NeedsApproval(decision.reason, name, argsJson)
        }
        onAuthorized()
        val executionPolicy = if (approval == null) policy else policy.copy(
            allowFileRead = true, allowFileWrite = true, allowTerminal = true, allowNetwork = true,
            requireApprovalForDestructive = false
        )
        return try {
            tool.execute(argsJson, executionPolicy, projectUri)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val safe = com.cosmic.ai.security.SensitiveTextRedactor.redact(error.message ?: error.javaClass.simpleName).take(300)
            ToolResult.Failure("Tool operation failed: $safe")
        }
    }

    private fun validateArguments(definition: ToolDefinition, encoded: String): String? {
        val args = try { JSONObject(encoded) } catch (_: Exception) { return "Tool arguments must be a JSON object" }
        val schema = try { JSONObject(definition.parametersJsonSchema) } catch (_: Exception) {
            return "Registered tool schema is unavailable"
        }
        val required = schema.optJSONArray("required") ?: org.json.JSONArray()
        for (index in 0 until required.length()) {
            val field = required.optString(index)
            if (field.isBlank() || !args.has(field) || args.opt(field) == null || args.opt(field) == JSONObject.NULL) {
                return "Required tool argument is missing: $field"
            }
        }
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val keys = args.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = args.opt(key) ?: return "Tool argument is invalid: $key"
            if (value == JSONObject.NULL) return "Tool argument is null: $key"
            val expected = properties.optJSONObject(key)?.optString("type").orEmpty()
            val valid = when (expected) {
                "string" -> value is String
                "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
                "number" -> value is Number
                "boolean" -> value is Boolean
                "object" -> value is JSONObject
                "array" -> value is org.json.JSONArray
                else -> true
            }
            if (!valid) return "Tool argument has an invalid type: $key"
        }
        return null
    }
}
