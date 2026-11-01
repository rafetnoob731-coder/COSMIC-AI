// language: kotlin, file: McpModels.kt, runtime: domain, target: MCP protocol surface
package com.cosmic.ai.domain.mcp

enum class McpTransport {
    STDIO,
    SSE,
    HTTP
}

data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransport,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val env: Map<String, String> = emptyMap()
)

data class McpTool(
    val serverId: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String
)

data class McpResource(
    val serverId: String,
    val uri: String,
    val name: String,
    val mimeType: String? = null
)

sealed class McpConnectionState {
    data object Disconnected : McpConnectionState()
    data object Connecting : McpConnectionState()
    data class Connected(val toolCount: Int) : McpConnectionState()
    data class Error(val message: String) : McpConnectionState()
}
