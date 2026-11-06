package com.cosmic.ai.data.local.db

import androidx.room.withTransaction
import com.cosmic.ai.core.state.TaskTransitions
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.ToolExecutionEntity
import com.cosmic.ai.data.local.db.entity.SecurityAuditEventEntity
import com.cosmic.ai.domain.agent.TaskStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class TaskLifecycleRepository(private val database: CosmicDatabase) {
    suspend fun create(task: TaskEntity, initialMessage: MessageEntity? = null) = database.withTransaction {
        require(database.taskDao().getById(task.id) == null) { "Task already exists: ${task.id}" }
        val status = TaskStatus.valueOf(task.status)
        require(status == TaskStatus.PENDING || status == TaskStatus.QUEUED) {
            "New tasks must start in PENDING or QUEUED"
        }
        val now = System.currentTimeMillis()
        val history = JSONArray(task.transitionHistoryJson).put(transitionRecord(status, now, "created"))
        database.taskDao().upsert(task.copy(transitionHistoryJson = history.toString(), updatedAt = now))
        initialMessage?.let { database.messageDao().insert(it) }
    }

    /** Atomically grants one worker ownership of a queued/recoverable task. */
    suspend fun claimForExecution(taskId: String): TaskEntity? = database.withTransaction {
        val current = database.taskDao().getById(taskId) ?: return@withTransaction null
        val source = TaskStatus.valueOf(current.status)
        if (source !in setOf(TaskStatus.QUEUED, TaskStatus.RECOVERY_REQUIRED, TaskStatus.RETRYABLE)) {
            return@withTransaction null
        }
        val now = System.currentTimeMillis()
        val history = JSONArray(current.transitionHistoryJson)
        if (source != TaskStatus.QUEUED) {
            TaskTransitions.requireTransition(source, TaskStatus.QUEUED)
            history.put(transitionRecord(TaskStatus.QUEUED, now, "Worker claimed recovery task"))
        }
        TaskTransitions.requireTransition(TaskStatus.QUEUED, TaskStatus.STARTING)
        history.put(transitionRecord(TaskStatus.STARTING, now, "Execution claimed"))
        val claimed = current.copy(
            status = TaskStatus.STARTING.name,
            progressMessage = "Starting",
            transitionHistoryJson = history.toString(),
            updatedAt = now
        )
        database.taskDao().upsert(claimed)
        claimed
    }

    suspend fun transition(taskId: String, target: TaskStatus, progress: String): TaskEntity = database.withTransaction {
        val current = database.taskDao().getById(taskId) ?: error("Task not found: $taskId")
        val source = TaskStatus.valueOf(current.status)
        TaskTransitions.requireTransition(source, target)
        val now = System.currentTimeMillis()
        val history = JSONArray(current.transitionHistoryJson).put(transitionRecord(target, now, progress))
        val updated = current.copy(
            status = target.name,
            progressMessage = progress.take(300),
            transitionHistoryJson = history.toString(),
            updatedAt = now
        )
        database.taskDao().upsert(updated)
        updated
    }

    suspend fun requestCancel(taskId: String) = database.withTransaction {
        val current = database.taskDao().getById(taskId) ?: return@withTransaction
        if (!TaskTransitions.isTerminal(TaskStatus.valueOf(current.status))) {
            database.taskDao().upsert(current.copy(cancelRequested = true, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun recoverable(): List<TaskEntity> = database.taskDao().getRecoverable()

    suspend fun markInterruptedTaskForRecovery(taskId: String): Boolean = database.withTransaction {
        val task = database.taskDao().getById(taskId) ?: return@withTransaction false
        val source = TaskStatus.valueOf(task.status)
        if (source == TaskStatus.RECOVERY_REQUIRED) return@withTransaction true
        if (!TaskTransitions.canTransition(source, TaskStatus.RECOVERY_REQUIRED)) return@withTransaction false
        val now = System.currentTimeMillis()
        val history = JSONArray(task.transitionHistoryJson)
            .put(transitionRecord(TaskStatus.RECOVERY_REQUIRED, now, "Worker resumed task after process interruption"))
        database.taskDao().upsert(task.copy(
            status = TaskStatus.RECOVERY_REQUIRED.name,
            progressMessage = "Interrupted. Recovering persisted task.",
            transitionHistoryJson = history.toString(),
            updatedAt = now
        ))
        val runningCalls = database.toolExecutionDao().runningForTask(taskId)
        database.toolExecutionDao().markTaskRunningUnknown(
            taskId, "Process ended while tool was running; automatic replay prevented", now
        )
        runningCalls.forEach { call -> SecurityAuditRepository(database).append(SecurityAuditEventEntity(
            UUID.randomUUID().toString(), null, taskId, "orchestrator", call.toolName,
            "invoke", auditResource(call.toolName, call.argumentsJson), "UNKNOWN", now, "Process interruption; not replayed", null
        )) }
        true
    }

    /** Marks interrupted work for explicit recovery; uncertain tool side effects are never replayed. */
    suspend fun markInterruptedTasksForRecovery(): List<String> = database.withTransaction {
        val interrupted = database.taskDao().getRecoverable()
        val now = System.currentTimeMillis()
        val ids = interrupted.mapNotNull { task ->
            val status = TaskStatus.valueOf(task.status)
            if (!TaskTransitions.canTransition(status, TaskStatus.RECOVERY_REQUIRED)) return@mapNotNull null
            val history = JSONArray(task.transitionHistoryJson)
                .put(transitionRecord(TaskStatus.RECOVERY_REQUIRED, now, "Process ended during execution"))
            database.taskDao().upsert(task.copy(
                status = TaskStatus.RECOVERY_REQUIRED.name,
                progressMessage = "Interrupted. Review execution history before resuming.",
                transitionHistoryJson = history.toString(),
                updatedAt = now
            ))
            task.id
        }
        val runningCalls = database.toolExecutionDao().running()
        database.toolExecutionDao().markRunningUnknown(now)
        runningCalls.forEach { call -> SecurityAuditRepository(database).append(SecurityAuditEventEntity(
            UUID.randomUUID().toString(), null, call.taskId, "orchestrator", call.toolName,
            "invoke", auditResource(call.toolName, call.argumentsJson), "UNKNOWN", now, "Process interruption; not replayed", null
        )) }
        ids
    }

    suspend fun prepareToolCall(taskId: String, callId: String, toolName: String, argsJson: String,
                                argsHash: String, idempotent: Boolean): ToolExecutionEntity = database.withTransaction {
        require(database.taskDao().getById(taskId) != null) { "Cannot execute a tool for a missing task" }
        val old = database.toolExecutionDao().get(taskId, callId)
        if (old != null) {
            require(old.argumentsHash == argsHash && old.toolName == toolName) {
                "Tool call ID was reused with different arguments"
            }
            return@withTransaction old
        }
        val now = System.currentTimeMillis()
        val record = ToolExecutionEntity(taskId, callId, toolName, argsJson, argsHash,
            "PREPARED", idempotent, createdAt = now, updatedAt = now)
        database.toolExecutionDao().insert(record)
        SecurityAuditRepository(database).append(SecurityAuditEventEntity(
            UUID.randomUUID().toString(), null, taskId, "orchestrator", toolName,
            "invoke", auditResource(toolName, argsJson), "TOOL_REQUESTED", now, "Prepared", null
        ))
        record
    }

    suspend fun startToolCall(taskId: String, callId: String): ToolExecutionEntity = database.withTransaction {
        val record = database.toolExecutionDao().get(taskId, callId) ?: error("Tool call was not prepared")
        when (record.state) {
            "SUCCEEDED", "FAILED", "UNKNOWN", "NEEDS_APPROVAL" -> return@withTransaction record
            "RUNNING" -> {
                val now = System.currentTimeMillis()
                database.toolExecutionDao().setState(taskId, callId, "UNKNOWN", "Execution outcome is unknown; automatic replay prevented", now)
                SecurityAuditRepository(database).append(SecurityAuditEventEntity(
                    UUID.randomUUID().toString(), null, taskId, "orchestrator", record.toolName,
                    "invoke", auditResource(record.toolName, record.argumentsJson), "UNKNOWN", now, "Duplicate execution prevented", null
                ))
                return@withTransaction record.copy(state = "UNKNOWN", error = "Execution outcome is unknown; automatic replay prevented")
            }
            "PREPARED" -> Unit
            else -> error("Unsupported tool execution state ${record.state}")
        }
        val now = System.currentTimeMillis()
        val updated = database.toolExecutionDao().compareAndSetState(taskId, callId, record.state, "RUNNING", now)
        if (updated != 1) error("Tool call was claimed by another execution")
        SecurityAuditRepository(database).append(SecurityAuditEventEntity(
            UUID.randomUUID().toString(), null, taskId, "orchestrator", record.toolName,
            "invoke", auditResource(record.toolName, record.argumentsJson), "EXECUTION_CLAIMED", now, null, null
        ))
        record.copy(state = "RUNNING", updatedAt = now)
    }

    suspend fun completeToolCall(taskId: String, callId: String, resultJson: String): Boolean = database.withTransaction {
        val record = database.toolExecutionDao().get(taskId, callId) ?: return@withTransaction false
        if (record.state == "SUCCEEDED") return@withTransaction record.resultJson == resultJson
        if (record.state != "RUNNING") return@withTransaction false
        val now = System.currentTimeMillis()
        val changed = database.toolExecutionDao().complete(taskId, callId, "SUCCEEDED", resultJson, null, now) == 1
        if (changed) SecurityAuditRepository(database).append(SecurityAuditEventEntity(
            UUID.randomUUID().toString(), null, taskId, "orchestrator", record.toolName,
            "invoke", auditResource(record.toolName, record.argumentsJson), "EXECUTION_COMPLETED", now, "Tool result persisted", null
        ))
        changed
    }

    suspend fun markToolUnknown(taskId: String, callId: String, reason: String): Boolean = database.withTransaction {
        val record = database.toolExecutionDao().get(taskId, callId) ?: return@withTransaction false
        if (record.state != "RUNNING") return@withTransaction record.state == "UNKNOWN"
        val now = System.currentTimeMillis()
        val safe = com.cosmic.ai.security.SensitiveTextRedactor.redact(reason).take(300)
        val changed = database.toolExecutionDao().setState(taskId, callId, "UNKNOWN", safe, now) == 1
        if (changed) SecurityAuditRepository(database).append(SecurityAuditEventEntity(
            UUID.randomUUID().toString(), null, taskId, "orchestrator", record.toolName,
            "invoke", auditResource(record.toolName, record.argumentsJson), "UNKNOWN", now,
            "Execution may have produced side effects; automatic retry blocked", safe
        ))
        changed
    }

    suspend fun failToolCall(taskId: String, callId: String, error: String): Boolean = database.withTransaction {
        val record = database.toolExecutionDao().get(taskId, callId) ?: return@withTransaction false
        val now = System.currentTimeMillis()
        val changed = database.toolExecutionDao().complete(taskId, callId, "FAILED", null,
            com.cosmic.ai.security.SensitiveTextRedactor.redact(error).take(300), now) == 1
        if (changed) SecurityAuditRepository(database).append(SecurityAuditEventEntity(
            UUID.randomUUID().toString(), null, taskId, "orchestrator", record.toolName,
            "invoke", auditResource(record.toolName, record.argumentsJson), "EXECUTION_FAILED", now, "Tool failed", com.cosmic.ai.security.SensitiveTextRedactor.redact(error).take(300)
        ))
        changed
    }

    suspend fun markApproval(taskId: String, callId: String): Boolean = database.withTransaction {
        database.toolExecutionDao().complete(taskId, callId, "NEEDS_APPROVAL", null, null, System.currentTimeMillis()) == 1
    }

    suspend fun markTaskToolsUnknown(taskId: String, reason: String) = database.withTransaction {
        database.toolExecutionDao().markTaskRunningUnknown(taskId, reason.take(300), System.currentTimeMillis())
    }

    suspend fun toolCalls(taskId: String): List<ToolExecutionEntity> = database.toolExecutionDao().forTask(taskId)

    private fun auditResource(toolName: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return toolName.take(200)
        val value = when (toolName) {
            "run_command" -> args.optString("command", toolName)
            "read_file", "write_file", "list_files" -> args.optString("path", "project root")
            else -> toolName
        }
        return com.cosmic.ai.security.SensitiveTextRedactor.redact(value).take(300)
    }

    private fun transitionRecord(status: TaskStatus, at: Long, detail: String) =
        JSONObject().put("status", status.name).put("at", at).put("detail", detail.take(300))

}
