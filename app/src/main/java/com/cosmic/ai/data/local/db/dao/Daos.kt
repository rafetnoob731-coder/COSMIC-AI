// language: kotlin, file: Daos.kt, runtime: Room, target: data access
package com.cosmic.ai.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.ProviderEntity
import com.cosmic.ai.data.local.db.entity.SessionEntity
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.data.local.db.entity.ToolExecutionEntity
import com.cosmic.ai.data.local.db.entity.ApprovalRequestEntity
import com.cosmic.ai.data.local.db.entity.SecurityAuditEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeForSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getForSession(sessionId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY name ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getById(id: String): ProviderEntity?

    @Query("SELECT * FROM providers WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): ProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE providers SET isDefault = 0")
    suspend fun clearDefault()
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getById(id: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status IN ('QUEUED','STARTING','RUNNING','WAITING_FOR_MODEL','WAITING_FOR_TOOL','COMPLETING') ORDER BY createdAt ASC")
    suspend fun getRecoverable(): List<TaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)
}


@Dao
interface ToolExecutionDao {
    @Query("SELECT * FROM tool_executions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ToolExecutionEntity>>

    @Query("SELECT * FROM tool_executions WHERE taskId = :taskId AND callId = :callId")
    suspend fun get(taskId: String, callId: String): ToolExecutionEntity?

    @Query("SELECT * FROM tool_executions WHERE taskId = :taskId ORDER BY createdAt ASC")
    suspend fun forTask(taskId: String): List<ToolExecutionEntity>

    @Query("SELECT * FROM tool_executions WHERE taskId = :taskId AND state = 'RUNNING'")
    suspend fun runningForTask(taskId: String): List<ToolExecutionEntity>

    @Query("SELECT * FROM tool_executions WHERE state = 'RUNNING'")
    suspend fun running(): List<ToolExecutionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ToolExecutionEntity): Long

    @Query("UPDATE tool_executions SET state = :to, updatedAt = :updatedAt WHERE taskId = :taskId AND callId = :callId AND state = :from")
    suspend fun compareAndSetState(taskId: String, callId: String, from: String, to: String, updatedAt: Long): Int

    @Query("UPDATE tool_executions SET state = :state, error = :error, updatedAt = :updatedAt WHERE taskId = :taskId AND callId = :callId")
    suspend fun setState(taskId: String, callId: String, state: String, error: String?, updatedAt: Long): Int

    @Query("UPDATE tool_executions SET state = 'UNKNOWN', error = 'Process ended while tool was running; automatic replay prevented', updatedAt = :updatedAt WHERE state = 'RUNNING'")
    suspend fun markRunningUnknown(updatedAt: Long): Int

    @Query("UPDATE tool_executions SET state = 'UNKNOWN', error = :error, updatedAt = :updatedAt WHERE taskId = :taskId AND state = 'RUNNING'")
    suspend fun markTaskRunningUnknown(taskId: String, error: String, updatedAt: Long): Int

    @Query("UPDATE tool_executions SET state = :to, updatedAt = :updatedAt WHERE taskId = :taskId AND callId = :callId AND state = :from")
    suspend fun transition(taskId: String, callId: String, from: String, to: String, updatedAt: Long): Int

    @Query("UPDATE tool_executions SET state = :state, resultJson = :result, error = :error, updatedAt = :updatedAt WHERE taskId = :taskId AND callId = :callId AND state = 'RUNNING'")
    suspend fun complete(taskId: String, callId: String, state: String, result: String?, error: String?, updatedAt: Long): Int
}


@Dao
interface ApprovalRequestDao {
    @Query("SELECT * FROM approval_requests WHERE status IN ('PENDING','APPROVED') ORDER BY createdAt ASC")
    fun observeActionable(): Flow<List<ApprovalRequestEntity>>

    @Query("SELECT * FROM approval_requests WHERE status = 'EXECUTING'")
    suspend fun executing(): List<ApprovalRequestEntity>

    @Query("SELECT * FROM approval_requests WHERE taskId = :taskId AND status IN ('PENDING','APPROVED','EXECUTING')")
    suspend fun actionableForTask(taskId: String): List<ApprovalRequestEntity>

    @Query("SELECT * FROM approval_requests WHERE id = :id")
    suspend fun get(id: String): ApprovalRequestEntity?

    @Query("SELECT * FROM approval_requests WHERE status IN ('PENDING','APPROVED') AND expiresAt <= :at")
    suspend fun expired(at: Long): List<ApprovalRequestEntity>

    @Query("SELECT * FROM approval_requests WHERE taskId = :taskId AND callId = :callId")
    suspend fun byCall(taskId: String, callId: String): ApprovalRequestEntity?

    @Query("SELECT * FROM approval_requests WHERE toolName = :toolName AND resourceScope = :resourceScope AND status IN ('PENDING','APPROVED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun activeForScope(toolName: String, resourceScope: String): ApprovalRequestEntity?

    @Query("SELECT * FROM approval_requests WHERE taskId = :taskId AND toolName = :toolName AND argumentsHash = :argumentsHash AND status = 'COMPLETED' AND decisionMode = 'ALLOW_FOR_TASK' AND expiresAt > :now ORDER BY decidedAt DESC LIMIT 1")
    suspend fun matchingTaskGrant(taskId: String, toolName: String, argumentsHash: String, now: Long): ApprovalRequestEntity?

    @Query("UPDATE approval_requests SET status = :status, decisionMode = :mode, decidedAt = :at, outcome = :outcome WHERE id = :id AND status = :from")
    suspend fun transition(id: String, from: String, status: String, mode: String?, at: Long, outcome: String?): Int

    @Query("UPDATE approval_requests SET status = 'EXPIRED', decidedAt = :at, outcome = 'Approval expired; execution was not started' WHERE status IN ('PENDING','APPROVED') AND expiresAt <= :at")
    suspend fun expire(at: Long): Int

    @Query("UPDATE approval_requests SET status = 'CANCELLED', decidedAt = :at, outcome = :outcome WHERE id = :id AND status = 'PENDING'")
    suspend fun cancel(id: String, at: Long, outcome: String): Int

    @androidx.room.Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(request: ApprovalRequestEntity): Long
}

@Dao
interface SecurityAuditDao {
    @Query("SELECT * FROM security_audit_events ORDER BY sequence DESC, occurredAt DESC")
    fun observeAll(): Flow<List<SecurityAuditEventEntity>>

    @Query("SELECT * FROM security_audit_events WHERE decision = :decision ORDER BY sequence DESC")
    fun observeByDecision(decision: String): Flow<List<SecurityAuditEventEntity>>

    @Query("SELECT * FROM security_audit_events WHERE (toolName LIKE '%' || :query || '%' OR action LIKE '%' || :query || '%' OR resourceScope LIKE '%' || :query || '%' OR agentId LIKE '%' || :query || '%' OR taskId LIKE '%' || :query || '%') ORDER BY sequence DESC")
    fun search(query: String): Flow<List<SecurityAuditEventEntity>>

    @androidx.room.Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: SecurityAuditEventEntity)

    @Query("SELECT * FROM security_audit_events ORDER BY sequence ASC")
    suspend fun allBySequence(): List<SecurityAuditEventEntity>

    @Query("SELECT * FROM security_audit_events ORDER BY occurredAt ASC, eventId ASC")
    suspend fun allChronologically(): List<SecurityAuditEventEntity>

    @Query("SELECT * FROM security_audit_events ORDER BY sequence DESC LIMIT 1")
    suspend fun latest(): SecurityAuditEventEntity?

    @androidx.room.Update
    suspend fun update(event: SecurityAuditEventEntity): Int
}
