// language: kotlin, file: CosmicMemory.kt, runtime: Room-backed, target: four memory layers
package com.cosmic.ai.memory

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memory_entries")
data class MemoryEntity(
    @PrimaryKey val id: String,
    val layer: String, // SESSION | PROJECT | USER | KNOWLEDGE
    val projectId: String?,
    val key: String,
    val content: String,
    val tags: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_entries WHERE layer = :layer ORDER BY updatedAt DESC")
    fun observeLayer(layer: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory_entries WHERE layer = :layer AND projectId = :projectId ORDER BY updatedAt DESC")
    fun observeProject(layer: String, projectId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory_entries WHERE content LIKE '%' || :q || '%' OR key LIKE '%' || :q || '%' ORDER BY updatedAt DESC LIMIT 50")
    suspend fun search(q: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MemoryEntity)

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memory_entries WHERE layer = :layer AND projectId = :projectId")
    suspend fun clearProject(layer: String, projectId: String)
}

class CosmicMemory(private val dao: MemoryDao) {
    fun session() = dao.observeLayer("SESSION")
    fun project(projectId: String) = dao.observeProject("PROJECT", projectId)
    fun userPrefs() = dao.observeLayer("USER")
    fun knowledge() = dao.observeLayer("KNOWLEDGE")

    suspend fun put(layer: String, key: String, content: String, projectId: String? = null, tags: String = "") {
        val now = System.currentTimeMillis()
        dao.upsert(
            MemoryEntity(
                id = "${layer}_${key}_${projectId ?: "g"}",
                layer = layer,
                projectId = projectId,
                key = key,
                content = content,
                tags = tags,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun search(q: String) = dao.search(q)
    suspend fun delete(id: String) = dao.delete(id)
}
