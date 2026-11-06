// language: kotlin, file: Entities.kt, runtime: Room, target: persistence
package com.cosmic.ai.data.local.db.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String?,
    val model: String?,
    val projectUri: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCallsJson: String? = null,
    val createdAt: Long
)

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val apiKeyAlias: String,
    val defaultModel: String,
    val extraJson: String = "{}",
    val isDefault: Boolean = false
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val status: String,
    val stepsJson: String,
    val progressMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val providerConfigJson: String? = null,
    val modelName: String? = null,
    val mode: String? = null,
    @ColumnInfo(defaultValue = "12") val maxSteps: Int = 12,
    @ColumnInfo(defaultValue = "0") val attempt: Int = 0,
    val projectUri: String? = null,
    val requestMessageId: String? = null,
    @ColumnInfo(defaultValue = "0") val cancelRequested: Boolean = false,
    @ColumnInfo(defaultValue = "'[]'") val transitionHistoryJson: String = "[]"
)

@Entity(tableName = "tool_executions", primaryKeys = ["taskId", "callId"],
    indices = [androidx.room.Index(value = ["taskId", "state"])])
data class ToolExecutionEntity(
    val taskId: String,
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val argumentsHash: String,
    val state: String,
    val idempotent: Boolean,
    val resultJson: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)


@Entity(tableName = "approval_requests", indices = [
    androidx.room.Index(value = ["taskId", "callId"], unique = true),
    androidx.room.Index(value = ["status", "createdAt"])
])
data class ApprovalRequestEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val callId: String,
    val agentId: String,
    val toolName: String,
    val action: String,
    val resourceScope: String,
    val riskLevel: String,
    val reason: String,
    val argumentsHash: String,
    val status: String,
    val decisionMode: String? = null,
    val createdAt: Long,
    val expiresAt: Long,
    val decidedAt: Long? = null,
    val outcome: String? = null
)

@Entity(tableName = "security_audit_events", indices = [
    androidx.room.Index(value = ["requestId", "occurredAt"]),
    androidx.room.Index(value = ["decision", "occurredAt"]),
    androidx.room.Index(value = ["toolName", "occurredAt"]),
    androidx.room.Index(value = ["taskId", "occurredAt"]),
    androidx.room.Index(value = ["sequence"])
])
data class SecurityAuditEventEntity(
    @PrimaryKey val eventId: String,
    val requestId: String?,
    val taskId: String?,
    val agentId: String?,
    val toolName: String,
    val action: String,
    val resourceScope: String,
    val decision: String,
    val occurredAt: Long,
    val outcome: String? = null,
    val sanitizedError: String? = null,
    @ColumnInfo(defaultValue = "0") val sequence: Long = 0,
    @ColumnInfo(defaultValue = "''") val previousHash: String = "",
    @ColumnInfo(defaultValue = "''") val eventHash: String = ""
)
