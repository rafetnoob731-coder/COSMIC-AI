// language: kotlin, file: AgentRuntime.kt, runtime: coroutines + tool loop, target: multi-step agent
package com.cosmic.ai.runtime.agent

import android.content.Context
import android.net.Uri
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.data.local.db.TaskLifecycleRepository
import com.cosmic.ai.core.state.TaskTransitions
import com.cosmic.ai.runtime.background.AgentTaskWork
import com.cosmic.ai.runtime.background.TaskWorkerOutcome
import com.cosmic.ai.domain.agent.*
import com.cosmic.ai.domain.provider.*
import com.cosmic.ai.runtime.providers.ProviderFactory
import com.cosmic.ai.runtime.tools.ToolRegistry
import com.cosmic.ai.runtime.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.IOException
import java.security.MessageDigest

class AgentRuntime(
    private val context: Context,
    private val db: CosmicDatabase,
    private val apiKeyReader: (String) -> String?,
    private val mcpManager: com.cosmic.ai.runtime.mcp.McpClientManager,
    private val providerResolver: (ProviderType) -> AiProvider = ProviderFactory::get,
    private val enqueueTaskWork: (Context, String) -> Unit = AgentTaskWork::enqueue
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val taskLifecycle = TaskLifecycleRepository(db)
    private val tools = ToolRegistry(context)

    private val _events = MutableSharedFlow<AgentUiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentUiEvent> = _events.asSharedFlow()

    private var currentJob: Job? = null
    @Volatile private var currentTaskId: String? = null
    private val recoveryClaims = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var projectUri: Uri? = null
    private var policy = PermissionPolicy()

    fun setProject(uri: Uri?) {
        projectUri = uri
    }

    fun updatePolicy(p: PermissionPolicy) {
        policy = p
    }

    fun cancel() {
        currentTaskId?.let(::cancelTask)
    }

    fun cancelTask(taskId: String) {
        if (currentTaskId == taskId) {
            currentJob?.cancel(CancellationException("Cancelled by user"))
            scope.launch { taskLifecycle.requestCancel(taskId) }
        } else {
            scope.launch {
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

                val history = db.messageDao().getForSession(sessionId).map {
                    ChatMessage(it.role, it.content, it.toolCallId, null)
                }.toMutableList()

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
                            com.cosmic.ai.domain.provider.ToolDefinition(it.name, it.description, it.inputSchemaJson)
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
                        _events.emit(AgentUiEvent.MessageAdded(ChatMessage("assistant", content)))
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
                        persistTask(TaskStatus.WAITING_FOR_TOOL, "Executing ${tc.name}")
                        emitStep(StepType.TOOL_CALL, tc.arguments, tc.name)
                        val tool = tools.get(tc.name)
                        val argsHash = MessageDigest.getInstance("SHA-256")
                            .digest(tc.arguments.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                        val durableCall = taskLifecycle.prepareToolCall(
                            taskId, tc.id, tc.name, tc.arguments, argsHash, idempotent = false
                        )
                        val result = when (durableCall.state) {
                            "SUCCEEDED" -> ToolResult.Success(JSONObject(durableCall.resultJson ?: "{}").optString("output"))
                            "FAILED" -> ToolResult.Failure(durableCall.error ?: "Tool previously failed")
                            "UNKNOWN" -> ToolResult.Failure(durableCall.error ?: "Tool outcome is unknown; automatic replay prevented")
                            "NEEDS_APPROVAL" -> ToolResult.NeedsApproval(
                                durableCall.error ?: "User approval required", tc.name, tc.arguments
                            )
                            else -> {
                                val claim = taskLifecycle.startToolCall(taskId, tc.id)
                                if (claim.state != "RUNNING") {
                                    ToolResult.Failure(claim.error ?: "Tool execution could not be safely claimed")
                                } else if (tool == null) {
                                    ToolResult.Failure("Unknown tool: ${tc.name}")
                                } else {
                                    withTimeout(120_000) { tool.execute(tc.arguments, policy, projectUri) }
                                }
                            }
                        }

                        when (result) {
                            is ToolResult.NeedsApproval -> {
                                if (!taskLifecycle.markApproval(taskId, tc.id)) {
                                    throw IOException("Approval state could not be durably recorded")
                                }
                                emitStep(StepType.ERROR, "Awaiting user approval for ${result.toolName}")
                                withContext(NonCancellable) {
                                    persistTask(TaskStatus.AWAITING_APPROVAL, "Approval required for ${result.toolName}")
                                }
                                _events.emit(AgentUiEvent.NeedsApproval(result.toolName, result.args, result.reason))
                                return@launch
                            }
                            is ToolResult.Success -> {
                                if (durableCall.state != "SUCCEEDED") {
                                    val resultJson = JSONObject().put("ok", true).put("output", result.output).toString()
                                    if (!taskLifecycle.completeToolCall(taskId, tc.id, resultJson)) {
                                        throw IOException("Tool result could not be durably recorded")
                                    }
                                }
                                emitStep(StepType.TOOL_RESULT, result.output, tc.name, result.output)
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
                            is ToolResult.Failure -> {
                                if (durableCall.state !in setOf("FAILED", "UNKNOWN", "NEEDS_APPROVAL")) {
                                    if (!taskLifecycle.failToolCall(taskId, tc.id, result.error)) {
                                        throw IOException("Tool failure could not be durably recorded")
                                    }
                                }
                                emitStep(StepType.TOOL_RESULT, result.error, tc.name, result.error)
                                val toolMsg = ChatMessage("tool", "Error: ${result.error}", toolCallId = tc.id, name = tc.name)
                                messages.add(toolMsg)
                                db.messageDao().insert(
                                    MessageEntity(UUID.randomUUID().toString(), sessionId, "tool", toolMsg.content,
                                        toolCallId = tc.id, createdAt = System.currentTimeMillis())
                                )
                            }
                        }
                    }
                }

                if (finishedNormally) {
                    // The final response has already been inserted into Room before terminal state.
                    persistTask(TaskStatus.COMPLETING, "Saving final response")
                    persistTask(TaskStatus.COMPLETED, "done")
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
                if (persistenceError == null) _events.tryEmit(AgentUiEvent.Cancelled(taskId))
                else _events.tryEmit(AgentUiEvent.Failed("Execution was cancelled, but its state could not be saved: $persistenceError", taskId))
            } catch (e: Exception) {
                val category = when (e) {
                    is ToolCallFormatException -> "Invalid tool call"
                    is java.net.SocketTimeoutException -> "Network timeout"
                    is IOException -> "Provider or network error"
                    is IllegalStateException -> "Configuration error"
                    else -> "Execution error"
                }
                val message = "$category: ${e.message ?: e.javaClass.simpleName}"
                emitStep(StepType.ERROR, message)
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
        val project = if (hasProject) "A project folder is open. Use list_files / read_file / write_file." else "No project open. File tools will fail."
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
    data class NeedsApproval(val toolName: String, val args: String, val reason: String) : AgentUiEvent()
    data class ExecutionStarted(val taskId: String, val provider: String, val model: String) : AgentUiEvent()
    data class ExecutionRejected(val message: String) : AgentUiEvent()
    data class Completed(val taskId: String) : AgentUiEvent()
    data class Failed(val message: String, val taskId: String? = null) : AgentUiEvent()
    data class Cancelled(val taskId: String? = null) : AgentUiEvent()
}
