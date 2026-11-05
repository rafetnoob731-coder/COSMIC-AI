// language: kotlin, file: AiProvider.kt, runtime: domain, target: provider adapters
package com.cosmic.ai.domain.provider

import kotlinx.coroutines.flow.Flow

data class ProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKeyAlias: String,
    val defaultModel: String,
    val extra: Map<String, String> = emptyMap()
)

data class ProviderModel(
    val id: String,
    val displayName: String = id
)

enum class ProviderType {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI,
    OPENROUTER,
    DEEPSEEK,
    CUSTOM
}

data class ChatMessage(
    val role: String, // system | user | assistant | tool
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val name: String? = null
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val stream: Boolean = true
)

sealed class ChatEvent {
    data class Delta(val text: String) : ChatEvent()
    data class ToolCallDelta(val toolCall: ToolCall) : ChatEvent()
    data class Usage(val promptTokens: Int, val completionTokens: Int) : ChatEvent()
    data class Finished(val finishReason: String) : ChatEvent()
    data class Error(val message: String, val code: Int? = null) : ChatEvent()
}

interface AiProvider {
    val type: ProviderType
    suspend fun testConnection(config: ProviderConfig, apiKey: String): Result<String>
    suspend fun discoverModels(config: ProviderConfig, apiKey: String): Result<List<ProviderModel>> =
        Result.failure(UnsupportedOperationException("Model discovery is not supported by this provider adapter"))
    fun streamChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Flow<ChatEvent>
    suspend fun completeChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Result<ChatMessage>
}
