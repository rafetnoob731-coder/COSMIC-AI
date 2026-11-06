// language: kotlin, file: AgentRuntime.kt, runtime: coroutines + tool loop, target: multi-step agent
package com.cosmic.ai.runtime.agent

import android.content.Context
import android.net.Uri
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.data.local.db.TaskLifecycleRepository
import com.cosmic.ai.data.local.db.ApprovalLifecycleRepository
import com.cosmic.ai.security.ApprovalAuthorization
import com.cosmic.ai.core.state.TaskTransitions
import com.cosmic.ai.runtime.background.AgentTaskWork
import com.cosmic.ai.runtime.background.TaskWorkerOutcome
import com.cosmic.ai.domain.agent.*
import com.cosmic.ai.domain.provider.*
import com.cosmic.ai.runtime.providers.ProviderFactory
import com.cosmic.ai.runtime.tools.ToolRegistry
import com.cosmic.ai.security.PermissionGateway
import com.cosmic.ai.runtime.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.IOException
import java.security.MessageDigest

private fun JSONObject.nonNullString(name: String): String? = when (val value = opt(name)) {
    is String -> value
    else -> null
}

internal fun MessageEntity.toProviderMessage(): ChatMessage {
    val storedCalls = toolCallsJson?.takeIf { it.isNotBlank() }?.let { encoded ->
        val array = JSONArray(encoded)
        buildList {
            for (index in 0 until array.length()) {
                val call = array.optJSONObject(index) ?: throw IOException("Saved tool call $index is malformed")
                val id = call.nonNullString("id") ?: throw IOException("Saved tool call $index has no ID")
                val name = call.nonNullString("name") ?: throw IOException("Saved tool call $index has no name")
                val arguments = call.nonNullString("arguments") ?: throw IOException("Saved tool call $index has no arguments")
                try { JSONObject(arguments) } catch (error: Exception) {
                    throw IOException("Saved arguments for $name are malformed", error)
                }
                add(ToolCall(id, name, arguments))
            }
        }.takeIf { it.isNotEmpty() }
    }
    return ChatMessage(role, content, toolCallId, storedCalls)
}

private class AgentToolExecutionException(val toolName: String, message: String) : IOException(message)

enum class AgentExecutionState {
    THINKING, TOOL_REQUESTED, WAITING_FOR_APPROVAL, EXECUTING, TOOL_COMPLETED, TOOL_FAILED, CANCELLED, COMPLETED
}

class AgentRuntime(
    private val context: Context,
    private val db: CosmicDatabase,
    private val apiKeyReader: (String) -> String?,
    private val mcpManager: com.cosmic.ai.runtime.mcp.McpClientManager,
    private val providerResolver: (ProviderType) -> AiProvider = ProviderFactory::get,
    private val enqueueTaskWork: (Context, String) -> Unit = AgentTaskWork::enqueue,
    permissionGateway: PermissionGateway = PermissionGateway(),
    private val approvalLifecycleRepository: ApprovalLifecycleRepository = ApprovalLifecycleRepository(db)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val taskLifecycle = TaskLifecycleRepository(db)
    private val approvalLifecycle = approvalLifecycleRepository
    private val permissionGateway = permissionGateway
    private val tools = ToolRegistry(context, permissionGateway)

    fun registeredToolDefinitions(): List<ToolDefinition> = tools.definitions()
    fun pendingApprovals() = approvalLifecycle.observePending()
    suspend fun requestTerminalApproval(command: String, reason: String) = approvalLifecycle.createTerminalRequest(command, reason)
    fun securityAudit() = approvalLifecycle.observeAudit()
    suspend fun approvalDetails(requestId: String): String {
        val request = db.approvalRequestDao().get(requestId) ?: return "Approval details are unavailable."
        val call = db.toolExecutionDao().get(request.taskId, request.callId) ?: return "Stored operation is unavailable."
        return runCatching {
            val args = JSONObject(call.argumentsJson)
            when (call.toolName) {
                "write_file" -> "File: ${args.optString("path")}\n\nProposed file contents:\n${args.optString("content")}"
                "run_command" -> "Command:\n${args.optString("command")}"
                "mcp_server_launch" -> {
                    val serverId = args.optString("serverId")
                    val config = mcpManager.configurations.value.firstOrNull { it.id == serverId }
                        ?: return "MCP server configuration is unavailable; do not continue."
                    val command = com.cosmic.ai.security.SensitiveTextRedactor.redact(
                        listOfNotNull(config.command, config.args.joinToString(" ").ifBlank { null }).joinToString(" ")
                    ).replace(Regex("(?i)(--?(?:api[-_]?key|token|password|secret)(?:=|\\s+))[^\\s]+")) { match ->
                        match.groupValues[1] + "[REDACTED]"
                    }
                    "MCP STDIO server: ${config.name}\nExecutable and arguments: $command\nEnvironment values are stored encrypted and are not shown.\nConfiguration hash: ${args.optString("configHash").take(24)}"
                }
                else -> "Arguments:\n${args.toString(2)}"
            }
        }.getOrElse { "Stored operation arguments are malformed; do not approve this request." }
    }
    fun searchSecurityAudit(query: String) = approvalLifecycle.searchAudit(query)
    suspend fun verifySecurityAudit() = com.cosmic.ai.data.local.db.SecurityAuditRepository(db).verifyIntegrity()

    fun resolveApproval(requestId: String, allow: Boolean, forTask: Boolean = false) {
        scope.launch {
            val job = currentCoroutineContext()[Job]
            val taskId = approvalLifecycle.requestById(requestId)?.taskId
            if (taskId != null && job != null) approvalJobs[taskId] = job
            try {
                val decision = approvalLifecycle.decide(requestId, allow, when {
                    !allow -> "DENY"
                    forTask -> "ALLOW_FOR_TASK"
                    else -> "ALLOW_ONCE"
                })
                if (decision.status != "APPROVED") {
                    _events.emit(AgentUiEvent.ApprovalResolved(requestId, decision.status, decision.outcome ?: "Permission denied"))
                    return@launch
                }
                executeApprovedRequest(requestId)
            } catch (error: Exception) {
                _events.emit(AgentUiEvent.ApprovalResolved(requestId, "FAILED", error.message ?: error.javaClass.simpleName))
            } finally {
                if (taskId != null && job != null) approvalJobs.remove(taskId, job)
            }
        }
    }

    fun continueApprovedApproval(requestId: String) {
        scope.launch {
            val job = currentCoroutineContext()[Job]
            val taskId = approvalLifecycle.requestById(requestId)?.taskId
            if (taskId != null && job != null) approvalJobs[taskId] = job
            try { executeApprovedRequest(requestId) }
            catch (error: Exception) { _events.emit(AgentUiEvent.ApprovalResolved(requestId, "FAILED", error.message ?: error.javaClass.simpleName)) }
            finally { if (taskId != null && job != null) approvalJobs.remove(taskId, job) }
        }
    }

    private suspend fun executeApprovedRequest(requestId: String) {
        approvalLifecycle.expirePending()
        val requestBeforeClaim = approvalLifecycle.requestById(requestId) ?: error("Approval request is unavailable")
        val callBeforeClaim = db.toolExecutionDao().get(requestBeforeClaim.taskId, requestBeforeClaim.callId)
            ?: error("Approved operation is unavailable")
        val mcpLaunchConfig = if (callBeforeClaim.toolName == "mcp_server_launch") {
            val args = runCatching { JSONObject(callBeforeClaim.argumentsJson) }.getOrNull()
            val serverId = args?.optString("serverId").orEmpty()
            val config = mcpManager.configurations.value.firstOrNull { it.id == serverId && it.transport == com.cosmic.ai.domain.mcp.McpTransport.STDIO && it.enabled }
            if (config == null || args?.optString("configHash") != mcpManager.launchScope(config)) {
                _events.emit(AgentUiEvent.ApprovalResolved(requestId, "APPROVED", "MCP server configuration is missing, disabled, or changed. Review settings and request approval again; nothing was launched."))
                return
            }
            config
        } else null
        val mcpBeforeClaim = mcpManager.resolveAgentTool(callBeforeClaim.toolName)
        if (mcpManager.isAgentToolName(callBeforeClaim.toolName) && mcpBeforeClaim == null) {
            _events.emit(AgentUiEvent.ApprovalResolved(requestId, "APPROVED", "MCP server/tool is not connected. Reconnect it, then continue; the operation has not run."))
            return
        }
        if (mcpBeforeClaim != null && !mcpManager.isConnected(mcpBeforeClaim)) {
            _events.emit(AgentUiEvent.ApprovalResolved(requestId, "APPROVED", "MCP server is disconnected. Reconnect it, then continue; the operation has not run."))
            return
        }
        val (claimedRequest, call) = approvalLifecycle.claimApproved(requestId)
        val task = db.taskDao().getById(claimedRequest.taskId) ?: error("Approval task is unavailable")
        val project = task.projectUri?.let(Uri::parse)
        val result = try {
            withTimeout(120_000) {
                val mcpTool = mcpManager.resolveAgentTool(call.toolName)
                val authorization = permissionGateway.issueApprovalAuthorization(
                    requestId = claimedRequest.id,
                    taskId = claimedRequest.taskId,
                    callId = claimedRequest.callId,
                    action = when {
                        mcpTool != null -> "mcp:${mcpTool.serverId}:${mcpTool.name}"
                        mcpLaunchConfig != null -> claimedRequest.action
                        else -> claimedRequest.toolName
                    },
                    scopeHash = when {
                        mcpTool != null -> mcpManager.approvalScope(mcpTool, call.argumentsJson)
                        mcpLaunchConfig != null -> mcpManager.launchScope(mcpLaunchConfig)
                        else -> tools.approvalScope(call.toolName, call.argumentsJson)
                    },
                    expiresAt = claimedRequest.expiresAt
                )
                when {
                    mcpLaunchConfig != null -> mcpManager.connectApproved(mcpLaunchConfig, authorization,
                        claimedRequest.taskId, claimedRequest.callId, mcpManager.launchScope(mcpLaunchConfig))
                    mcpTool != null -> mcpManager.executeApproved(mcpTool, call.argumentsJson, authorization,
                        claimedRequest.taskId, claimedRequest.callId, mcpManager.approvalScope(mcpTool, call.argumentsJson)) {
                        _events.emit(AgentUiEvent.StateChanged(claimedRequest.taskId, AgentExecutionState.EXECUTING, "Executing ${mcpTool.name}"))
                    }
                    else -> tools.execute(call.toolName, call.argumentsJson, policy, project, authorization, claimedRequest.taskId, claimedRequest.callId) {
                        _events.emit(AgentUiEvent.StateChanged(claimedRequest.taskId, AgentExecutionState.EXECUTING, "Executing ${call.toolName}"))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                approvalLifecycle.finishApprovedUnknown(claimedRequest, "Execution cancelled or timed out; external outcome is unknown")
            }
            _events.emit(AgentUiEvent.ApprovalResolved(requestId, "UNKNOWN", "Execution stopped; side effects may have occurred"))
            return
        } catch (error: Exception) {
            ToolResult.Failure("${error.javaClass.simpleName}: ${error.message ?: "approved tool failed"}")
        }
        when (result) {
            is ToolResult.Success -> {
                val encoded = JSONObject().put("ok", true).put("output", result.output).toString()
                approvalLifecycle.finishApproved(claimedRequest, true, encoded, result.output, null)
                val completedTask = db.taskDao().getById(claimedRequest.taskId)
                if (completedTask?.mode in setOf("TERMINAL_APPROVAL", "MCP_LAUNCH_APPROVAL")) {
                    _events.emit(AgentUiEvent.ApprovalResolved(requestId, "COMPLETED", "Command finished", result.output))
                } else {
                    enqueueTaskWork(context, claimedRequest.taskId)
                }
            }
            is ToolResult.Failure -> {
                approvalLifecycle.finishApproved(claimedRequest, false, null, null, result.error)
                _events.emit(AgentUiEvent.ApprovalResolved(requestId, "FAILED",
                    com.cosmic.ai.security.SensitiveTextRedactor.redact(result.error).take(500)))
            }
            is ToolResult.Unknown -> {
                approvalLifecycle.finishApprovedUnknown(claimedRequest, result.reason)
                _events.emit(AgentUiEvent.ApprovalResolved(requestId, "UNKNOWN", result.reason))
            }
            is ToolResult.NeedsApproval -> {
                approvalLifecycle.finishApproved(claimedRequest, false, null, null, "Nested approval was requested; execution stopped")
                _events.emit(AgentUiEvent.ApprovalResolved(requestId, "FAILED", "Operation requested additional approval; stopped safely"))
            }
        }
    }

    fun cancelApproval(requestId: String) {
        scope.launch {
            try {
                if (approvalLifecycle.cancel(requestId)) {
                    _events.emit(AgentUiEvent.ApprovalResolved(requestId, "CANCELLED", "Approval cancelled; no new execution was started"))
                }
            } catch (error: Exception) {
                _events.emit(AgentUiEvent.ApprovalResolved(requestId, "FAILED", error.message ?: error.javaClass.simpleName))
            }
        }
    }
    fun selectedProjectUri(): Uri? = projectUri

    private val _events = MutableSharedFlow<AgentUiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentUiEvent> = _events.asSharedFlow()

    private var currentJob: Job? = null
    @Volatile private var currentTaskId: String? = null
    private val recoveryClaims = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val approvalJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var projectUri: Uri? = null
    @Volatile private var policy = PermissionPolicy()

    fun setProject(uri: Uri?) {
        projectUri = uri
    }

    fun updatePolicy(p: PermissionPolicy) {
        policy = p
        permissionGateway.update(com.cosmic.ai.security.SecurityPolicy(
            allowRead = p.allowFileRead,
            allowWrite = p.allowFileWrite,
            allowExecute = p.allowTerminal,
            allowAdmin = false,
            allowNetwork = p.allowNetwork,
            requireApprovalRead = false,
            requireApprovalWrite = p.requireApprovalForDestructive,
            requireApprovalExecute = false,
            requireApprovalAdmin = true,
            requireApprovalNetwork = false
        ))
    }

    fun cancel() {
        currentTaskId?.let(::cancelTask)
    }

    fun cancelTask(taskId: String) {
        if (currentTaskId == taskId) {
            currentJob?.cancel(CancellationException("Cancelled by user"))
            scope.launch {
                approvalLifecycle.cancelForTask(taskId, "Task cancelled by user")
                taskLifecycle.requestCancel(taskId)
            }
        } else {
            approvalJobs[taskId]?.cancel(CancellationException("Cancelled by user"))
            scope.launch {
                approvalLifecycle.cancelForTask(taskId, "Task cancelled by user")
                taskLifecycle.markTaskToolsUnknown(taskId, "Task cancelled while tool outcome was uncertain")
                taskLifecycle.requestCancel(taskId)
                val task = db.taskDao().getById(taskId) ?: return@launch
                val status = TaskStatus.valueOf(task.status)
                if (!TaskTransitions.isTerminal(status) && TaskTransitions.canTransition(status, TaskStatus.CANCELLED)) {
                    taskLifecycle.transition(taskId, TaskStatus.CANCELLED, "Cancelled by user")
                }
            }
        }
    }

    suspend fun runOrRecoverPersistedTask(taskId: String, onProgress: suspend (String) -> Unit): TaskWorkerOutcome {
        var lastProgress: String? = null
        var busySince: Long? = null
        while (currentCoroutineContext().isActive) {
            val task = db.taskDao().getById(taskId) ?: return TaskWorkerOutcome.BLOCKED
            val status = runCatching { TaskStatus.valueOf(task.status) }.getOrNull() ?: return TaskWorkerOutcome.BLOCKED
            val progress = task.progressMessage ?: status.name
            if (progress != lastProgress) { onProgress(progress); lastProgress = progress }
            if (TaskTransitions.isTerminal(status) || status == TaskStatus.AWAITING_APPROVAL) return TaskWorkerOutcome.FINISHED
            if (currentTaskId == taskId && currentJob?.isActive == true) {
                busySince = null
            } else if (taskId in recoveryClaims) {
                busySince = null
            } else if (currentJob?.isActive == true) {
                val since = busySince ?: System.currentTimeMillis().also { busySince = it }
                if (System.currentTimeMillis() - since > 60_000) return TaskWorkerOutcome.BUSY
            } else {
                busySince = null
                if (status in setOf(TaskStatus.STARTING, TaskStatus.RUNNING, TaskStatus.WAITING_FOR_MODEL,
                        TaskStatus.WAITING_FOR_TOOL, TaskStatus.COMPLETING)) {
                    if (System.currentTimeMillis() - task.updatedAt < 30_000) {
                        delay(500)
                        continue
                    }
                    taskLifecycle.markInterruptedTaskForRecovery(taskId)
                }
                val refreshed = db.taskDao().getById(taskId) ?: return TaskWorkerOutcome.BLOCKED
                if (refreshed.status in setOf(TaskStatus.QUEUED.name, TaskStatus.RECOVERY_REQUIRED.name, TaskStatus.RETRYABLE.name)) {
                    resume(taskId)
                } else if (refreshed.status == TaskStatus.AWAITING_APPROVAL.name) {
                    return TaskWorkerOutcome.FINISHED
                } else {
                    return TaskWorkerOutcome.BLOCKED
                }
            }
            delay(500)
        }
        throw CancellationException("WorkManager stopped monitoring task")
    }

    fun run(
        sessionId: String,
        userMessage: String,
        config: ProviderConfig,
        model: String,
        mode: AgentMode = AgentMode.AGENT,
        maxSteps: Int = 12
    ) { start(UUID.randomUUID().toString(), sessionId, userMessage, config, model, mode, maxSteps, persistUserMessage = true) }

    /** Resume persisted context after WorkManager has claimed this task. */
    fun resume(taskId: String) {
        if (!recoveryClaims.add(taskId)) return
        scope.launch {
            var started = false
            try {
                val task = taskLifecycle.claimForExecution(taskId) ?: return@launch
                val saved = JSONObject(task.providerConfigJson ?: error("Task has no provider recovery configuration"))
                val config = ProviderConfig(
                    id = saved.getString("id"), name = saved.getString("name"),
                    type = ProviderType.valueOf(saved.getString("type")),
                    baseUrl = saved.getString("baseUrl"), apiKeyAlias = saved.getString("apiKeyAlias"),
                    defaultModel = saved.getString("defaultModel")
                )
                val sessionMessages = db.messageDao().getForSession(task.sessionId)
                val userInstruction = task.requestMessageId?.let { id -> sessionMessages.firstOrNull { it.id == id } }
                    ?: sessionMessages.lastOrNull { it.role == "user" }
                    ?: error("No persisted user instruction is available")
                projectUri = task.projectUri?.let(Uri::parse)
                    ?: db.sessionDao().getById(task.sessionId)?.projectUri?.let(Uri::parse)
                started = start(task.id, task.sessionId, userInstruction.content, config,
                    task.modelName ?: config.defaultModel,
                    task.mode?.let { AgentMode.valueOf(it) } ?: AgentMode.AGENT,
                    task.maxSteps.coerceIn(1, 50), persistUserMessage = false)
                if (!started) taskLifecycle.transition(taskId, TaskStatus.QUEUED, "Waiting for another active task")
            } catch (e: Exception) {
                val message = "Recovery failed: ${e.message ?: e.javaClass.simpleName}"
                runCatching {
                    val task = db.taskDao().getById(taskId)
                    val status = task?.status?.let { runCatching { TaskStatus.valueOf(it) }.getOrNull() }
                    if (status != null && TaskTransitions.canTransition(status, TaskStatus.FAILED)) {
                        taskLifecycle.transition(taskId, TaskStatus.FAILED, message)
                    }
                }
                _events.emit(AgentUiEvent.Failed(message, taskId))
            } finally {
                recoveryClaims.remove(taskId)
            }
        }
    }

    suspend fun recoverInterruptedTasks(): List<String> {
        val pending = taskLifecycle.recoverable()
        pending.forEach { enqueueTaskWork(context, it.id) }
        return pending.map { it.id }
    }

    @Synchronized
    private fun start(
        taskId: String,
        sessionId: String,
        userMessage: String,
        config: ProviderConfig,
        model: String,
        mode: AgentMode,
        maxSteps: Int,
        persistUserMessage: Boolean
    ): Boolean {
        if (currentJob?.isActive == true) {
            _events.tryEmit(AgentUiEvent.ExecutionRejected("An execution is already active. Stop it before starting another."))
            return false
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            _events.emit(AgentUiEvent.ExecutionStarted(taskId, config.name, model))
            _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.THINKING, "Understanding the request"))
            val taskCreatedAt = db.taskDao().getById(taskId)?.createdAt ?: System.currentTimeMillis()
            val steps = mutableListOf<AgentStep>()
            val userMessageId = if (persistUserMessage) UUID.randomUUID().toString() else null
            var httpStatus: Int? = null
            var promptTokens = 0
            var completionTokens = 0
            var streaming = false
            suspend fun persistTask(status: TaskStatus, progress: String) {
                val now = System.currentTimeMillis()
                val diagnostic = JSONObject()
                    .put("kind", "execution")
                    .put("executionId", taskId)
                    .put("provider", config.name)
                    .put("model", model)
                    .put("startedAt", taskCreatedAt)
                    .put("finishedAt", if (status in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED)) now else JSONObject.NULL)
                    .put("stage", progress.take(300))
                    .put("streaming", streaming)
                    .put("promptTokens", promptTokens)
                    .put("completionTokens", completionTokens)
                    .put("httpStatus", httpStatus ?: JSONObject.NULL)
                val recordedSteps = JSONArray().put(diagnostic)
                steps.forEach {
                    recordedSteps.put(JSONObject()
                        .put("kind", "step")
                        .put("index", it.index)
                        .put("type", it.type.name)
                        .put("content", it.content.take(2000))
                        .put("tool", it.toolName))
                }
                val existing = db.taskDao().getById(taskId)
                if (existing == null) {
                    taskLifecycle.create(TaskEntity(
                        id = taskId,
                        sessionId = sessionId,
                        status = TaskStatus.QUEUED.name,
                        stepsJson = recordedSteps.toString(),
                        progressMessage = "Queued",
                        createdAt = taskCreatedAt,
                        updatedAt = now,
                        providerConfigJson = JSONObject()
                            .put("id", config.id).put("name", config.name).put("type", config.type.name)
                            .put("baseUrl", config.baseUrl).put("apiKeyAlias", config.apiKeyAlias)
                            .put("defaultModel", config.defaultModel).toString(),
                        modelName = model,
                        mode = mode.name,
                        maxSteps = maxSteps,
                        projectUri = projectUri?.toString(),
                        requestMessageId = userMessageId
                    ), initialMessage = if (persistUserMessage) MessageEntity(
                        userMessageId!!, sessionId, "user", userMessage, createdAt = now
                    ) else null)
                    enqueueTaskWork(context, taskId)
                }
                val current = db.taskDao().getById(taskId) ?: error("Task persistence failed")
                if (current.status != status.name) taskLifecycle.transition(taskId, status, progress)
                val latest = db.taskDao().getById(taskId) ?: error("Task disappeared during persistence")
                db.taskDao().upsert(latest.copy(
                    stepsJson = recordedSteps.toString(),
                    progressMessage = progress.take(300),
                    updatedAt = now
                ))
            }
            fun emitStep(type: StepType, content: String, tool: String? = null, result: String? = null) {
                val step = AgentStep(steps.size, type, content, tool, result)
                steps.add(step)
                _events.tryEmit(AgentUiEvent.Step(step))
            }

            var finishedNormally = false
            try {
                persistTask(TaskStatus.STARTING, "Starting")
                // persist user message
                if (persistUserMessage) {
                    _events.emit(AgentUiEvent.MessageAdded(ChatMessage("user", userMessage)))
                }

                // Persisted assistant tool-call metadata is part of the conversation protocol.
                // Restore it when resuming after an approval/process restart so tool results
                // still follow the matching assistant tool_calls message.
                val history = db.messageDao().getForSession(sessionId).map(MessageEntity::toProviderMessage).toMutableList()

                val system = ChatMessage(
                    "system",
                    buildSystemPrompt(mode, projectUri != null)
                )
                val messages = mutableListOf(system).apply { addAll(history) }

                var stepCount = 0
                while (stepCount < maxSteps) {
                    ensureActive()
                    stepCount++
                    val apiKey = apiKeyReader(config.apiKeyAlias)
                        ?: throw IllegalStateException("No API key is configured for ${config.name}. Add or select a provider in Settings.")

                    val request = ChatRequest(
                        model = model,
                        messages = messages,
                        tools = if (mode == AgentMode.CHAT) null else {
                        val local = tools.definitions()
                        val mcp = mcpManager.allTools().map {
                            com.cosmic.ai.domain.provider.ToolDefinition(mcpManager.agentToolName(it), it.description, it.inputSchemaJson)
                        }
                        local + mcp
                    },
                        temperature = 0.2,
                        stream = true
                    )

                    val assistantText = StringBuilder()
                    val toolCallAssembler = ToolCallAssembler()
                    var finishReason: String? = null
                    var streamFinished = false

                    _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.THINKING, "Waiting for ${config.name} · $model"))
                    persistTask(TaskStatus.WAITING_FOR_MODEL, "Waiting for ${config.name} · $model")
                    providerResolver(config.type).streamChat(config, apiKey, request).collect { event ->
                        ensureActive()
                        when (event) {
                            is ChatEvent.Delta -> {
                                if (!streaming) {
                                    streaming = true
                                    persistTask(TaskStatus.RUNNING, "Receiving model response")
                                }
                                assistantText.append(event.text)
                                _events.emit(AgentUiEvent.StreamDelta(event.text))
                            }
                            is ChatEvent.ToolCallDelta -> toolCallAssembler.append(
                                event.index, event.id, event.nameFragment, event.argumentsFragment
                            )
                            is ChatEvent.Finished -> {
                                if (finishReason == null || event.finishReason != "stop") finishReason = event.finishReason
                                streamFinished = true
                            }
                            is ChatEvent.Error -> {
                                httpStatus = event.code
                                emitStep(StepType.ERROR, "Provider error${event.code?.let { " (HTTP $it)" } ?: ""}: ${event.message}")
                                throw IOException("Provider request failed${event.code?.let { " (HTTP $it)" } ?: ""}: ${event.message}")
                            }
                            is ChatEvent.Usage -> {
                                promptTokens = event.promptTokens
                                completionTokens = event.completionTokens
                            }
                        }
                    }

                    if (!streamFinished) {
                        throw IOException("Provider stream ended before a completion signal was received")
                    }
                    val pendingTools = toolCallAssembler.finish(request.tools.orEmpty())
                    streaming = false
                    val content = assistantText.toString()
                    if (content.isBlank() && pendingTools.isEmpty()) {
                        throw IOException("Provider returned an empty response. Check the selected model and try again.")
                    }
                    if (content.isNotBlank() || pendingTools.isNotEmpty()) {
                        emitStep(StepType.REASONING, content.ifBlank { "Tool call requested" })
                        messages.add(ChatMessage("assistant", content, toolCalls = pendingTools.ifEmpty { null }))
                        db.messageDao().insert(
                            MessageEntity(
                                UUID.randomUUID().toString(),
                                sessionId,
                                "assistant",
                                content,
                                toolCallsJson = if (pendingTools.isEmpty()) null else JSONArray(pendingTools.map {
                                    JSONObject().put("id", it.id).put("name", it.name).put("arguments", it.arguments)
                                }).toString(),
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        _events.emit(AgentUiEvent.MessageAdded(
                            ChatMessage("assistant", content, toolCalls = pendingTools.ifEmpty { null })
                        ))
                    }

                    if (pendingTools.isEmpty()) {
                        if (finishReason == "length") throw IOException("The model response was truncated at its output limit. Increase the token limit or retry.")
                        emitStep(StepType.SUMMARY, "Completed in $stepCount step(s)")
                        finishedNormally = true
                        break
                    }
                    if (finishReason != "tool_calls") {
                        throw IOException("Provider returned tool calls without a tool-call completion reason")
                    }

                    // execute tools
                    for (tc in pendingTools) {
                        ensureActive()
                        persistTask(TaskStatus.WAITING_FOR_TOOL, "Preparing ${tc.name}")
                        _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.TOOL_REQUESTED, "${tc.name} requested"))
                        emitStep(StepType.TOOL_CALL, tc.arguments, tc.name)
                        val mcpTool = mcpManager.resolveAgentTool(tc.name)
                        val argsHash = if (mcpTool != null) mcpManager.approvalScope(mcpTool, tc.arguments) else MessageDigest.getInstance("SHA-256")
                            .digest(tc.arguments.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        val durableCall = taskLifecycle.prepareToolCall(
                            taskId, tc.id, tc.name, tc.arguments, argsHash, idempotent = false
                        )
                        val result = when (durableCall.state) {
                            "SUCCEEDED" -> {
                                val saved = runCatching { JSONObject(durableCall.resultJson ?: "") }.getOrNull()
                                val output = saved?.nonNullString("output")
                                if (saved?.optBoolean("ok") == true && output != null) ToolResult.Success(output)
                                else ToolResult.Failure("Previously successful tool result is incomplete; refusing to report success")
                            }
                            "FAILED" -> ToolResult.Failure(durableCall.error ?: "Tool previously failed")
                            "UNKNOWN" -> ToolResult.Failure(durableCall.error ?: "Tool outcome is unknown; automatic replay prevented")
                            "NEEDS_APPROVAL" -> ToolResult.NeedsApproval(
                                durableCall.error ?: "User approval required", tc.name, tc.arguments
                            )
                            else -> {
                                val claim = taskLifecycle.startToolCall(taskId, tc.id)
                                if (claim.state != "RUNNING") {
                                    ToolResult.Failure(claim.error ?: "Tool execution could not be safely claimed")
                                } else {
                                    val taskGrant = approvalLifecycle.taskGrant(taskId, tc.name, durableCall.argumentsHash)
                                    val authorization = taskGrant?.let {
                                        permissionGateway.issueApprovalAuthorization(it.id, taskId, tc.id, it.toolName,
                                            tools.approvalScope(tc.name, tc.arguments), it.expiresAt)
                                    }
                                    withTimeout(120_000) {
                                        if (mcpTool != null) mcpManager.executeApproved(mcpTool, tc.arguments, authorization,
                                            taskId, tc.id, argsHash) {
                                            _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.EXECUTING, "Executing ${mcpTool.name}"))
                                        }
                                        else tools.execute(tc.name, tc.arguments, policy, projectUri, authorization,
                                            if (authorization == null) null else taskId, if (authorization == null) null else tc.id) {
                                            _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.EXECUTING, "Executing ${tc.name}"))
                                        }
                                    }
                                }
                            }
                        }

                        when (result) {
                            is ToolResult.NeedsApproval -> {
                                val parsed = runCatching { JSONObject(tc.arguments) }.getOrDefault(JSONObject())
                                val resource = when {
                                    mcpTool != null -> "MCP ${mcpTool.serverId} · ${mcpTool.name} · args sha256:${argsHash.take(16)}"
                                    tc.name in setOf("write_file", "edit_file", "create_directory", "delete_file") -> parsed.nonNullString("path") ?: "selected project resource"
                                    tc.name == "run_command" -> "command sha256:${argsHash.take(16)}"
                                    else -> tc.name
                                }
                                val request = withContext(NonCancellable) {
                                    approvalLifecycle.request(taskId, tc.id, "orchestrator", tc.name,
                                        when {
                                            mcpTool != null -> "Call MCP tool ${mcpTool.name}"
                                            tc.name == "run_command" -> "Execute the requested command"
                                            tc.name == "write_file" -> "Create or update a project file"
                                            tc.name == "edit_file" -> "Edit an existing project file"
                                            tc.name == "create_directory" -> "Create a project directory"
                                            tc.name == "delete_file" -> "Delete a project file"
                                            else -> "Use the requested protected tool"
                                        },
                                        resource,
                                        if (mcpTool != null || tc.name in setOf("run_command", "delete_file")) "HIGH" else "MEDIUM",
                                        result.reason, argsHash)
                                }
                                _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.WAITING_FOR_APPROVAL, "Waiting for approval to use ${result.toolName}"))
                                _events.emit(AgentUiEvent.NeedsApproval(request.id, taskId, result.toolName, result.args, result.reason, request.resourceScope, request.riskLevel))
                                return@launch
                            }
                            is ToolResult.Success -> {
                                _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.TOOL_COMPLETED, "${tc.name} completed"))
                                if (durableCall.state != "SUCCEEDED") {
                                    val resultJson = JSONObject().put("ok", true).put("output", result.output).toString()
                                    if (!taskLifecycle.completeToolCall(taskId, tc.id, resultJson)) {
                                        throw IOException("Tool result could not be durably recorded")
                                    }
                                }
                                emitStep(StepType.TOOL_RESULT, result.output, tc.name, result.output)
                                val alreadyPersisted = messages.any { it.role == "tool" && it.toolCallId == tc.id }
                                if (!alreadyPersisted) {
                                    val toolMsg = ChatMessage("tool", result.output, toolCallId = tc.id, name = tc.name)
                                    messages.add(toolMsg)
                                    db.messageDao().insert(
                                        MessageEntity(
                                            UUID.randomUUID().toString(),
                                            sessionId,
                                            "tool",
                                            result.output,
                                            toolCallId = tc.id,
                                            createdAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                            is ToolResult.Unknown -> {
                                _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.TOOL_FAILED, "${tc.name} outcome is unknown"))
                                if (!taskLifecycle.markToolUnknown(taskId, tc.id, result.reason)) {
                                    throw IOException("Unknown tool outcome could not be durably recorded")
                                }
                                emitStep(StepType.ERROR, "${tc.name}: ${result.reason}", tc.name)
                                throw IOException("${tc.name} outcome is unknown. Automatic retry was blocked; inspect the project before retrying.")
                            }
                            is ToolResult.Failure -> {
                                _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.TOOL_FAILED, "${tc.name} failed"))
                                val safeError = com.cosmic.ai.security.SensitiveTextRedactor.redact(result.error)
                                    .take(500).ifBlank { "Tool execution failed" }
                                if (durableCall.state !in setOf("FAILED", "UNKNOWN", "NEEDS_APPROVAL")) {
                                    if (!taskLifecycle.failToolCall(taskId, tc.id, safeError)) {
                                        throw IOException("Tool failure could not be durably recorded")
                                    }
                                }
                                emitStep(StepType.TOOL_RESULT, safeError, tc.name, safeError)
                                if (messages.none { it.role == "tool" && it.toolCallId == tc.id }) {
                                    val toolMsg = ChatMessage("tool", "Error: $safeError", toolCallId = tc.id, name = tc.name)
                                    messages.add(toolMsg)
                                    db.messageDao().insert(
                                        MessageEntity(UUID.randomUUID().toString(), sessionId, "tool", toolMsg.content,
                                            toolCallId = tc.id, createdAt = System.currentTimeMillis())
                                    )
                                }
                                // Do not ask the model to continue after an operation failed.
                                // The failure is durably returned to chat and the task is marked
                                // FAILED by the outer lifecycle handler; a later explicit retry
                                // starts with this actual result in context.
                                throw AgentToolExecutionException(tc.name, safeError)
                            }
                        }
                    }
                }

                if (finishedNormally) {
                    // The final response has already been inserted into Room before terminal state.
                    persistTask(TaskStatus.COMPLETING, "Saving final response")
                    persistTask(TaskStatus.COMPLETED, "done")
                    _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.COMPLETED, "Task completed"))
                    _events.emit(AgentUiEvent.Completed(taskId))
                } else {
                    throw IOException("Execution reached an invalid terminal state")
                }
            } catch (e: TimeoutCancellationException) {
                val message = "Tool execution timed out"
                val persistenceError = withContext(NonCancellable) {
                    try {
                        taskLifecycle.markTaskToolsUnknown(taskId, message)
                        persistTask(TaskStatus.FAILED, message)
                        null
                    }
                    catch (persist: Exception) { persist.message ?: persist.javaClass.simpleName }
                }
                _events.emit(AgentUiEvent.Failed(if (persistenceError == null) message else "$message (task state save failed: $persistenceError)", taskId))
            } catch (e: CancellationException) {
                val persistenceError = withContext(NonCancellable) {
                    try {
                        taskLifecycle.markTaskToolsUnknown(taskId, "Cancelled while tool outcome was uncertain")
                        persistTask(TaskStatus.CANCELLED, "Cancelled")
                        null
                    } catch (persist: Exception) {
                        persist.message ?: persist.javaClass.simpleName
                    }
                }
                if (persistenceError == null) {
                    _events.tryEmit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.CANCELLED, "Task cancelled"))
                    _events.tryEmit(AgentUiEvent.Cancelled(taskId))
                }
                else _events.tryEmit(AgentUiEvent.Failed("Execution was cancelled, but its state could not be saved: $persistenceError", taskId))
            } catch (e: Exception) {
                val category = when (e) {
                    is ToolCallFormatException -> "Invalid tool call"
                    is AgentToolExecutionException -> "Tool execution failed (${e.toolName})"
                    is java.net.SocketTimeoutException -> "Network timeout"
                    is IOException -> "Provider or network error"
                    is IllegalStateException -> "Configuration error"
                    else -> "Execution error"
                }
                val message = if (e is AgentToolExecutionException) "$category: ${e.message ?: "operation failed"}"
                    else "$category: ${e.message ?: e.javaClass.simpleName}"
                emitStep(StepType.ERROR, message)
                if (e is AgentToolExecutionException) {
                    _events.emit(AgentUiEvent.StateChanged(taskId, AgentExecutionState.TOOL_FAILED, message))
                }
                val persistenceError = withContext(NonCancellable) {
                    try {
                        persistTask(TaskStatus.FAILED, message)
                        null
                    } catch (persist: Exception) {
                        persist.message ?: persist.javaClass.simpleName
                    }
                }
                val reported = if (persistenceError == null) message else "$message (task state save failed: $persistenceError)"
                _events.emit(AgentUiEvent.Failed(reported, taskId))
            } finally {
                synchronized(this@AgentRuntime) {
                    if (currentJob === coroutineContext[Job]) {
                        currentJob = null
                        currentTaskId = null
                    }
                }
            }
        }
        currentTaskId = taskId
        currentJob = job
        job.start()
        return true
    }

    private fun buildSystemPrompt(mode: AgentMode, hasProject: Boolean): String {
        val base = """
            You are COSMIC CODEX, a precise autonomous engineering assistant running on Android.
            DuckDuckGo Instant Answers can provide topic summaries and related-topic citations, but it is not a full organic web search API. Use duckduckgo_instant_answer only when useful, cite returned URLs, and clearly state when it provides no result. Never invent search results.
            Prefer tools over speculation. Never invent file contents or command results.
            Keep responses concise and technical. When editing, prefer small targeted changes.
        """.trimIndent()
        val project = if (hasProject) "A project folder is open. Use list_files / read_file / write_file / edit_file / create_directory as needed. delete_file always needs explicit approval." else "No project folder is selected. Request/select a permitted project folder before using project file tools; do not claim files were created."
        val modeLine = when (mode) {
            AgentMode.CHAT -> "Mode: chat only. Do not call tools."
            AgentMode.PLAN -> "Mode: plan. Propose steps, do not execute write/terminal tools."
            AgentMode.AGENT -> "Mode: agent. Use tools when needed. Stop when the user request is fully handled."
        }
        return "$base\n$project\n$modeLine"
    }
}

sealed class AgentUiEvent {
    data class MessageAdded(val message: ChatMessage) : AgentUiEvent()
    data class StreamDelta(val text: String) : AgentUiEvent()
    data class Step(val step: AgentStep) : AgentUiEvent()
    data class NeedsApproval(val requestId: String, val taskId: String, val toolName: String, val args: String, val reason: String, val resource: String, val risk: String) : AgentUiEvent()
    data class ApprovalResolved(val requestId: String, val status: String, val message: String, val result: String? = null) : AgentUiEvent()
    data class ExecutionStarted(val taskId: String, val provider: String, val model: String) : AgentUiEvent()
    data class StateChanged(val taskId: String, val state: AgentExecutionState, val detail: String) : AgentUiEvent()
    data class ExecutionRejected(val message: String) : AgentUiEvent()
    data class Completed(val taskId: String) : AgentUiEvent()
    data class Failed(val message: String, val taskId: String? = null) : AgentUiEvent()
    data class Cancelled(val taskId: String? = null) : AgentUiEvent()
}
