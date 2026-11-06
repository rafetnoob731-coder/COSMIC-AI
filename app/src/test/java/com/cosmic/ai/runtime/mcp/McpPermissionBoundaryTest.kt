package com.cosmic.ai.runtime.mcp

import com.cosmic.ai.domain.mcp.McpTool
import com.cosmic.ai.security.PermissionDecision
import com.cosmic.ai.security.PermissionGateway
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpPermissionBoundaryTest {
    @Test fun stableToolIdentityResolvesAgainAfterExplicitServerReconnect() {
        val manager = McpClientManager()
        val tool = McpTool("server-id", "lookup", "lookup", "{}")
        val agentName = manager.agentToolName(tool)
        val session = object : McpSession {
            override suspend fun initialize() = Unit
            override suspend fun listTools() = listOf(tool)
            override suspend fun callTool(name: String, argumentsJson: String): Result<String> = Result.success("ok")
            override fun close() = Unit
        }
        manager.publishConnectedSession(tool.serverId, session, listOf(tool))
        assertEquals(tool, manager.resolveAgentTool(agentName))
        manager.disconnect(tool.serverId)
        assertNull(manager.resolveAgentTool(agentName))
        manager.publishConnectedSession(tool.serverId, session, listOf(tool))
        assertEquals(tool, manager.resolveAgentTool(agentName))
    }

    @Test fun stdioServerDoesNotStartWhenExecutionPermissionIsDenied() = runBlocking {
        val manager = McpClientManager(PermissionGateway())
        val config = com.cosmic.ai.domain.mcp.McpServerConfig(
            id = "blocked", name = "Blocked process", transport = com.cosmic.ai.domain.mcp.McpTransport.STDIO,
            command = "this-command-must-not-run"
        )
        manager.connect(config)
        val state = withTimeout(2_000) { manager.states.first { it[config.id] is com.cosmic.ai.domain.mcp.McpConnectionState.Error } }
        assertTrue((state[config.id] as com.cosmic.ai.domain.mcp.McpConnectionState.Error).message.contains("EXECUTE"))
        assertFalse(manager.isConnected(com.cosmic.ai.domain.mcp.McpTool(config.id, "x", "x", "{}")))
    }

    @Test fun directMcpToolInvocationRequiresGatewayApproval() {
        val manager = McpClientManager()
        val result = manager.authorizeToolCall("server", "delete_item", "{\"id\":\"42\"}")
        assertTrue(result is PermissionDecision.NeedsApproval || result is PermissionDecision.Deny)
    }

    @Test fun mcpExecutionRequiresExactSingleUseGatewayAuthorization() = runBlocking {
        val gateway = PermissionGateway()
        val manager = McpClientManager(gateway)
        val tool = McpTool("server", "change_item", "change", "{\"type\":\"object\"}")
        manager.publishConnectedSession("server", object : McpSession {
            override suspend fun initialize() = Unit
            override suspend fun listTools() = listOf(tool)
            override suspend fun callTool(name: String, argumentsJson: String): Result<String> = Result.success("changed")
            override fun close() = Unit
        }, listOf(tool))
        val args = "{\"id\":\"7\"}"
        val scope = manager.approvalScope(tool, args)
        assertTrue(manager.executeApproved(tool, args, null, "task", "call", scope) is com.cosmic.ai.runtime.tools.ToolResult.NeedsApproval)
        val token = gateway.issueApprovalAuthorization("request", "task", "call", "mcp:server:change_item", scope,
            System.currentTimeMillis() + 60_000)
        assertEquals(com.cosmic.ai.runtime.tools.ToolResult.Success("changed"),
            manager.executeApproved(tool, args, token, "task", "call", scope))
        assertTrue(manager.executeApproved(tool, args, token, "task", "call", scope) is com.cosmic.ai.runtime.tools.ToolResult.NeedsApproval)
    }
}
