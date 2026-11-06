// language: kotlin, file: DiffRollback.kt, runtime: pure + Android SAF adapter, target: M3 filesystem
package com.cosmic.ai.tools.filesystem

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.domain.provider.ToolDefinition
import com.cosmic.ai.runtime.tools.Tool
import com.cosmic.ai.runtime.tools.ToolResult
import com.cosmic.ai.runtime.tools.resolvePath
import com.cosmic.ai.security.PermissionGateway
import com.cosmic.ai.security.PermissionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory checkpoint store for rollback (session-scoped). */
object CheckpointStore {
    data class Checkpoint(val id: String, val path: String, val content: String, val at: Long)
    private val map = ConcurrentHashMap<String, Checkpoint>()

    fun save(path: String, content: String): String {
        val id = UUID.randomUUID().toString()
        map[id] = Checkpoint(id, path, content, System.currentTimeMillis())
        return id
    }

    fun get(id: String): Checkpoint? = map[id]
    fun remove(id: String) { map.remove(id) }
}

internal class DiffFileTool(
    private val context: Context,
    private val gateway: PermissionGateway
) : Tool {
    override val definition = ToolDefinition(
        name = "diff_file",
        description = "Compute a unified-style diff between current file content and proposed content. Does not write.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"},"proposed":{"type":"string"}},"required":["path","proposed"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        val pathCheck = gateway.validateRelativePath(JSONObject(argsJson).optString("path", ""))
        if (pathCheck is com.cosmic.ai.security.PermissionDecision.Deny)
            return ToolResult.Failure(pathCheck.reason)
        when (val d = gateway.check(PermissionLevel.READ, "diff_file", argsJson)) {
            is com.cosmic.ai.security.PermissionDecision.Deny -> return ToolResult.Failure(d.reason)
            is com.cosmic.ai.security.PermissionDecision.NeedsApproval ->
                return ToolResult.NeedsApproval(d.reason, "diff_file", argsJson)
            else -> {}
        }
        if (projectUri == null) return ToolResult.Failure("No project open")
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject(argsJson)
                val path = json.getString("path")
                val proposed = json.getString("proposed")
                val root = DocumentFile.fromTreeUri(context, projectUri)
                    ?: return@withContext ToolResult.Failure("Cannot open project")
                val file = resolvePath(root, path)
                val current = if (file != null && file.isFile) {
                    context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.readText() ?: ""
                } else ""
                val diff = unifiedDiff(path, current, proposed)
                ToolResult.Success(diff)
            } catch (e: Exception) {
                ToolResult.Failure(e.message ?: "diff failed")
            }
        }
    }
}

internal class WriteFileWithCheckpointTool(
    private val context: Context,
    private val gateway: PermissionGateway
) : Tool {
    override val definition = ToolDefinition(
        name = "write_file_safe",
        description = "Write file after creating a checkpoint. Returns checkpoint_id for rollback. Requires WRITE approval when policy demands it.",
        parametersJsonSchema = """
            {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        val path = JSONObject(argsJson).optString("path", "")
        val pathCheck = gateway.validateRelativePath(path)
        if (pathCheck is com.cosmic.ai.security.PermissionDecision.Deny)
            return ToolResult.Failure(pathCheck.reason)
        when (val d = gateway.check(PermissionLevel.WRITE, "write_file_safe", path)) {
            is com.cosmic.ai.security.PermissionDecision.Deny -> return ToolResult.Failure(d.reason)
            is com.cosmic.ai.security.PermissionDecision.NeedsApproval ->
                return ToolResult.NeedsApproval(d.reason, "write_file_safe", argsJson)
            else -> {}
        }
        if (projectUri == null) return ToolResult.Failure("No project open")
        return perform(argsJson, projectUri)
    }

    private suspend fun perform(argsJson: String, projectUri: Uri): ToolResult = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(argsJson)
            val path = json.getString("path")
            val content = json.getString("content")
            val root = DocumentFile.fromTreeUri(context, projectUri)
                ?: return@withContext ToolResult.Failure("Cannot open project")
            val existing = resolvePath(root, path)
            val previous = if (existing != null && existing.isFile) {
                context.contentResolver.openInputStream(existing.uri)?.bufferedReader()?.readText() ?: ""
            } else ""
            val checkpointId = CheckpointStore.save(path, previous)

            val segments = path.split("/").filter { it.isNotBlank() }
            var dir = root
            for (i in 0 until segments.size - 1) {
                val next = dir.findFile(segments[i]) ?: dir.createDirectory(segments[i])
                if (next == null || !next.isDirectory) return@withContext ToolResult.Failure("Cannot create dir")
                dir = next
            }
            val name = segments.last()
            var file = dir.findFile(name)
            if (file == null) {
                file = dir.createFile("text/plain", name)
                    ?: return@withContext ToolResult.Failure("Cannot create file")
            }
            context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            } ?: return@withContext ToolResult.Failure("Cannot open output")
            ToolResult.Success("Wrote ${content.length} bytes to $path\ncheckpoint_id=$checkpointId")
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: "write failed")
        }
    }
}

internal class RollbackFileTool(
    private val context: Context,
    private val gateway: PermissionGateway
) : Tool {
    override val definition = ToolDefinition(
        name = "rollback_file",
        description = "Restore file content from a checkpoint_id produced by write_file_safe.",
        parametersJsonSchema = """
            {"type":"object","properties":{"checkpoint_id":{"type":"string"}},"required":["checkpoint_id"]}
        """.trimIndent()
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        when (val d = gateway.check(PermissionLevel.WRITE, "rollback_file", argsJson)) {
            is com.cosmic.ai.security.PermissionDecision.Deny -> return ToolResult.Failure(d.reason)
            is com.cosmic.ai.security.PermissionDecision.NeedsApproval ->
                return ToolResult.NeedsApproval(d.reason, "rollback_file", argsJson)
            else -> {}
        }
        if (projectUri == null) return ToolResult.Failure("No project open")
        return withContext(Dispatchers.IO) {
            try {
                val id = JSONObject(argsJson).getString("checkpoint_id")
                val cp = CheckpointStore.get(id) ?: return@withContext ToolResult.Failure("Unknown checkpoint")
                val root = DocumentFile.fromTreeUri(context, projectUri)
                    ?: return@withContext ToolResult.Failure("Cannot open project")
                val pathCheck = gateway.validateRelativePath(cp.path)
                if (pathCheck is com.cosmic.ai.security.PermissionDecision.Deny)
                    return@withContext ToolResult.Failure(pathCheck.reason)
                val segments = cp.path.split("/").filter { it.isNotBlank() }
                var dir = root
                for (i in 0 until segments.size - 1) {
                    val next = dir.findFile(segments[i]) ?: return@withContext ToolResult.Failure("Path missing")
                    dir = next
                }
                val file = dir.findFile(segments.last())
                    ?: return@withContext ToolResult.Failure("File missing")
                context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                    it.write(cp.content.toByteArray(Charsets.UTF_8))
                } ?: return@withContext ToolResult.Failure("Cannot write")
                CheckpointStore.remove(id)
                ToolResult.Success("Rolled back ${cp.path} (${cp.content.length} bytes restored)")
            } catch (e: Exception) {
                ToolResult.Failure(e.message ?: "rollback failed")
            }
        }
    }
}

fun unifiedDiff(path: String, before: String, after: String): String {
    if (before == after) return "No changes for $path"
    val a = before.lines()
    val b = after.lines()
    val sb = StringBuilder()
    sb.appendLine("--- a/$path")
    sb.appendLine("+++ b/$path")
    val max = maxOf(a.size, b.size)
    // simple line-oriented diff (not Myers) — honest and readable for mobile review
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) {
        when {
            i < a.size && j < b.size && a[i] == b[j] -> {
                sb.appendLine(" ${a[i]}")
                i++; j++
            }
            j < b.size && (i >= a.size || a[i] != b[j]) -> {
                // prefer showing removal then addition when both exist
                if (i < a.size && (j + 1 >= b.size || a[i] != b.getOrNull(j + 1))) {
                    sb.appendLine("-${a[i]}")
                    i++
                } else {
                    sb.appendLine("+${b[j]}")
                    j++
                }
            }
            i < a.size -> {
                sb.appendLine("-${a[i]}")
                i++
            }
            else -> break
        }
        if (sb.length > 80_000) {
            sb.appendLine("... diff truncated")
            break
        }
    }
    return sb.toString()
}
