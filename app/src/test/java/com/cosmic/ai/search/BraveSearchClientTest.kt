package com.cosmic.ai.search

import com.cosmic.ai.runtime.search.BraveSearchClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BraveSearchClientTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() { server.shutdown() }

    @Test fun sendsSearchCredentialInDedicatedHeaderAndReturnsCitableSources() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"web":{"results":[{"title":"Official docs","url":"https://developer.example/docs","description":"API guide"},{"title":"Bad URL","url":"javascript:alert(1)","description":"ignored"}]}}"""
        ))
        val client = BraveSearchClient({ "brave-test-secret" }, endpoint = server.url("/search").toString())
        val result = client.search("Kotlin HTTP API", 4).getOrThrow()
        assertEquals(1, result.size)
        assertEquals("https://developer.example/docs", result.single().url)
        val request = server.takeRequest()
        assertEquals("brave-test-secret", request.getHeader("X-Subscription-Token"))
        assertEquals("Kotlin HTTP API", request.requestUrl?.queryParameter("q"))
        assertTrue(request.path!!.startsWith("/search?"))
    }

    @Test fun missingKeyAndHttpErrorsAreExplicitAndDoNotLeakResponseBody() = runBlocking {
        assertTrue(BraveSearchClient({ null }).search("query").exceptionOrNull()?.message.orEmpty().contains("not configured"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("private gateway body"))
        val failure = BraveSearchClient({ "test" }, endpoint = server.url("/search").toString())
            .search("query").exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("HTTP 429"))
        assertTrue(!failure?.message.orEmpty().contains("private gateway"))
    }
}
