package com.cosmic.ai.runtime.project

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipInputStream

/** A scoped project workspace. SAF identifiers are never converted to filesystem paths. */
interface ProjectWorkspaceProvider {
    val workspaceId: String
    suspend fun list(relativePath: String = ""): WorkspaceResult<List<WorkspaceEntry>>
    suspend fun read(relativePath: String, maxBytes: Int = 2_000_000): WorkspaceResult<ByteArray>
    suspend fun write(relativePath: String, content: ByteArray): WorkspaceResult<Unit>
    suspend fun createDirectory(relativePath: String): WorkspaceResult<Unit>
    suspend fun deleteFile(relativePath: String): WorkspaceResult<Unit>
}

data class WorkspaceEntry(val relativePath: String, val directory: Boolean, val sizeBytes: Long)

sealed interface WorkspaceResult<out T> {
    data class Success<T>(val value: T) : WorkspaceResult<T>
    data class Failure(val code: Code, val message: String) : WorkspaceResult<Nothing>
    enum class Code { INVALID_PATH, NOT_FOUND, PERMISSION_REVOKED, WRONG_TYPE, TOO_LARGE, IO, ALREADY_EXISTS }
}

private const val WORKSPACE_MAX_FILE_BYTES = 20 * 1024 * 1024

private fun segments(path: String, allowRoot: Boolean = false): List<String>? {
    val normalized = path.replace('\\', '/')
    if (normalized.startsWith('/') || normalized.contains('\u0000')) return null
    if (normalized.isBlank() && allowRoot) return emptyList()
    val parts = normalized.split('/')
    if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
    return parts
}

class SafProjectWorkspaceProvider(
    private val context: Context,
    private val treeUri: Uri,
    override val workspaceId: String
) : ProjectWorkspaceProvider {
    val uri: Uri get() = treeUri
    override suspend fun list(relativePath: String): WorkspaceResult<List<WorkspaceEntry>> = withContext(Dispatchers.IO) {
        val path = segments(relativePath, allowRoot = true) ?: return@withContext invalidPath()
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext notFound("Selected folder unavailable")
            var node = root
            for (part in path) node = node.findFile(part) ?: return@withContext notFound("Directory not found")
            if (!node.isDirectory) return@withContext wrongType("Target is not a directory")
            WorkspaceResult.Success(node.listFiles().mapNotNull { child ->
                val name = child.name ?: return@mapNotNull null
                WorkspaceEntry((path + name).joinToString("/"), child.isDirectory, if (child.isFile) child.length() else 0L)
            }.sortedBy { it.relativePath })
        } catch (_: SecurityException) { permissionRevoked() }
        catch (_: Exception) { ioFailure("Could not list selected project folder") }
    }

    override suspend fun read(relativePath: String, maxBytes: Int): WorkspaceResult<ByteArray> = withContext(Dispatchers.IO) {
        val path = segments(relativePath) ?: return@withContext invalidPath()
        try {
            val file = resolve(path) ?: return@withContext notFound("File not found")
            if (!file.isFile) return@withContext wrongType("Target is not a file")
            if (file.length() > maxBytes) return@withContext tooLarge("File exceeds the read limit")
            val stream = context.contentResolver.openInputStream(file.uri) ?: return@withContext notFound("File stream unavailable")
            val bytes = stream.use { readBounded(it, maxBytes) } ?: return@withContext tooLarge("File exceeds the read limit")
            WorkspaceResult.Success(bytes)
        } catch (_: SecurityException) { permissionRevoked() }
        catch (_: Exception) { ioFailure("Could not read project file") }
    }

    override suspend fun write(relativePath: String, content: ByteArray): WorkspaceResult<Unit> = withContext(Dispatchers.IO) {
        val path = segments(relativePath) ?: return@withContext invalidPath()
        if (content.size > WORKSPACE_MAX_FILE_BYTES) return@withContext tooLarge("Write exceeds the 20 MB file limit")
        try {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext notFound("Selected folder unavailable")
            var parent = root
            for (part in path.dropLast(1)) parent = parent.findFile(part)?.takeIf { it.isDirectory }
                ?: return@withContext notFound("Parent directory does not exist")
            val name = path.last()
            val existing = parent.findFile(name)
            if (existing != null && !existing.isFile) return@withContext wrongType("A directory exists at the target path")
            val tempName = ".codex-${UUID.randomUUID()}.tmp"
            val temporary = parent.createFile("application/octet-stream", tempName)
                ?: return@withContext ioFailure("Could not create a temporary replacement file")
            try {
                context.contentResolver.openOutputStream(temporary.uri, "wt")?.use { it.write(content) }
                    ?: throw IllegalStateException("Could not open temporary replacement")
                if (existing == null) {
                    if (!temporary.renameTo(name)) throw IllegalStateException("Could not publish new file")
                } else {
                    val backupName = ".codex-${UUID.randomUUID()}.bak"
                    if (!existing.renameTo(backupName)) throw IllegalStateException("Could not preserve existing file before replacement")
                    val backup = parent.findFile(backupName)
                    if (!temporary.renameTo(name)) {
                        backup?.renameTo(name)
                        throw IllegalStateException("Could not publish replacement; previous file restore was attempted")
                    }
                    backup?.delete()
                }
                WorkspaceResult.Success(Unit)
            } catch (error: Exception) {
                temporary.delete()
                ioFailure(error.message ?: "Safe file replacement failed")
            }
        } catch (_: SecurityException) { permissionRevoked() }
        catch (_: Exception) { ioFailure("Could not write project file") }
    }

    override suspend fun createDirectory(relativePath: String): WorkspaceResult<Unit> = withContext(Dispatchers.IO) {
        val path = segments(relativePath) ?: return@withContext invalidPath()
        try {
            var node = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext notFound("Selected folder unavailable")
            for (part in path) {
                val existing = node.findFile(part)
                node = when {
                    existing?.isDirectory == true -> existing
                    existing != null -> return@withContext wrongType("A file exists in the requested directory path")
                    else -> node.createDirectory(part) ?: return@withContext ioFailure("Could not create directory")
                }
            }
            WorkspaceResult.Success(Unit)
        } catch (_: SecurityException) { permissionRevoked() }
        catch (_: Exception) { ioFailure("Could not create project directory") }
    }

    override suspend fun deleteFile(relativePath: String): WorkspaceResult<Unit> = withContext(Dispatchers.IO) {
        val path = segments(relativePath) ?: return@withContext invalidPath()
        try {
            val file = resolve(path) ?: return@withContext notFound("File not found")
            if (!file.isFile) return@withContext wrongType("Only individual files can be deleted")
            if (file.delete()) WorkspaceResult.Success(Unit) else ioFailure("Document provider refused deletion")
        } catch (_: SecurityException) { permissionRevoked() }
        catch (_: Exception) { ioFailure("Could not delete project file") }
    }

    private fun resolve(path: List<String>): DocumentFile? {
        var current = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        for (part in path) current = current.findFile(part) ?: return null
        return current
    }
}

class InternalProjectWorkspaceProvider private constructor(
    rootDirectory: File,
    override val workspaceId: String
) : ProjectWorkspaceProvider {
    private val root = rootDirectory.canonicalFile
    val rootDirectory: File get() = root

    init { require(root.isDirectory || root.mkdirs()) { "Could not create internal workspace" } }

    override suspend fun list(relativePath: String): WorkspaceResult<List<WorkspaceEntry>> = withContext(Dispatchers.IO) {
        val path = segments(relativePath, allowRoot = true) ?: return@withContext invalidPath()
        try {
            val directory = resolve(path) ?: return@withContext notFound("Directory not found")
            if (!directory.isDirectory) return@withContext wrongType("Target is not a directory")
            WorkspaceResult.Success(directory.listFiles().orEmpty().mapNotNull { child ->
                val canonical = child.canonicalFile
                if (!canonical.toPath().startsWith(root.toPath())) return@mapNotNull null
                WorkspaceEntry(root.toPath().relativize(canonical.toPath()).toString().replace(File.separatorChar, '/'), child.isDirectory, if (child.isFile) child.length() else 0L)
            }.sortedBy { it.relativePath })
        } catch (_: Exception) { ioFailure("Could not list internal workspace") }
    }

    override suspend fun read(relativePath: String, maxBytes: Int): WorkspaceResult<ByteArray> = withContext(Dispatchers.IO) {
        val path = segments(relativePath) ?: return@withContext invalidPath()
        try {
            val file = resolve(path) ?: return@withContext notFound("File not found")
            if (!file.isFile) return@withContext wrongType("Target is not a file")
            if (file.length() > maxBytes) return@withContext tooLarge("File exceeds the read limit")
            WorkspaceResult.Success(file.readBytes())
        } catch (_: Exception) { ioFailure("Could not read internal project file") }
    }

    override suspend fun write(relativePath: String, content: ByteArray): WorkspaceResult<Unit> = withContext(Dispatchers.IO) {
        val path = segments(relativePath) ?: return@withContext invalidPath()
        if (content.size > WORKSPACE_MAX_FILE_BYTES) return@withContext tooLarge("Write exceeds the 20 MB file limit")
        val parent = resolve(path.dropLast(1)) ?: return@withContext notFound("Parent directory not found")
        if (!parent.isDirectory) return@withContext wrongType("Parent is not a directory")
        val target = File(parent, path.last())
        try {
            if (!target.canonicalFile.toPath().startsWith(root.toPath())) return@withContext invalidPath()
            val temp = File(parent, ".codex-${UUID.randomUUID()}.tmp")
            temp.writeBytes(content)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            WorkspaceResult.Success(Unit)
        } catch (_: Exception) { ioFailure("Safe internal file replacement failed") }
    }

    override suspend fun createDirectory(relativePath: String): WorkspaceResult<Unit> = withContext(Dispatchers.IO) {
        val path = segments(relativePath) ?: return@withContext invalidPath()
        val directory = resolve(path) ?: File(root, path.joinToString("/")).canonicalFile
        if (!directory.toPath().startsWith(root.toPath())) return@withContext invalidPath()
        if (directory.isDirectory || directory.mkdirs()) WorkspaceResult.Success(Unit) else ioFailure("Could not create directory")
    }

    override suspend fun deleteFile(relativePath: String): WorkspaceResult<Unit> = withContext(Dispatchers.IO) {
        val path = segments(relativePath) ?: return@withContext invalidPath()
        val file = resolve(path) ?: return@withContext notFound("File not found")
        if (!file.isFile) return@withContext wrongType("Only individual files can be deleted")
        if (file.delete()) WorkspaceResult.Success(Unit) else ioFailure("Could not delete file")
    }

    private fun resolve(path: List<String>): File? {
        val file = File(root, path.joinToString("/")).canonicalFile
        if (!file.toPath().startsWith(root.toPath())) return null
        return file.takeIf { it.exists() }
    }

    companion object {
        fun create(context: Context, workspaceId: String): InternalProjectWorkspaceProvider {
            val safeId = workspaceId.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) && it != "." && it != ".." }
                ?: throw IllegalArgumentException("Invalid workspace ID")
            return InternalProjectWorkspaceProvider(File(File(context.filesDir, "codex-workspaces"), safeId), safeId)
        }

        internal fun imported(context: Context, destination: File, workspaceId: String): InternalProjectWorkspaceProvider {
            val allowedRoot = File(context.filesDir, "codex-workspaces").canonicalFile
            val canonical = destination.canonicalFile
            require(canonical.toPath().startsWith(allowedRoot.toPath())) { "Imported workspace escaped app-private storage" }
            return InternalProjectWorkspaceProvider(canonical, workspaceId)
        }
    }
}

class ProjectArchiveImporter(private val context: Context) {
    suspend fun import(input: InputStream, workspaceName: String): WorkspaceResult<InternalProjectWorkspaceProvider> = withContext(Dispatchers.IO) {
        val safeName = workspaceName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(80)
        if (safeName.isBlank()) return@withContext WorkspaceResult.Failure(WorkspaceResult.Code.INVALID_PATH, "Invalid project name")
        val base = File(context.filesDir, "codex-workspaces").canonicalFile
        val destination = File(base, "$safeName-${UUID.randomUUID().toString().take(8)}").canonicalFile
        if (!destination.toPath().startsWith(base.toPath()) || !destination.mkdirs()) return@withContext ioFailure("Could not create isolated project workspace")
        try {
            ZipInputStream(input).use { zip ->
                var count = 0
                var totalBytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    count++
                    if (count > MAX_ENTRIES) throw IllegalArgumentException("Archive contains too many entries")
                    val entryName = if (entry.isDirectory) entry.name.trimEnd('/') else entry.name
                    val path = segments(entryName, allowRoot = false) ?: throw IllegalArgumentException("Archive contains an unsafe path")
                    val target = File(destination, path.joinToString("/")).canonicalFile
                    if (!target.toPath().startsWith(destination.toPath())) throw IllegalArgumentException("Archive path escapes workspace")
                    if (entry.isDirectory) {
                        if (!target.mkdirs() && !target.isDirectory) throw IllegalStateException("Could not create archive directory")
                    } else {
                        target.parentFile?.let { if (!it.mkdirs() && !it.isDirectory) throw IllegalStateException("Could not create archive parent") }
                        val output = target.outputStream().buffered()
                        output.use {
                            val buffer = ByteArray(8192)
                            var entryBytes = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = zip.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalBytes += read
                                if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_ARCHIVE_BYTES) throw IllegalArgumentException("Archive expands beyond configured limits")
                                it.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            WorkspaceResult.Success(InternalProjectWorkspaceProvider.imported(context, destination, safeName))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            destination.deleteRecursively()
            throw cancelled
        } catch (error: Exception) {
            destination.deleteRecursively()
            WorkspaceResult.Failure(WorkspaceResult.Code.IO, error.message ?: "Project archive import failed")
        }
    }

    companion object {
        private const val MAX_ENTRIES = 5_000
        private const val MAX_ENTRY_BYTES = 20L * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 100L * 1024 * 1024
    }
}

private suspend fun readBounded(input: InputStream, limit: Int): ByteArray? {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun invalidPath() = WorkspaceResult.Failure(WorkspaceResult.Code.INVALID_PATH, "Path escapes or is invalid for this workspace")
private fun notFound(message: String) = WorkspaceResult.Failure(WorkspaceResult.Code.NOT_FOUND, message)
private fun permissionRevoked() = WorkspaceResult.Failure(WorkspaceResult.Code.PERMISSION_REVOKED, "Storage access was revoked")
private fun wrongType(message: String) = WorkspaceResult.Failure(WorkspaceResult.Code.WRONG_TYPE, message)
private fun tooLarge(message: String) = WorkspaceResult.Failure(WorkspaceResult.Code.TOO_LARGE, message)
private fun ioFailure(message: String) = WorkspaceResult.Failure(WorkspaceResult.Code.IO, message.take(300))
