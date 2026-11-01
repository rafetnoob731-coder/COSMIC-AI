// language: kotlin, file: AgentModels.kt, runtime: domain, target: agent loop
package com.cosmic.ai.domain.agent

import com.cosmic.ai.domain.provider.ChatMessage
import com.cosmic.ai.domain.provider.ToolCall

enum class AgentMode {
    CHAT,
    AGENT,
    PLAN
}

data class AgentTaskState(
    val taskId: String,
    val sessionId: String,
    val status: TaskStatus,
    val steps: List<AgentStep>,
    val currentTool: String? = null,
    val progressMessage: String? = null
)

enum class TaskStatus {
    PENDING,
    RUNNING,
    AWAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AgentStep(
    val index: Int,
    val type: StepType,
    val content: String,
    val toolName: String? = null,
    val toolResult: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class StepType {
    REASONING,
    TOOL_CALL,
    TOOL_RESULT,
    SUMMARY,
    ERROR
}

data class PermissionPolicy(
    val allowFileRead: Boolean = true,
    val allowFileWrite: Boolean = false,
    val allowTerminal: Boolean = false,
    val allowNetwork: Boolean = true,
    val requireApprovalForDestructive: Boolean = true,
    val approvedCommands: Set<String> = emptySet()
)
