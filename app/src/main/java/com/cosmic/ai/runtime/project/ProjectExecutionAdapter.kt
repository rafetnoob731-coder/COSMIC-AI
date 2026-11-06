package com.cosmic.ai.runtime.project

import android.content.Context
import com.cosmic.ai.core.project.ProjectBuildPlan
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.security.ApprovalAuthorization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class ExecutionStatus {
    SUCCESS,
    FAILURE,
    TIMED_OUT,
    CANCELLED,
    RUNTIME_UNAVAILABLE,
    PERMISSION_DENIED
}

data class ProjectExecutionReport(
    val plan: ProjectBuildPlan,
    val status: ExecutionStatus,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val errorMessage: String? = null
)

interface ProjectExecutionAdapter {
    suspend fun execute(
        workspace: ProjectWorkspaceProvider,
        plan: ProjectBuildPlan,
        policy: PermissionPolicy,
        authorization: ApprovalAuthorization? = null,
        timeoutSeconds: Int = 60,
        onOutputChunk: ((String) -> Unit)? = null
    ): ProjectExecutionReport
}

class LocalProjectExecutionAdapter(
    private val context: Context,
    private val probe: AndroidRuntimeProbe = AndroidRuntimeProbe()
) : ProjectExecutionAdapter {

    override suspend fun execute(
        workspace: ProjectWorkspaceProvider,
        plan: ProjectBuildPlan,
        policy: PermissionPolicy,
        authorization: ApprovalAuthorization?,
        timeoutSeconds: Int,
        onOutputChunk: ((String) -> Unit)?
    ): ProjectExecutionReport = withContext(Dispatchers.IO) {
        val startNano = System.nanoTime()
        val durationMs = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano) }

        // 1. Permission verification
        val isAuthorized = authorization != null && authorization.expiresAt > System.currentTimeMillis()
        if (!isAuthorized) {
            if (!policy.allowTerminal) {
                return@withContext ProjectExecutionReport(
                    plan = plan,
                    status = ExecutionStatus.PERMISSION_DENIED,
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    durationMs = durationMs(),
                    errorMessage = "Terminal/execution permission is disabled by active policy"
                )
            }
            if (plan.requiresApproval) {
                return@withContext ProjectExecutionReport(
                    plan = plan,
                    status = ExecutionStatus.PERMISSION_DENIED,
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    durationMs = durationMs(),
                    errorMessage = "This project operation requires explicit user approval"
                )
            }
            if (plan.requiresNetwork && !policy.allowNetwork) {
                return@withContext ProjectExecutionReport(
                    plan = plan,
                    status = ExecutionStatus.PERMISSION_DENIED,
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    durationMs = durationMs(),
                    errorMessage = "Network permission is required for this build action"
                )
            }
            if ((plan.action == ProjectBuildPlan.Action.BUILD || plan.action == ProjectBuildPlan.Action.CLEAN) && !policy.allowFileWrite) {
                return@withContext ProjectExecutionReport(
                    plan = plan,
                    status = ExecutionStatus.PERMISSION_DENIED,
                    exitCode = null,
                    stdout = "",
                    stderr = "",
                    durationMs = durationMs(),
                    errorMessage = "File write permission is disabled by active policy"
                )
            }
        }

        // 2. Validate executable argv
        if (plan.argv.isEmpty()) {
            return@withContext ProjectExecutionReport(
                plan = plan,
                status = ExecutionStatus.FAILURE,
                exitCode = null,
                stdout = "",
                stderr = "",
                durationMs = durationMs(),
                errorMessage = "Build plan contains an empty command"
            )
        }
        val executable = plan.argv.first()

        // 3. Runtime probe verification
        // Wrapper scripts like ./gradlew are handled via local filesystem if present;
        // system tools like cargo, go, npm, python3 are checked against the app PATH probe.
        val isLocalScript = executable.startsWith("./") || executable.startsWith("/")
        if (!isLocalScript && !probe.isAvailable(executable)) {
            return@withContext ProjectExecutionReport(
                plan = plan,
                status = ExecutionStatus.RUNTIME_UNAVAILABLE,
                exitCode = null,
                stdout = "",
                stderr = "",
                durationMs = durationMs(),
                errorMessage = "Runtime executable '$executable' is not installed or available on this device"
            )
        }

        // 4. Resolve working directory
        var stagingDir: File? = null
        val targetWorkingDir: File = try {
            when (workspace) {
                is InternalProjectWorkspaceProvider -> {
                    val root = workspace.rootDirectory.canonicalFile
                    val rawDir = plan.workingDirectory.replace('\\', '/').trim()
                    if (rawDir.contains('\u0000')) {
                        return@withContext ProjectExecutionReport(
                            plan = plan,
                            status = ExecutionStatus.PERMISSION_DENIED,
                            exitCode = null,
                            stdout = "",
                            stderr = "",
                            durationMs = durationMs(),
                            errorMessage = "Working directory contains an invalid null character"
                        )
                    }
                    val sub = if (rawDir.isBlank() || rawDir == ".") root else File(root, rawDir).canonicalFile
                    if (!sub.toPath().startsWith(root.toPath())) {
                        return@withContext ProjectExecutionReport(
                            plan = plan,
                            status = ExecutionStatus.PERMISSION_DENIED,
                            exitCode = null,
                            stdout = "",
                            stderr = "",
                            durationMs = durationMs(),
                            errorMessage = "Working directory escapes the workspace root"
                        )
                    }
                    if (!sub.isDirectory && !sub.mkdirs()) {
                        return@withContext ProjectExecutionReport(
                            plan = plan,
                            status = ExecutionStatus.FAILURE,
                            exitCode = null,
                            stdout = "",
                            stderr = "",
                            durationMs = durationMs(),
                            errorMessage = "Could not access or create working directory: ${plan.workingDirectory}"
                        )
                    }
                    sub
                }
                is SafProjectWorkspaceProvider -> {
                    // Stage workspace files into app cache for process execution
                    val base = File(context.cacheDir, "project-exec-${UUID.randomUUID().toString().take(8)}").canonicalFile
                    if (!base.mkdirs()) {
                        return@withContext ProjectExecutionReport(
                            plan = plan,
                            status = ExecutionStatus.FAILURE,
                            exitCode = null,
                            stdout = "",
                            stderr = "",
                            durationMs = durationMs(),
                            errorMessage = "Could not initialize isolated execution directory"
                        )
                    }
                    stagingDir = base
                    stageSafWorkspace(workspace, base, maxBytes = 30 * 1024 * 1024)
                    val sub = if (plan.workingDirectory.isBlank()) base else File(base, plan.workingDirectory).canonicalFile
                    if (!sub.toPath().startsWith(base.toPath())) {
                        base.deleteRecursively()
                        return@withContext ProjectExecutionReport(
                            plan = plan,
                            status = ExecutionStatus.PERMISSION_DENIED,
                            exitCode = null,
                            stdout = "",
                            stderr = "",
                            durationMs = durationMs(),
                            errorMessage = "Working directory escapes the workspace boundary"
                        )
                    }
                    sub.mkdirs()
                    sub
                }
                else -> {
                    return@withContext ProjectExecutionReport(
                        plan = plan,
                        status = ExecutionStatus.FAILURE,
                        exitCode = null,
                        stdout = "",
                        stderr = "",
                        durationMs = durationMs(),
                        errorMessage = "Unsupported workspace provider: ${workspace.javaClass.simpleName}"
                    )
                }
            }
        } catch (error: Exception) {
            stagingDir?.deleteRecursively()
            return@withContext ProjectExecutionReport(
                plan = plan,
                status = ExecutionStatus.FAILURE,
                exitCode = null,
                stdout = "",
                stderr = "",
                durationMs = durationMs(),
                errorMessage = "Workspace staging failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }

        // 5. Execute process
        var process: Process? = null
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val streamError = AtomicReference<Throwable?>(null)
        val tooMuchOutput = AtomicBoolean(false)

        fun drain(stream: InputStream, destination: ByteArrayOutputStream) {
            stream.use { input ->
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val remaining = (MAX_OUTPUT_BYTES - total).coerceAtLeast(0)
                    val accept = minOf(remaining, read)
                    if (accept > 0) {
                        synchronized(destination) { destination.write(buffer, 0, accept) }
                        total += accept
                        onOutputChunk?.invoke(String(buffer, 0, accept, Charsets.UTF_8))
                    }
                    if (accept < read) tooMuchOutput.set(true)
                }
            }
        }

        try {
            val child = ProcessBuilder(plan.argv)
                .directory(targetWorkingDir)
                .start()
            process = child

            val outThread = Thread({
                try { drain(child.inputStream, stdout) }
                catch (e: Exception) { streamError.compareAndSet(null, e) }
            }, "proj-stdout").apply { isDaemon = true; start() }

            val errThread = Thread({
                try { drain(child.errorStream, stderr) }
                catch (e: Exception) { streamError.compareAndSet(null, e) }
            }, "proj-stderr").apply { isDaemon = true; start() }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceIn(1, 300).toLong())
            while (child.isAlive && System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                val remainingMs = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(1L))
                child.waitFor(minOf(100L, remainingMs), TimeUnit.MILLISECONDS)
            }

            if (child.isAlive) {
                child.destroyForcibly()
                child.waitFor()
                return@withContext ProjectExecutionReport(
                    plan = plan,
                    status = ExecutionStatus.TIMED_OUT,
                    exitCode = null,
                    stdout = stdout.toString(Charsets.UTF_8.name()),
                    stderr = stderr.toString(Charsets.UTF_8.name()),
                    durationMs = durationMs(),
                    errorMessage = "Execution timed out after ${timeoutSeconds}s"
                )
            }

            outThread.join(1000)
            errThread.join(1000)

            val exitCode = child.exitValue()
            val stdoutStr = stdout.toString(Charsets.UTF_8.name())
            val stderrStr = stderr.toString(Charsets.UTF_8.name())

            ProjectExecutionReport(
                plan = plan,
                status = if (exitCode == 0) ExecutionStatus.SUCCESS else ExecutionStatus.FAILURE,
                exitCode = exitCode,
                stdout = stdoutStr,
                stderr = stderrStr,
                durationMs = durationMs(),
                errorMessage = if (exitCode != 0) "Process exited with code $exitCode" else null
            )
        } catch (cancelled: CancellationException) {
            process?.destroyForcibly()
            ProjectExecutionReport(
                plan = plan,
                status = ExecutionStatus.CANCELLED,
                exitCode = null,
                stdout = stdout.toString(Charsets.UTF_8.name()),
                stderr = stderr.toString(Charsets.UTF_8.name()),
                durationMs = durationMs(),
                errorMessage = "Execution was cancelled"
            )
        } catch (error: Exception) {
            process?.destroyForcibly()
            ProjectExecutionReport(
                plan = plan,
                status = ExecutionStatus.FAILURE,
                exitCode = null,
                stdout = stdout.toString(Charsets.UTF_8.name()),
                stderr = stderr.toString(Charsets.UTF_8.name()),
                durationMs = durationMs(),
                errorMessage = error.message ?: error.javaClass.simpleName
            )
        } finally {
            if (process?.isAlive == true) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
            stagingDir?.deleteRecursively()
        }
    }

    private suspend fun stageSafWorkspace(workspace: SafProjectWorkspaceProvider, destination: File, maxBytes: Long) {
        val queue = ArrayDeque<String>()
        queue.add("")
        var totalBytes = 0L

        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            when (val list = workspace.list(dir)) {
                is WorkspaceResult.Failure -> continue
                is WorkspaceResult.Success -> {
                    for (entry in list.value) {
                        if (entry.directory) {
                            queue.add(entry.relativePath)
                        } else {
                            if (totalBytes + entry.sizeBytes > maxBytes) continue
                            when (val read = workspace.read(entry.relativePath, 5 * 1024 * 1024)) {
                                is WorkspaceResult.Success -> {
                                    val target = File(destination, entry.relativePath).canonicalFile
                                    if (target.toPath().startsWith(destination.toPath()) && !java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                        target.parentFile?.mkdirs()
                                        target.writeBytes(read.value)
                                        totalBytes += read.value.size
                                    }
                                }
                                is WorkspaceResult.Failure -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_OUTPUT_BYTES = 100 * 1024
    }
}
