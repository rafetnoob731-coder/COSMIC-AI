// language: kotlin, file: Entities.kt, runtime: Room, target: persistence
package com.cosmic.ai.data.local.db.entity

import androidx.room.Entity
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
    val updatedAt: Long
)
