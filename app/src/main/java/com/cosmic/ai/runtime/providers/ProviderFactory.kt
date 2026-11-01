// language: kotlin, file: ProviderFactory.kt, runtime: domain, target: all adapters
package com.cosmic.ai.runtime.providers

import com.cosmic.ai.domain.provider.AiProvider
import com.cosmic.ai.domain.provider.ProviderType

object ProviderFactory {
    private val openAi = OpenAiCompatibleProvider()
    private val anthropic = AnthropicProvider()
    private val gemini = GeminiProvider()

    fun get(type: ProviderType): AiProvider = when (type) {
        ProviderType.OPENAI_COMPATIBLE,
        ProviderType.OPENROUTER,
        ProviderType.DEEPSEEK,
        ProviderType.CUSTOM -> openAi
        ProviderType.ANTHROPIC -> anthropic
        ProviderType.GEMINI -> gemini
    }

    fun presets(): List<ProviderPreset> = listOf(
        ProviderPreset("OpenAI", ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-4o-mini"),
        ProviderPreset("Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com", "claude-3-5-sonnet-20241022"),
        ProviderPreset("Google Gemini", ProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-1.5-flash"),
        ProviderPreset("OpenRouter", ProviderType.OPENROUTER, "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        ProviderPreset("DeepSeek", ProviderType.DEEPSEEK, "https://api.deepseek.com", "deepseek-chat"),
        ProviderPreset("Custom OpenAI-compatible", ProviderType.CUSTOM, "https://", "model-id"),
        ProviderPreset("Ollama (local)", ProviderType.OPENAI_COMPATIBLE, "http://127.0.0.1:11434/v1", "llama3.2")
    )
}

data class ProviderPreset(
    val label: String,
    val type: ProviderType,
    val baseUrl: String,
    val defaultModel: String
)
