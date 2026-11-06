package com.cosmic.ai.runtime.agent

import android.app.Application
import androidx.room.Room
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.runtime.background.TaskWorkerOutcome
import com.cosmic.ai.domain.provider.ProviderConfig
import com.cosmic.ai.domain.provider.ProviderType
import com.cosmic.ai.runtime.mcp.McpClientManager
import com.cosmic.ai.runtime.providers.OpenAiCompatibleProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = AgentRuntimeHttpTest.TestApplication::class, sdk = [35])
class AgentRuntimeHttpTest {
    class TestApplication : Application()
    private lateinit var server: MockWebServer
    private lateinit var database: CosmicDatabase
    private lateinit var databaseName: String

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        databaseName = "agent-runtime-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), CosmicDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
        server.shutdown()
    }

    @Test fun simpleHiUsesRealStreamingAdapterPersistsResponseThenCompletes() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()))
        val runtime = AgentRuntime(RuntimeEnvironment.getApplication(), database, { "test-key" }, McpClientManager(),
            { OpenAiCompatibleProvider() }, enqueueTaskWork = { _, _ -> })
        val done = CompletableDeferred<AgentUiEvent>()
        val collector = CoroutineScope(coroutineContext).launch {
            runtime.events.collect { event ->
                if (event is AgentUiEvent.Completed || event is AgentUiEvent.Failed) done.complete(event)
            }
        }
        val config = ProviderConfig("test", "Mock OpenAI", ProviderType.OPENAI_COMPATIBLE,
            server.url("/v1").toString().trimEnd('/'), "api-key", "test-model")
        runtime.run("session", "hi", config, "test-model")
        val result = withTimeout(10_000) { done.await() }
        assertTrue("Expected completion, got $result", result is AgentUiEvent.Completed)
        assertEquals(listOf("hi", "Hello"), database.messageDao().getForSession("session").map { it.content })
        assertEquals("COMPLETED", database.taskDao().getById((result as AgentUiEvent.Completed).taskId)?.status)
        assertEquals("Bearer test-key", server.takeRequest().getHeader("Authorization"))
        collector.cancel()
    }

    @Test fun unauthorizedProviderResponseIsReportedAsHttp401AndPersistedFailed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("private authorization response"))
        val runtime = AgentRuntime(RuntimeEnvironment.getApplication(), database, { "invalid-key" }, McpClientManager(),
            { OpenAiCompatibleProvider() }, enqueueTaskWork = { _, _ -> })
        val done = CompletableDeferred<AgentUiEvent.Failed>()
        val collector = CoroutineScope(coroutineContext).launch {
            runtime.events.collect { event -> if (event is AgentUiEvent.Failed) done.complete(event) }
        }
        val config = ProviderConfig("test", "Mock OpenAI", ProviderType.OPENAI_COMPATIBLE,
            server.url("/v1").toString().trimEnd('/'), "api-key", "test-model")
        runtime.run("session", "hi", config, "test-model")
        val failure = withTimeout(10_000) { done.await() }
        assertTrue(failure.message.contains("HTTP 401"))
        assertTrue(!failure.message.contains("private authorization response"))
        assertEquals("FAILED", database.taskDao().getById(failure.taskId!!)?.status)
        collector.cancel()
    }

    @Test fun providerNetworkDisconnectFailsInsteadOfReportingCompletion() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val runtime = AgentRuntime(RuntimeEnvironment.getApplication(), database, { "test-key" }, McpClientManager(),
            { OpenAiCompatibleProvider() }, enqueueTaskWork = { _, _ -> })
        val done = CompletableDeferred<AgentUiEvent>()
        val collector = CoroutineScope(coroutineContext).launch {
            runtime.events.collect { event -> if (event is AgentUiEvent.Failed || event is AgentUiEvent.Completed) done.complete(event) }
        }
        val config = ProviderConfig("test", "Mock OpenAI", ProviderType.OPENAI_COMPATIBLE,
            server.url("/v1").toString().trimEnd('/'), "api-key", "test-model")
        runtime.run("session", "hi", config, "test-model")
        val result = withTimeout(10_000) { done.await() }
        assertTrue("Expected network failure, got $result", result is AgentUiEvent.Failed)
        collector.cancel()
    }

    @Test fun workerRecoversPersistedTaskFromInterruptedModelWait() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"Recovered"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()))
        val userId = "recovery-user-message"
        database.messageDao().insert(MessageEntity(userId, "recovery-session", "user", "hi", createdAt = 100L))
        val config = ProviderConfig("test", "Mock OpenAI", ProviderType.OPENAI_COMPATIBLE,
            server.url("/v1").toString().trimEnd('/'), "api-key", "test-model")
        database.taskDao().upsert(TaskEntity(
            id = "recovery-task", sessionId = "recovery-session", status = "WAITING_FOR_MODEL",
            stepsJson = "[]", progressMessage = "Waiting for model", createdAt = 90L, updatedAt = 100L,
            providerConfigJson = org.json.JSONObject()
                .put("id", config.id).put("name", config.name).put("type", config.type.name)
                .put("baseUrl", config.baseUrl).put("apiKeyAlias", config.apiKeyAlias)
                .put("defaultModel", config.defaultModel).toString(),
            modelName = "test-model", mode = "CHAT", requestMessageId = userId
        ))
        val runtime = AgentRuntime(RuntimeEnvironment.getApplication(), database, { "test-key" }, McpClientManager(),
            { OpenAiCompatibleProvider() }, enqueueTaskWork = { _, _ -> })
        val outcome = runtime.runOrRecoverPersistedTask("recovery-task") { }
        assertEquals(TaskWorkerOutcome.FINISHED, outcome)
        assertEquals("COMPLETED", database.taskDao().getById("recovery-task")?.status)
        assertEquals(listOf("hi", "Recovered"), database.messageDao().getForSession("recovery-session").map { it.content })
    }

    @Test fun failedToolIsPersistedAndStopsFurtherModelGeneration() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-exit","function":{"name":"run_command","arguments":"{\"command\":\"exit 7\"}"}}]},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()))
        val runtime = AgentRuntime(RuntimeEnvironment.getApplication(), database, { "test-key" }, McpClientManager(),
            { OpenAiCompatibleProvider() }, enqueueTaskWork = { _, _ -> })
        runtime.updatePolicy(com.cosmic.ai.domain.agent.PermissionPolicy(
            allowTerminal = true, requireApprovalForDestructive = false
        ))
        val done = CompletableDeferred<AgentUiEvent>()
        val collector = CoroutineScope(coroutineContext).launch {
            runtime.events.collect { event -> if (event is AgentUiEvent.Failed || event is AgentUiEvent.Completed) done.complete(event) }
        }
        val config = ProviderConfig("test", "Mock OpenAI", ProviderType.OPENAI_COMPATIBLE,
            server.url("/v1").toString().trimEnd('/'), "api-key", "test-model")
        runtime.run("failure-session", "run the command", config, "test-model")
        val result = withTimeout(10_000) { done.await() }
        assertTrue("Non-zero tool exit must fail the task, got $result", result is AgentUiEvent.Failed)
        assertEquals("FAILED", database.taskDao().getById((result as AgentUiEvent.Failed).taskId!!)?.status)
        assertEquals("Only the initial model call may run after the tool failure", 1, server.requestCount)
        assertTrue(database.messageDao().getForSession("failure-session").any { it.role == "tool" && it.content.contains("exit=7") })
        collector.cancel()
    }

    @Test fun missingCredentialFailsWithConfigurationErrorInsteadOfGenericIncompleteMessage() = runBlocking {
        val runtime = AgentRuntime(RuntimeEnvironment.getApplication(), database, { null }, McpClientManager(), enqueueTaskWork = { _, _ -> })
        val done = CompletableDeferred<AgentUiEvent>()
        val collector = CoroutineScope(coroutineContext).launch {
            runtime.events.collect { event -> if (event is AgentUiEvent.Failed) done.complete(event) }
        }
        val config = ProviderConfig("test", "OpenAI", ProviderType.OPENAI_COMPATIBLE,
            server.url("/v1").toString().trimEnd('/'), "missing", "model")
        runtime.run("session", "hi", config, "model")
        val failure = withTimeout(10_000) { done.await() } as AgentUiEvent.Failed
        assertTrue(failure.message.contains("No API key is configured"))
        assertEquals("FAILED", database.taskDao().getById(failure.taskId!!)?.status)
        collector.cancel()
    }
}
