package com.cosmic.ai.data.local.db

import androidx.room.withTransaction
import com.cosmic.ai.core.state.TaskTransitions
import com.cosmic.ai.data.local.db.entity.ApprovalRequestEntity
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.SecurityAuditEventEntity
import com.cosmic.ai.data.local.db.entity.ToolExecutionEntity
import com.cosmic.ai.domain.agent.TaskStatus
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID

class ApprovalLifecycleRepository(private val db: CosmicDatabase) {
    fun observePending(): Flow<List<ApprovalRequestEntity>> = db.approvalRequestDao().observeActionable()
    suspend fun requestById(requestId: String): ApprovalRequestEntity? = db.approvalRequestDao().get(requestId)
    fun observeAudit(): Flow<List<SecurityAuditEventEntity>> = db.securityAuditDao().observeAll()
    fun searchAudit(query: String): Flow<List<SecurityAuditEventEntity>> = db.securityAuditDao().search(query.trim())

    suspend fun request(
        taskId: String, callId: String, agentId: String, toolName: String, action: String,
        resource: String, risk: String, reason: String, argumentsHash: String,
        ttlMillis: Long = 10 * 60 * 1000L
    ): ApprovalRequestEntity = db.withTransaction {
        val existing = db.approvalRequestDao().byCall(taskId, callId)
        if (existing != null) {
            require(existing.toolName == toolName && existing.argumentsHash == argumentsHash && existing.action == action) {
                "Duplicate approval identity does not match the original action scope"
            }
            return@withTransaction existing
        }
        val task = db.taskDao().getById(taskId) ?: error("Task not found for approval")
        val call = db.toolExecutionDao().get(taskId, callId) ?: error("Tool call not found for approval")
        require(call.toolName == toolName && call.argumentsHash == argumentsHash) { "Approval scope does not match tool call" }
        require(call.state == "RUNNING" || call.state == "NEEDS_APPROVAL") { "Tool call is not awaiting approval" }
        val now = System.currentTimeMillis()
        val request = ApprovalRequestEntity(
            id = UUID.randomUUID().toString(), taskId = taskId, callId = callId,
            agentId = agentId.take(80), toolName = toolName.take(120), action = action.take(120),
            resourceScope = com.cosmic.ai.security.SensitiveTextRedactor.redact(resource).take(300), riskLevel = risk.take(32), reason = com.cosmic.ai.security.SensitiveTextRedactor.redact(reason).take(500),
            argumentsHash = argumentsHash, status = "PENDING", createdAt = now, expiresAt = now + ttlMillis
        )
        check(db.approvalRequestDao().insert(request) != -1L) { "Could not persist approval request" }
        if (call.state == "RUNNING") {
            check(db.toolExecutionDao().complete(taskId, callId, "NEEDS_APPROVAL", null, "Approval request ${request.id}", now) == 1) {
                "Could not mark tool call as awaiting approval"
            }
        }
        val status = TaskStatus.valueOf(task.status)
        if (status != TaskStatus.AWAITING_APPROVAL) {
            TaskTransitions.requireTransition(status, TaskStatus.AWAITING_APPROVAL)
            appendTaskTransition(task, TaskStatus.AWAITING_APPROVAL, "Approval required for $toolName", now)
        }
        appendAudit(request, "REQUESTED", now, "Pending user decision", null)
        request
    }

    /** Persist an exact, digest-bound MCP STDIO launch approval without storing credentials or command arguments. */
    suspend fun createMcpLaunchRequest(serverId: String, serverName: String, configHash: String): ApprovalRequestEntity = db.withTransaction {
        val toolName = "mcp_server_launch"
        val action = "mcp_server_connect:$serverId"
        val resource = "server config sha256:${configHash.take(24)}"
        db.approvalRequestDao().activeForScope(toolName, resource)?.let { return@withTransaction it }
        val taskId = "mcp-launch:${UUID.randomUUID()}"
        val callId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val args = JSONObject().put("serverId", serverId).put("configHash", configHash).toString()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(args.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        var task = com.cosmic.ai.data.local.db.entity.TaskEntity(
            id = taskId, sessionId = "mcp-launch", status = TaskStatus.QUEUED.name,
            stepsJson = "[]", progressMessage = "MCP server launch requires approval", createdAt = now,
            updatedAt = now, mode = "MCP_LAUNCH_APPROVAL",
            transitionHistoryJson = org.json.JSONArray().put(JSONObject().put("status", TaskStatus.QUEUED.name).put("at", now).put("detail", "MCP launch approval created")).toString()
        )
        db.taskDao().upsert(task)
        for ((status, detail) in listOf(
            TaskStatus.STARTING to "MCP server launch prepared",
            TaskStatus.WAITING_FOR_TOOL to "Waiting for launch authorization",
            TaskStatus.AWAITING_APPROVAL to "Waiting for user approval"
        )) {
            TaskTransitions.requireTransition(TaskStatus.valueOf(task.status), status)
            task = task.copy(status = status.name, progressMessage = detail, updatedAt = now,
                transitionHistoryJson = appendHistory(task, status, detail, now))
            db.taskDao().upsert(task)
        }
        check(db.toolExecutionDao().insert(ToolExecutionEntity(
            taskId, callId, toolName, args, hash, "NEEDS_APPROVAL", false,
            error = "Awaiting user approval", createdAt = now, updatedAt = now
        )) != -1L) { "Could not persist MCP launch operation" }
        val request = ApprovalRequestEntity(
            id = UUID.randomUUID().toString(), taskId = taskId, callId = callId,
            agentId = "mcp", toolName = toolName, action = action,
            resourceScope = resource, riskLevel = "HIGH",
            reason = com.cosmic.ai.security.SensitiveTextRedactor.redact("Launch MCP STDIO server ${serverName.take(80)} as an app-sandbox process").take(500),
            argumentsHash = hash, status = "PENDING", createdAt = now, expiresAt = now + 10 * 60 * 1000L
        )
        check(db.approvalRequestDao().insert(request) != -1L) { "Could not persist MCP launch approval" }
        appendAudit(request, "REQUESTED", now, "STDIO server launch awaits explicit user decision", null)
        request
    }

    suspend fun createTerminalRequest(command: String, reason: String): ApprovalRequestEntity = db.withTransaction {
        val taskId = "terminal:${UUID.randomUUID()}"
        val callId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val args = JSONObject().put("command", command).toString()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(args.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        var history = org.json.JSONArray().put(JSONObject().put("status", TaskStatus.QUEUED.name).put("at", now).put("detail", "Terminal approval task created"))
        var task = com.cosmic.ai.data.local.db.entity.TaskEntity(
            id = taskId, sessionId = "terminal", status = TaskStatus.QUEUED.name,
            stepsJson = "[]", progressMessage = "Queued for permission decision", createdAt = now,
            updatedAt = now, mode = "TERMINAL_APPROVAL", transitionHistoryJson = history.toString()
        )
        db.taskDao().upsert(task)
        for ((status, detail) in listOf(
            TaskStatus.STARTING to "Command prepared",
            TaskStatus.WAITING_FOR_TOOL to "Waiting for terminal authorization",
            TaskStatus.AWAITING_APPROVAL to "Waiting for user approval"
        )) {
            TaskTransitions.requireTransition(TaskStatus.valueOf(task.status), status)
            history = org.json.JSONArray(task.transitionHistoryJson).put(JSONObject().put("status", status.name).put("at", now).put("detail", detail))
            task = task.copy(status = status.name, progressMessage = detail, updatedAt = now, transitionHistoryJson = history.toString())
            db.taskDao().upsert(task)
        }
        check(db.toolExecutionDao().insert(ToolExecutionEntity(
            taskId, callId, "run_command", args, hash, "NEEDS_APPROVAL", false,
            error = "Awaiting user approval", createdAt = now, updatedAt = now
        )) != -1L) { "Could not persist terminal operation" }
        val request = ApprovalRequestEntity(
            id = UUID.randomUUID().toString(), taskId = taskId, callId = callId,
            agentId = "terminal", toolName = "run_command", action = "Execute command",
            resourceScope = "command sha256:${hash.take(16)}", riskLevel = "HIGH", reason = com.cosmic.ai.security.SensitiveTextRedactor.redact(reason).take(500),
            argumentsHash = hash, status = "PENDING", createdAt = now, expiresAt = now + 10 * 60 * 1000L
        )
        check(db.approvalRequestDao().insert(request) != -1L)
        appendAudit(request, "REQUESTED", now, "Terminal command awaits user approval", null)
        request
    }

    suspend fun taskGrant(taskId: String, toolName: String, argumentsHash: String): ApprovalRequestEntity? = db.withTransaction {
        if (toolName != "write_file") return@withTransaction null
        val grant = db.approvalRequestDao().matchingTaskGrant(taskId, toolName, argumentsHash, System.currentTimeMillis())
            ?: return@withTransaction null
        SecurityAuditRepository(db).append(SecurityAuditEventEntity(
            UUID.randomUUID().toString(), grant.id, taskId, grant.agentId, toolName,
            "repeat exact scoped write", grant.resourceScope, "TASK_GRANT_USED", System.currentTimeMillis(),
            "Identical arguments within the same task; tool ledger still enforces call ID idempotency", null
        ))
        grant
    }

    suspend fun decide(requestId: String, allow: Boolean, mode: String): ApprovalRequestEntity = db.withTransaction {
        require(mode in setOf("ALLOW_ONCE", "ALLOW_FOR_TASK", "DENY")) { "Unsupported approval mode" }
        val request = db.approvalRequestDao().get(requestId) ?: error("Approval request not found")
        require(request.status == "PENDING") { "Approval is no longer pending" }
        val now = System.currentTimeMillis()
        if (request.expiresAt <= now) {
            db.approvalRequestDao().transition(requestId, "PENDING", "EXPIRED", null, now, "Request expired before decision")
            appendAudit(request, "EXPIRED", now, "No tool execution started", null)
            db.toolExecutionDao().setState(request.taskId, request.callId, "FAILED", "Approval expired", now)
            expireTaskIfWaiting(request)
            return@withTransaction request.copy(status = "EXPIRED", decidedAt = now, outcome = "Request expired before decision")
        }
        if (allow && mode == "ALLOW_FOR_TASK") require(request.toolName == "write_file") {
            "Task-scoped reuse is only supported for an identical, idempotent file write"
        }
        val task = db.taskDao().getById(request.taskId) ?: error("Approval task no longer exists")
        require(task.status == TaskStatus.AWAITING_APPROVAL.name) { "Task is no longer waiting for approval" }
        val call = db.toolExecutionDao().get(request.taskId, request.callId) ?: error("Approved tool call no longer exists")
        require(call.state == "NEEDS_APPROVAL" && call.argumentsHash == request.argumentsHash && call.toolName == request.toolName) {
            "Stored tool call does not match approval scope"
        }
        val target = if (allow) "APPROVED" else "DENIED"
        check(db.approvalRequestDao().transition(requestId, "PENDING", target, mode, now, if (allow) null else "Denied by user") == 1) {
            "Approval was decided concurrently"
        }
        appendAudit(request, target, now, if (allow) mode else "Tool execution blocked", null)
        if (!allow) {
            db.toolExecutionDao().setState(request.taskId, request.callId, "FAILED", "Denied by user", now)
            val cancelled = task.copy(status = TaskStatus.CANCELLED.name, progressMessage = "Cancelled after permission denial",
                transitionHistoryJson = appendHistory(task, TaskStatus.CANCELLED, "Permission denied", now), updatedAt = now)
            TaskTransitions.requireTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.CANCELLED)
            db.taskDao().upsert(cancelled)
        }
        request.copy(status = target, decisionMode = mode, decidedAt = now, outcome = if (allow) null else "Denied by user")
    }

    /** Atomic single-use claim. A process death after this point is recorded UNKNOWN and never replayed. */
    suspend fun claimApproved(requestId: String): Pair<ApprovalRequestEntity, ToolExecutionEntity> = db.withTransaction {
        val request = db.approvalRequestDao().get(requestId) ?: error("Approval request not found")
        require(request.status == "APPROVED") { "Approval is not executable" }
        require(request.expiresAt > System.currentTimeMillis()) { "Approval expired" }
        val task = db.taskDao().getById(request.taskId) ?: error("Task not found")
        require(task.status == TaskStatus.AWAITING_APPROVAL.name) { "Task is not awaiting approval" }
        val call = db.toolExecutionDao().get(request.taskId, request.callId) ?: error("Tool call not found")
        require(call.state == "NEEDS_APPROVAL" && call.argumentsHash == request.argumentsHash && call.toolName == request.toolName) {
            "Approved operation no longer matches its persisted scope"
        }
        val now = System.currentTimeMillis()
        check(db.approvalRequestDao().transition(requestId, "APPROVED", "EXECUTING", request.decisionMode, now, null) == 1)
        check(db.toolExecutionDao().transition(request.taskId, request.callId, "NEEDS_APPROVAL", "RUNNING", now) == 1)
        appendAudit(request, "EXECUTION_STARTED", now, "", null)
        request.copy(status = "EXECUTING") to call.copy(state = "RUNNING", updatedAt = now)
    }

    suspend fun finishApproved(request: ApprovalRequestEntity, success: Boolean, result: String?, output: String?, error: String?) = db.withTransaction {
        val current = db.approvalRequestDao().get(request.id) ?: error("Approval request not found")
        require(current.status == "EXECUTING") { "Approval execution is not active" }
        val now = System.currentTimeMillis()
        val outcome = if (success) "COMPLETED" else "FAILED"
        val safeError = error?.let(::sanitizeError)
        check(db.toolExecutionDao().complete(current.taskId, current.callId,
            if (success) "SUCCEEDED" else "FAILED", result?.take(32_000), safeError, now) == 1)
        check(db.approvalRequestDao().transition(current.id, "EXECUTING", outcome, current.decisionMode, now, safeError ?: outcome) == 1)
        appendAudit(current, outcome, now, outcome, safeError)
        val task = db.taskDao().getById(current.taskId) ?: error("Task not found")
        if (success && task.mode in setOf("TERMINAL_APPROVAL", "MCP_LAUNCH_APPROVAL")) {
            TaskTransitions.requireTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.COMPLETED)
            val completion = if (task.mode == "MCP_LAUNCH_APPROVAL") "MCP server connected" else "Terminal command completed"
            db.taskDao().upsert(task.copy(status = TaskStatus.COMPLETED.name, progressMessage = completion,
                transitionHistoryJson = appendHistory(task, TaskStatus.COMPLETED, completion, now), updatedAt = now))
        } else if (success) {
            db.messageDao().insert(MessageEntity(UUID.randomUUID().toString(), task.sessionId, "tool", output.orEmpty(),
                toolCallId = current.callId, createdAt = now))
            TaskTransitions.requireTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.QUEUED)
            db.taskDao().upsert(task.copy(status = TaskStatus.QUEUED.name, progressMessage = "Approved tool completed",
                transitionHistoryJson = appendHistory(task, TaskStatus.QUEUED, "Approved tool completed", now), updatedAt = now))
        } else {
            TaskTransitions.requireTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.FAILED)
            db.taskDao().upsert(task.copy(status = TaskStatus.FAILED.name, progressMessage = "Approved tool failed",
                transitionHistoryJson = appendHistory(task, TaskStatus.FAILED, "Approved tool failed", now), updatedAt = now))
        }
    }

    suspend fun finishApprovedUnknown(request: ApprovalRequestEntity, reason: String) = db.withTransaction {
        val current = db.approvalRequestDao().get(request.id) ?: error("Approval request not found")
        if (current.status == "UNKNOWN") return@withTransaction
        require(current.status == "EXECUTING") { "Approval execution is not active" }
        val now = System.currentTimeMillis()
        val safe = sanitizeError(reason)
        check(db.toolExecutionDao().setState(current.taskId, current.callId, "UNKNOWN", safe, now) == 1)
        check(db.approvalRequestDao().transition(current.id, "EXECUTING", "UNKNOWN", current.decisionMode, now,
            "Outcome unknown; automatic replay prevented") == 1)
        appendAudit(current, "UNKNOWN", now, "Possible side effects; never automatically retried", safe)
        val task = db.taskDao().getById(current.taskId) ?: error("Task not found")
        TaskTransitions.requireTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.FAILED)
        db.taskDao().upsert(task.copy(status = TaskStatus.FAILED.name, progressMessage = "Approved operation outcome is unknown",
            transitionHistoryJson = appendHistory(task, TaskStatus.FAILED, "Approved operation outcome unknown; no replay", now), updatedAt = now))
    }

    suspend fun cancel(requestId: String, reason: String = "Cancelled by user"): Boolean = db.withTransaction {
        val request = db.approvalRequestDao().get(requestId) ?: return@withTransaction false
        if (request.status !in setOf("PENDING", "APPROVED")) return@withTransaction false
        val now = System.currentTimeMillis()
        if (db.approvalRequestDao().transition(requestId, request.status, "CANCELLED", request.decisionMode, now, reason.take(200)) != 1) return@withTransaction false
        db.toolExecutionDao().setState(request.taskId, request.callId, "FAILED", reason.take(300), now)
        val task = db.taskDao().getById(request.taskId)
        if (task?.status == TaskStatus.AWAITING_APPROVAL.name) {
            db.taskDao().upsert(task.copy(status = TaskStatus.CANCELLED.name, progressMessage = reason.take(200),
                transitionHistoryJson = appendHistory(task, TaskStatus.CANCELLED, reason, now), updatedAt = now))
        }
        appendAudit(request, "CANCELLED", now, reason, null)
        true
    }

    suspend fun cancelForTask(taskId: String, reason: String): Int = db.withTransaction {
        val now = System.currentTimeMillis()
        var cancelled = 0
        for (request in db.approvalRequestDao().actionableForTask(taskId)) {
            if (request.status == "EXECUTING") {
                if (db.approvalRequestDao().transition(request.id, "EXECUTING", "UNKNOWN", request.decisionMode, now,
                        "Cancelled during an operation; external outcome is unknown") != 1) continue
                db.toolExecutionDao().setState(taskId, request.callId, "UNKNOWN", "Cancelled while running; outcome unknown; no replay", now)
                appendAudit(request, "UNKNOWN", now, "Cancellation during execution; outcome unknown", null)
                cancelled++
                continue
            }
            if (db.approvalRequestDao().transition(request.id, request.status, "CANCELLED", request.decisionMode, now, reason.take(200)) != 1) continue
            db.toolExecutionDao().setState(taskId, request.callId, "FAILED", reason.take(300), now)
            appendAudit(request, "CANCELLED", now, reason, null)
            cancelled++
        }
        cancelled
    }

    suspend fun expirePending(): Int = db.withTransaction {
        val now = System.currentTimeMillis()
        val expired = db.approvalRequestDao().expired(now)
        var count = 0
        for (req in expired) {
            if (db.approvalRequestDao().transition(req.id, req.status, "EXPIRED", req.decisionMode, now, "Approval expired") == 1) {
                db.toolExecutionDao().setState(req.taskId, req.callId, "FAILED", "Approval expired", now)
                expireTaskIfWaiting(req)
                appendAudit(req, "EXPIRED", now, "No tool execution started", null)
                count++
            }
        }
        count
    }

    suspend fun recoverInterruptedExecutions(): Int = db.withTransaction {
        val now = System.currentTimeMillis()
        var recovered = 0
        for (request in db.approvalRequestDao().executing()) {
            if (db.approvalRequestDao().transition(request.id, "EXECUTING", "UNKNOWN", request.decisionMode, now,
                    "Process ended during execution; outcome is unknown and will not be replayed") != 1) continue
            db.toolExecutionDao().setState(request.taskId, request.callId, "UNKNOWN",
                "Approved operation may have run before process interruption; automatic replay prevented", now)
            appendAudit(request, "UNKNOWN", now, "Execution outcome unknown; never automatically retried", null)
            val task = db.taskDao().getById(request.taskId)
            if (task?.status == TaskStatus.AWAITING_APPROVAL.name) {
                db.taskDao().upsert(task.copy(status = TaskStatus.FAILED.name,
                    progressMessage = "Approved operation was interrupted; outcome unknown",
                    transitionHistoryJson = appendHistory(task, TaskStatus.FAILED, "Approved tool outcome is unknown; no replay", now), updatedAt = now))
            }
            recovered++
        }
        recovered
    }

    private suspend fun expireTaskIfWaiting(request: ApprovalRequestEntity) {
        val task = db.taskDao().getById(request.taskId) ?: return
        if (task.status == TaskStatus.AWAITING_APPROVAL.name) {
            val now = System.currentTimeMillis()
            db.taskDao().upsert(task.copy(status = TaskStatus.CANCELLED.name, progressMessage = "Approval expired",
                transitionHistoryJson = appendHistory(task, TaskStatus.CANCELLED, "Approval expired", now), updatedAt = now))
        }
    }

    private suspend fun appendAudit(request: ApprovalRequestEntity, decision: String, at: Long, outcome: String?, error: String?) {
        SecurityAuditRepository(db).append(SecurityAuditEventEntity(UUID.randomUUID().toString(), request.id,
            request.taskId, request.agentId, request.toolName, request.action,
            com.cosmic.ai.security.SensitiveTextRedactor.redact(request.resourceScope).take(300), decision, at, outcome?.let(com.cosmic.ai.security.SensitiveTextRedactor::redact)?.take(250), error?.let(::sanitizeError)))
    }

    private suspend fun appendTaskTransition(task: com.cosmic.ai.data.local.db.entity.TaskEntity, status: TaskStatus, detail: String, at: Long) {
        db.taskDao().upsert(task.copy(status = status.name, progressMessage = detail.take(250),
            transitionHistoryJson = appendHistory(task, status, detail, at), updatedAt = at))
    }

    private fun appendHistory(task: com.cosmic.ai.data.local.db.entity.TaskEntity, status: TaskStatus, detail: String, at: Long): String =
        org.json.JSONArray(task.transitionHistoryJson).put(JSONObject().put("status", status.name).put("at", at).put("detail", detail.take(250))).toString()

    private fun sanitizeError(value: String): String = com.cosmic.ai.security.SensitiveTextRedactor.redact(value)
        .replace(Regex("(?i)(/[^\\s]+)+"), "[path]").take(300)
}
