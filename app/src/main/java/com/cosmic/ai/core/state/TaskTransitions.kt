package com.cosmic.ai.core.state

import com.cosmic.ai.domain.agent.TaskStatus

/** Shared transition policy used by both runtime persistence and tests. */
object TaskTransitions {
    private val allowed = mapOf(
        TaskStatus.PENDING to setOf(TaskStatus.QUEUED, TaskStatus.STARTING, TaskStatus.CANCELLED),
        TaskStatus.QUEUED to setOf(TaskStatus.STARTING, TaskStatus.RETRYABLE, TaskStatus.CANCELLED, TaskStatus.RECOVERY_REQUIRED, TaskStatus.FAILED),
        TaskStatus.STARTING to setOf(TaskStatus.QUEUED, TaskStatus.WAITING_FOR_MODEL, TaskStatus.WAITING_FOR_TOOL, TaskStatus.RUNNING, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.RETRYABLE, TaskStatus.RECOVERY_REQUIRED),
        TaskStatus.RUNNING to setOf(TaskStatus.WAITING_FOR_MODEL, TaskStatus.WAITING_FOR_TOOL, TaskStatus.COMPLETING, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.RETRYABLE, TaskStatus.RECOVERY_REQUIRED),
        TaskStatus.WAITING_FOR_MODEL to setOf(TaskStatus.RUNNING, TaskStatus.WAITING_FOR_TOOL, TaskStatus.COMPLETING, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.RETRYABLE, TaskStatus.RECOVERY_REQUIRED),
        TaskStatus.WAITING_FOR_TOOL to setOf(TaskStatus.RUNNING, TaskStatus.WAITING_FOR_MODEL, TaskStatus.AWAITING_APPROVAL, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.RECOVERY_REQUIRED),
        TaskStatus.COMPLETING to setOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.RECOVERY_REQUIRED),
        TaskStatus.AWAITING_APPROVAL to setOf(TaskStatus.QUEUED, TaskStatus.COMPLETED, TaskStatus.CANCELLED, TaskStatus.FAILED),
        TaskStatus.RETRYABLE to setOf(TaskStatus.QUEUED, TaskStatus.CANCELLED, TaskStatus.FAILED),
        TaskStatus.RECOVERY_REQUIRED to setOf(TaskStatus.QUEUED, TaskStatus.CANCELLED, TaskStatus.FAILED),
        TaskStatus.COMPLETED to emptySet(),
        TaskStatus.FAILED to emptySet(),
        TaskStatus.CANCELLED to emptySet()
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean = to in allowed.getValue(from)

    fun requireTransition(from: TaskStatus, to: TaskStatus) {
        require(canTransition(from, to)) { "Invalid task state transition: $from -> $to" }
    }

    fun isTerminal(status: TaskStatus): Boolean = status in setOf(
        TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED
    )

    fun isRecoverable(status: TaskStatus): Boolean = status in setOf(
        TaskStatus.QUEUED, TaskStatus.STARTING, TaskStatus.RUNNING,
        TaskStatus.WAITING_FOR_MODEL, TaskStatus.WAITING_FOR_TOOL, TaskStatus.COMPLETING,
        TaskStatus.RETRYABLE, TaskStatus.RECOVERY_REQUIRED
    )
}
