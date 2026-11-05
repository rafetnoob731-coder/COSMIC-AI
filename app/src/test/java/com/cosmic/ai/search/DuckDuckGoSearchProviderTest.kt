package com.cosmic.ai.search

import com.cosmic.ai.runtime.search.DuckDuckGoSearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DuckDuckGoSearchProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: DuckDuckGoSearchProvider

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        provider = DuckDuckGoSearchProvider(endpoint = server.url("/").toString())
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun parsesInstantAnswerAndRelatedTopicsWithAttribution() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""
            {"Heading":"Kotlin","AbstractText":"Kotlin official language site","AbstractURL":"https://kotlinlang.org/","RelatedTopics":[{"Text":"Coroutines - Kotlin docs","FirstURL":"https://kotlinlang.org/docs/coroutines-overview.html"}]}
        """.trimIndent()))
        val result = provider.search("Kotlin coroutine docs").getOrThrow()
        assertEquals(2, result.size)
        assertEquals("https://kotlinlang.org/", result[0].url)
        assertEquals("DuckDuckGo Instant Answers", result[0].source)
        val request = server.takeRequest()
        assertEquals("Kotlin coroutine docs", request.requestUrl?.queryParameter("q"))
        assertEquals("json", request.requestUrl?.queryParameter("format"))
        assertEquals(null, request.getHeader("Authorization"))
    }

    @Test fun sendsBanglaAndEnglishQueriesWithoutLosingQueryText() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(provider.search("বাংলা প্রোগ্রামিং").getOrThrow().isEmpty())
        assertTrue(provider.search("Android Compose").getOrThrow().isEmpty())
        assertEquals("বাংলা প্রোগ্রামিং", server.takeRequest().requestUrl?.queryParameter("q"))
        assertEquals("Android Compose", server.takeRequest().requestUrl?.queryParameter("q"))
    }

    @Test fun emptyAndOversizedQueriesAreRejectedBeforeNetwork() = runBlocking {
        assertTrue(provider.search("  ").isFailure)
        assertTrue(provider.search("x".repeat(501)).isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun malformedResponseAndHttpFailuresAreErrors() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("private upstream detail"))
        val malformed = provider.search("query").exceptionOrNull()
        assertTrue(malformed?.message.orEmpty().contains("invalid JSON"))
        val http = provider.search("query").exceptionOrNull()
        assertTrue(http?.message.orEmpty().contains("HTTP 503"))
        assertFalse(http?.message.orEmpty().contains("private upstream"))
    }

    @Test fun unsafeAndEmptySourceLinksAreNotReturned() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"Heading":"x","AbstractText":"y","AbstractURL":"javascript:alert(1)","RelatedTopics":[{"Text":"no link","FirstURL":""}]}"""))
        assertTrue(provider.search("query").getOrThrow().isEmpty())
    }

    @Test fun cancellationCancelsUnderlyingHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = async { provider.search("slow query") }
        server.takeRequest(2, TimeUnit.SECONDS)
        job.cancel(CancellationException("test cancellation"))
        withTimeout(2_000) { job.join() }
        assertTrue(job.isCancelled)
    }
}
