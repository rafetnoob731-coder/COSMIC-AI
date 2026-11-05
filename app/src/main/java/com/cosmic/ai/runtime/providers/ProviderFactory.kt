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
        ProviderPreset("Groq", ProviderType.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "model-id"),
        ProviderPreset("Together AI", ProviderType.OPENAI_COMPATIBLE, "https://api.together.xyz/v1", "model-id"),
        ProviderPreset("Fireworks AI", ProviderType.OPENAI_COMPATIBLE, "https://api.fireworks.ai/inference/v1", "model-id"),
        ProviderPreset("Cerebras", ProviderType.OPENAI_COMPATIBLE, "https://api.cerebras.ai/v1", "model-id"),
        ProviderPreset("Mistral", ProviderType.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1", "model-id"),
        ProviderPreset("xAI", ProviderType.OPENAI_COMPATIBLE, "https://api.x.ai/v1", "model-id"),
        ProviderPreset("Perplexity", ProviderType.OPENAI_COMPATIBLE, "https://api.perplexity.ai", "model-id"),
        ProviderPreset("NVIDIA NIM", ProviderType.OPENAI_COMPATIBLE, "https://integrate.api.nvidia.com/v1", "model-id"),
        ProviderPreset("DeepInfra", ProviderType.OPENAI_COMPATIBLE, "https://api.deepinfra.com/v1/openai", "model-id"),
        ProviderPreset("SambaNova", ProviderType.OPENAI_COMPATIBLE, "https://api.sambanova.ai/v1", "model-id"),
        ProviderPreset("LiteLLM Proxy", ProviderType.OPENAI_COMPATIBLE, "https://", "model-id"),
        ProviderPreset("LocalAI", ProviderType.OPENAI_COMPATIBLE, "http://127.0.0.1:8080/v1", "model-id"),
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
