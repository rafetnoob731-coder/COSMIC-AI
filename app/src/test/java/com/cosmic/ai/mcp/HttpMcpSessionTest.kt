package com.cosmic.ai.mcp

import com.cosmic.ai.domain.mcp.McpServerConfig
import com.cosmic.ai.domain.mcp.McpTransport
import com.cosmic.ai.runtime.mcp.HttpMcpSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HttpMcpSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var session: HttpMcpSession

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        session = HttpMcpSession(
            McpServerConfig(
                id = "server-1", name = "Test MCP", transport = McpTransport.HTTP,
                url = server.url("/mcp").toString(), headers = mapOf("Authorization" to "Bearer test-token")
            )
        )
    }

    @After fun tearDown() { session.close(); server.shutdown() }

    @Test fun initializesDiscoversAndInvokesToolsUsingJsonRpc() = runBlocking {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05"}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"lookup","description":"Lookup item","inputSchema":{"type":"object","properties":{"id":{"type":"string"}}}}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"found"}]}}"""))

        session.initialize()
        val tools = session.listTools()
        assertEquals(1, tools.size)
        assertEquals("lookup", tools.single().name)
        assertEquals("server-1", tools.single().serverId)
        assertTrue(tools.single().inputSchemaJson.contains("properties"))

        val result = session.callTool("lookup", """{"id":"42"}""")
        assertEquals("found", result.getOrThrow())
        val requests = (1..3).map { server.takeRequest() }
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer test-token" })
        assertTrue(requests[0].body.readUtf8().contains("\"method\":\"initialize\""))
        assertTrue(requests[1].body.readUtf8().contains("\"method\":\"tools/list\""))
        val callBody = requests[2].body.readUtf8()
        assertTrue(callBody.contains("\"method\":\"tools/call\""))
        assertTrue(callBody.contains("\"id\":\"42\""))
    }

    @Test fun invalidToolArgumentsAreRejectedWithoutNetworkRequest() = runBlocking {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05"}}"""))
        session.initialize()
        val result = session.callTool("lookup", "[not-an-object]")
        assertTrue(result.isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun remoteCleartextMcpEndpointIsRejected() {
        try {
            HttpMcpSession(
                McpServerConfig(id = "remote", name = "Remote", transport = McpTransport.HTTP, url = "http://example.com/mcp")
            )
            fail("Remote cleartext MCP endpoint must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("HTTPS"))
        }
    }

    @Test fun httpFailureIsReportedWithoutClaimingToolSuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val result = runCatching { session.initialize() }
        assertTrue(result.isFailure)
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
