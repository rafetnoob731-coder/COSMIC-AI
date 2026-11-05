package com.cosmic.ai.providers

import com.cosmic.ai.domain.provider.ChatMessage
import com.cosmic.ai.domain.provider.ChatRequest
import com.cosmic.ai.domain.provider.ProviderConfig
import com.cosmic.ai.domain.provider.ProviderType
import com.cosmic.ai.runtime.providers.AnthropicProvider
import com.cosmic.ai.runtime.providers.GeminiProvider
import com.cosmic.ai.runtime.providers.OpenAiCompatibleProvider
import com.cosmic.ai.runtime.providers.ProviderFactory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderAdaptersTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun providerPresetsAreUniqueAndRetainCustomEndpoint() {
        val presets = ProviderFactory.presets()
        assertEquals(19, presets.size)
        assertEquals(19, presets.map { it.label }.toSet().size)
        assertTrue(presets.any { it.label == "Custom OpenAI-compatible" && it.type == ProviderType.CUSTOM })
        assertTrue(presets.any { it.label == "Ollama (local)" })
        assertTrue(presets.all { it.baseUrl.startsWith("https://") || it.baseUrl.startsWith("http://127.0.0.1") })
    }

    @Test fun factoryUsesDistinctNativeAdaptersAndCompatibleAdapterForGateway() {
        assertTrue(ProviderFactory.get(ProviderType.ANTHROPIC) is AnthropicProvider)
        assertTrue(ProviderFactory.get(ProviderType.GEMINI) is GeminiProvider)
        assertTrue(ProviderFactory.get(ProviderType.OPENROUTER) is OpenAiCompatibleProvider)
        assertTrue(ProviderFactory.get(ProviderType.DEEPSEEK) is OpenAiCompatibleProvider)
        assertTrue(ProviderFactory.get(ProviderType.CUSTOM) is OpenAiCompatibleProvider)
    }

    @Test fun openAiCompatibleModelDiscoveryParsesModelsAndUsesBearerKey() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":[{"id":"model-a"},{"id":"model-b","name":"Model B"}]}"""))
        val result = OpenAiCompatibleProvider().discoverModels(config("/v1"), "test-secret")
        assertTrue(result.isSuccess)
        assertEquals(listOf("model-a", "model-b"), result.getOrThrow().map { it.id })
        assertEquals("Model B", result.getOrThrow()[1].displayName)
        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer test-secret", request.getHeader("Authorization"))
    }

    @Test fun anthropicModelDiscoveryUsesNativeHeaders() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"data":[{"id":"claude-test","display_name":"Claude Test"}]}"""))
        val result = AnthropicProvider().discoverModels(config(""), "anthropic-secret")
        assertEquals("claude-test", result.getOrThrow().single().id)
        val request = server.takeRequest()
        assertEquals("/v1/models?limit=1000", request.path)
        assertEquals("anthropic-secret", request.getHeader("x-api-key"))
    }

    @Test fun geminiModelDiscoveryFiltersModelsWithoutGenerationSupportAndKeepsKeyOutOfUrl() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"models":[{"name":"models/gemini-test","displayName":"Gemini Test","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding-test","supportedGenerationMethods":["embedContent"]}]}"""
        ))
        val result = GeminiProvider().discoverModels(config(""), "gemini-secret")
        assertEquals(listOf("gemini-test"), result.getOrThrow().map { it.id })
        val request = server.takeRequest()
        assertEquals("/v1beta/models", request.path)
        assertEquals("gemini-secret", request.getHeader("x-goog-api-key"))
        assertFalse(request.requestUrl!!.queryParameterNames.contains("key"))
    }

    @Test fun openAiCompletionParsesAssistantResponseAndSendsRequestedModel() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"choices":[{"message":{"role":"assistant","content":"completed"},"finish_reason":"stop"}]}"""
        ))
        val result = OpenAiCompatibleProvider().completeChat(
            config("/v1"), "test-secret", ChatRequest("model-x", listOf(ChatMessage("user", "hello")), stream = false)
        )
        assertTrue(result.isSuccess)
        assertEquals("completed", result.getOrThrow().content)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertTrue(request.body.readUtf8().contains("\"model\":\"model-x\""))
    }

    @Test fun providerHttpErrorsDoNotIncludeResponseBodyInException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("credential leaked by gateway"))
        val result = OpenAiCompatibleProvider().discoverModels(config("/v1"), "test-secret")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("credential leaked"))
    }

    private fun config(path: String) = ProviderConfig(
        id = "test", name = "Test", type = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = server.url(path.trimStart('/').let { if (it.isEmpty()) "" else "$it/" }).toString().trimEnd('/'),
        apiKeyAlias = "key", defaultModel = "model"
    )
}
