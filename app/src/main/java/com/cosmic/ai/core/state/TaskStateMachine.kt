// language: kotlin, file: TaskStateMachine.kt, runtime: pure Kotlin, target: platform-independent COSMIC CORE
package com.cosmic.ai.core.state

import java.util.UUID

enum class TaskPhase {
    CREATED,
    ANALYZING,
    PLANNING,
    AWAITING_APPROVAL,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    PAUSED
}

data class TaskPlanStep(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val assignedAgent: String? = null,
    val toolName: String? = null,
    val status: TaskPhase = TaskPhase.CREATED
)

data class CosmicTask(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val title: String,
    val instruction: String,
    val phase: TaskPhase = TaskPhase.CREATED,
    val plan: List<TaskPlanStep> = emptyList(),
    val selectedModel: String? = null,
    val assignedAgent: String? = null,
    val toolHistory: List<ToolHistoryEntry> = emptyList(),
    val approvalHistory: List<ApprovalEntry> = emptyList(),
    val result: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val recoverable: Boolean = true
)

data class ToolHistoryEntry(
    val tool: String,
    val args: String,
    val output: String,
    val success: Boolean,
    val at: Long = System.currentTimeMillis()
)

data class ApprovalEntry(
    val action: String,
    val approved: Boolean,
    val at: Long = System.currentTimeMillis()
)

class TaskStateMachine {
    private val tasks = linkedMapOf<String, CosmicTask>()

    fun create(sessionId: String, title: String, instruction: String): CosmicTask {
        val t = CosmicTask(sessionId = sessionId, title = title, instruction = instruction)
        tasks[t.id] = t
        return t
    }

    fun get(id: String): CosmicTask? = tasks[id]

    fun all(): List<CosmicTask> = tasks.values.toList()

    fun transition(id: String, phase: TaskPhase, error: String? = null, result: String? = null): CosmicTask? {
        val cur = tasks[id] ?: return null
        val next = cur.copy(
            phase = phase,
            error = error ?: cur.error,
            result = result ?: cur.result,
            updatedAt = System.currentTimeMillis()
        )
        tasks[id] = next
        return next
    }

    fun setPlan(id: String, plan: List<TaskPlanStep>): CosmicTask? {
        val cur = tasks[id] ?: return null
        val next = cur.copy(plan = plan, phase = TaskPhase.PLANNING, updatedAt = System.currentTimeMillis())
        tasks[id] = next
        return next
    }

    fun appendTool(id: String, entry: ToolHistoryEntry): CosmicTask? {
        val cur = tasks[id] ?: return null
        val next = cur.copy(toolHistory = cur.toolHistory + entry, updatedAt = System.currentTimeMillis())
        tasks[id] = next
        return next
    }

    fun appendApproval(id: String, entry: ApprovalEntry): CosmicTask? {
        val cur = tasks[id] ?: return null
        val next = cur.copy(approvalHistory = cur.approvalHistory + entry, updatedAt = System.currentTimeMillis())
        tasks[id] = next
        return next
    }

    fun assignAgent(id: String, agent: String, model: String?): CosmicTask? {
        val cur = tasks[id] ?: return null
        val next = cur.copy(assignedAgent = agent, selectedModel = model ?: cur.selectedModel, updatedAt = System.currentTimeMillis())
        tasks[id] = next
        return next
    }
}
