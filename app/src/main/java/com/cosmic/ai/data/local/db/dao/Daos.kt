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

    @Query("UPDATE tool_executions SET state = :state, resultJson = :result, error = :error, updatedAt = :updatedAt WHERE taskId = :taskId AND callId = :callId AND state = 'RUNNING'")
    suspend fun complete(taskId: String, callId: String, state: String, result: String?, error: String?, updatedAt: Long): Int
}
