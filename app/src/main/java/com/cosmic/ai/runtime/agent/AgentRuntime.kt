// language: kotlin, file: AgentRuntime.kt, runtime: coroutines + tool loop, target: multi-step agent
package com.cosmic.ai.runtime.agent

import android.content.Context
import android.net.Uri
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.data.local.keystore.SecureStore
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

class AgentRuntime(
    private val context: Context,
    private val db: CosmicDatabase,
    private val secureStore: SecureStore,
    private val mcpManager: com.cosmic.ai.runtime.mcp.McpClientManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tools = ToolRegistry(context)

    private val _events = MutableSharedFlow<AgentUiEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentUiEvent> = _events.asSharedFlow()

    private var currentJob: Job? = null
    private var projectUri: Uri? = null
    private var policy = PermissionPolicy()

    fun setProject(uri: Uri?) {
        projectUri = uri
    }

    fun updatePolicy(p: PermissionPolicy) {
        policy = p
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        scope.launch { _events.emit(AgentUiEvent.Cancelled) }
    }

    fun run(
        sessionId: String,
        userMessage: String,
        config: ProviderConfig,
        model: String,
        mode: AgentMode = AgentMode.AGENT,
        maxSteps: Int = 12
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            val taskId = UUID.randomUUID().toString()
            val steps = mutableListOf<AgentStep>()
            fun emitStep(type: StepType, content: String, tool: String? = null, result: String? = null) {
                val step = AgentStep(steps.size, type, content, tool, result)
                steps.add(step)
                _events.tryEmit(AgentUiEvent.Step(step))
            }

            try {
                // persist user message
                val userMsgId = UUID.randomUUID().toString()
                db.messageDao().insert(
                    MessageEntity(userMsgId, sessionId, "user", userMessage, createdAt = System.currentTimeMillis())
                )
                _events.emit(AgentUiEvent.MessageAdded(ChatMessage("user", userMessage)))

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
                    val apiKey = secureStore.getSecret(config.apiKeyAlias)
                        ?: run {
                            emitStep(StepType.ERROR, "API key missing for provider ${config.name}")
                            break
                        }

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

                    var assistantText = StringBuilder()
                    val pendingTools = mutableListOf<ToolCall>()
                    var finishReason = "stop"

                    ProviderFactory.get(config.type).streamChat(config, apiKey, request).collect { event ->
                        ensureActive()
                        when (event) {
                            is ChatEvent.Delta -> {
                                assistantText.append(event.text)
                                _events.emit(AgentUiEvent.StreamDelta(event.text))
                            }
                            is ChatEvent.ToolCallDelta -> pendingTools.add(event.toolCall)
                            is ChatEvent.Finished -> finishReason = event.finishReason
                            is ChatEvent.Error -> {
                                emitStep(StepType.ERROR, event.message)
                                throw CancellationException(event.message)
                            }
                            is ChatEvent.Usage -> { /* optional tracking */ }
                        }
                    }

                    val content = assistantText.toString()
                    if (content.isNotBlank()) {
                        emitStep(StepType.REASONING, content)
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

                    if (pendingTools.isEmpty() || finishReason != "tool_calls") {
                        emitStep(StepType.SUMMARY, "Completed in $stepCount step(s)")
                        break
                    }

                    // execute tools
                    for (tc in pendingTools) {
                        ensureActive()
                        emitStep(StepType.TOOL_CALL, tc.arguments, tc.name)
                        val tool = tools.get(tc.name)
                        val result = if (tool == null) {
                            ToolResult.Failure("Unknown tool: ${tc.name}")
                        } else {
                            tool.execute(tc.arguments, policy, projectUri)
                        }

                        when (result) {
                            is ToolResult.NeedsApproval -> {
                                _events.emit(AgentUiEvent.NeedsApproval(result.toolName, result.args, result.reason))
                                // wait for external approval signal — for spine we pause the loop
                                emitStep(StepType.ERROR, "Awaiting user approval for ${result.toolName}")
                                return@launch
                            }
                            is ToolResult.Success -> {
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
                                emitStep(StepType.TOOL_RESULT, result.error, tc.name, result.error)
                                val toolMsg = ChatMessage("tool", "Error: ${result.error}", toolCallId = tc.id, name = tc.name)
                                messages.add(toolMsg)
                            }
                        }
                    }
                }

                // persist task
                db.taskDao().upsert(
                    TaskEntity(
                        taskId,
                        sessionId,
                        TaskStatus.COMPLETED.name,
                        JSONArray(steps.map {
                            JSONObject()
                                .put("index", it.index)
                                .put("type", it.type.name)
                                .put("content", it.content.take(2000))
                                .put("tool", it.toolName)
                        }).toString(),
                        "done",
                        System.currentTimeMillis(),
                        System.currentTimeMillis()
                    )
                )
                _events.emit(AgentUiEvent.Completed(taskId))
            } catch (e: CancellationException) {
                _events.emit(AgentUiEvent.Cancelled)
            } catch (e: Exception) {
                emitStep(StepType.ERROR, e.message ?: "agent failure")
                _events.emit(AgentUiEvent.Failed(e.message ?: "unknown"))
            }
        }
    }

    private fun buildSystemPrompt(mode: AgentMode, hasProject: Boolean): String {
        val base = """
            You are COSMIC AI, a precise autonomous coding agent running on Android.
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
    data class Completed(val taskId: String) : AgentUiEvent()
    data class Failed(val message: String) : AgentUiEvent()
    data object Cancelled : AgentUiEvent()
}
