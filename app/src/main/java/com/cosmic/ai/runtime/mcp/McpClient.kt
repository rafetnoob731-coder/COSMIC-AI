// language: kotlin, file: McpClient.kt, runtime: JSON-RPC, target: MCP tools/list + tools/call
package com.cosmic.ai.runtime.mcp

import com.cosmic.ai.domain.mcp.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class McpClientManager(
    private val permissionGateway: com.cosmic.ai.security.PermissionGateway = com.cosmic.ai.security.PermissionGateway(),
    private val audit: com.cosmic.ai.data.local.db.SecurityAuditRepository? = null,
    private val approvalLifecycle: com.cosmic.ai.data.local.db.ApprovalLifecycleRepository? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, McpSession>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val states: StateFlow<Map<String, McpConnectionState>> = _states.asStateFlow()
    private val _tools = MutableStateFlow<List<McpTool>>(emptyList())
    val tools: StateFlow<List<McpTool>> = _tools.asStateFlow()
    private val _configurations = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val configurations: StateFlow<List<McpServerConfig>> = _configurations.asStateFlow()

    fun restoreConfigurations(configs: List<McpServerConfig>) { _configurations.value = configs.toList() }

    fun upsertConfiguration(config: McpServerConfig) {
        _configurations.value = _configurations.value.filterNot { it.id == config.id } + config
    }

    fun removeConfiguration(serverId: String) {
        disconnect(serverId)
        _configurations.value = _configurations.value.filterNot { it.id == serverId }
    }

    fun connect(config: McpServerConfig) {
        if (!config.enabled) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var candidate: McpSession? = null
            val self = currentCoroutineContext()[Job]
            try {
                setState(config.id, McpConnectionState.Connecting)
                val executionDescription = listOfNotNull(config.command, config.args.joinToString(" ").ifBlank { null }).joinToString(" ")
                val permissionLevel = if (config.transport == McpTransport.STDIO) {
                    com.cosmic.ai.security.PermissionLevel.EXECUTE
                } else {
                    com.cosmic.ai.security.PermissionLevel.NETWORK
                }
                val action = "mcp_server_connect:${config.id}"
                val scopeHash = launchScope(config)
                val safeExecutionDescription = com.cosmic.ai.security.SensitiveTextRedactor.redact(executionDescription).take(500)
                val permissionDetail = if (config.transport == McpTransport.STDIO) safeExecutionDescription else scopeHash
                val permission = permissionGateway.check(permissionLevel, action, permissionDetail)
                if (permission !is com.cosmic.ai.security.PermissionDecision.Allow) {
                    val reason = when (permission) {
                        is com.cosmic.ai.security.PermissionDecision.Deny -> permission.reason
                        is com.cosmic.ai.security.PermissionDecision.NeedsApproval -> "MCP server connection requires approval; no server process or request was started"
                        else -> "MCP server connection was blocked"
                    }
                    val approval = if (permission is com.cosmic.ai.security.PermissionDecision.NeedsApproval &&
                        config.transport == McpTransport.STDIO) {
                        try { approvalLifecycle?.createMcpLaunchRequest(config.id, config.name, scopeHash) }
                        catch (error: Exception) {
                            android.util.Log.e("CosmicMcp", "STDIO launch approval could not be persisted (${error.javaClass.simpleName})")
                            null
                        }
                    } else null
                    val visibleReason = if (approval != null) {
                        "Approval required. Review request ${approval.id} in Security Center, then continue. No process was started."
                    } else reason
                    audit?.record(null, approval?.taskId, "mcp", "mcp_server_connect", "connect", scopeHash,
                        if (approval != null) "NEEDS_APPROVAL" else if (permission is com.cosmic.ai.security.PermissionDecision.NeedsApproval) "NEEDS_APPROVAL" else "DENIED",
                        "Not connected", visibleReason)
                    setState(config.id, McpConnectionState.Error(visibleReason))
                    return@launch
                }
                audit?.record(null, null, "mcp", "mcp_server_connect", "connect", scopeHash, "AUTHORIZED", "Connection starting")
                val session = when (config.transport) {
                    McpTransport.STDIO -> StdioMcpSession(config)
                    McpTransport.HTTP -> HttpMcpSession(config)
                    McpTransport.SSE -> error("Legacy MCP SSE transport is not implemented; use Streamable HTTP")
                }
                candidate = session
                session.initialize()
                val discovered = session.listTools()
                ensureActive()
                publishConnectedSession(config.id, session, discovered)
                candidate = null // ownership moved into the connected-client map
                setState(config.id, McpConnectionState.Connected(discovered.size))
                runCatching {
                    audit?.record(null, null, "mcp", "mcp_server_connect", "connect", scopeHash, "CONNECTED", "${discovered.size} tools discovered")
                }.onFailure { auditError ->
                    android.util.Log.e("CosmicMcp", "Connected MCP audit result could not be persisted (${auditError.javaClass.simpleName})")
                }
            } catch (cancelled: CancellationException) {
                candidate?.close()
                throw cancelled
            } catch (error: Exception) {
                candidate?.close()
                val safeMessage = com.cosmic.ai.security.SensitiveTextRedactor.redact(error.message ?: "connect failed").take(300)
                runCatching {
                    audit?.record(null, null, "mcp", "mcp_server_connect", "connect", config.id, "CONNECT_FAILED", "Connection failed", error.javaClass.simpleName)
                }.onFailure { auditError ->
                    android.util.Log.e("CosmicMcp", "Connection failure audit could not be persisted (${auditError.javaClass.simpleName})")
                }
                setState(config.id, McpConnectionState.Error(safeMessage))
            } finally {
                if (self != null) connectionJobs.remove(config.id, self)
            }
        }
        connectionJobs.put(config.id, job)?.cancel()
        job.start()
    }

    fun disconnect(serverId: String) {
        connectionJobs.remove(serverId)?.cancel(CancellationException("MCP connection cancelled by user"))
        clients.remove(serverId)?.close()
        _tools.value = _tools.value.filter { it.serverId != serverId }
        setState(serverId, McpConnectionState.Disconnected)
    }

    /** Starts a STDIO server only after validating the persisted, one-use, task-bound approval. */
    suspend fun connectApproved(
        config: McpServerConfig,
        approval: com.cosmic.ai.security.ApprovalAuthorization,
        taskId: String,
        callId: String,
        expectedScopeHash: String
    ): com.cosmic.ai.runtime.tools.ToolResult {
        if (config.transport != McpTransport.STDIO) return com.cosmic.ai.runtime.tools.ToolResult.Failure("Only STDIO launch approvals use this operation")
        val currentScope = launchScope(config)
        if (currentScope != expectedScopeHash) return com.cosmic.ai.runtime.tools.ToolResult.Failure("MCP server configuration changed after approval")
        val commandLine = listOfNotNull(config.command, config.args.joinToString(" ").ifBlank { null }).joinToString(" ")
        val safeCommand = com.cosmic.ai.security.SensitiveTextRedactor.redact(commandLine).take(500)
        val action = "mcp_server_connect:${config.id}"
        val decision = permissionGateway.check(
            com.cosmic.ai.security.PermissionLevel.EXECUTE, action, safeCommand,
            approval = approval, expectedTaskId = taskId, expectedCallId = callId,
            approvalScopeHash = currentScope
        )
        if (decision !is com.cosmic.ai.security.PermissionDecision.Allow) {
            val message = when (decision) {
                is com.cosmic.ai.security.PermissionDecision.Deny -> decision.reason
                is com.cosmic.ai.security.PermissionDecision.NeedsApproval -> "MCP launch authorization is no longer valid"
                else -> "MCP launch was blocked"
            }
            return com.cosmic.ai.runtime.tools.ToolResult.Failure(message)
        }
        if (isConnected(McpTool(config.id, "", "", "{}"))) {
            return com.cosmic.ai.runtime.tools.ToolResult.Success("MCP server is already connected")
        }
        val self = currentCoroutineContext()[Job]
        if (self != null) connectionJobs.put(config.id, self)?.cancel()
        var candidate: McpSession? = null
        try {
            setState(config.id, McpConnectionState.Connecting)
            audit?.record(null, taskId, "mcp", "mcp_server_connect", "connect", currentScope, "EXECUTION_STARTED")
            val session = StdioMcpSession(config)
            candidate = session
            session.initialize()
            val discovered = session.listTools()
            currentCoroutineContext().ensureActive()
            publishConnectedSession(config.id, session, discovered)
            candidate = null
            setState(config.id, McpConnectionState.Connected(discovered.size))
            runCatching { audit?.record(null, taskId, "mcp", "mcp_server_connect", "connect", currentScope, "CONNECTED", "${discovered.size} tools discovered") }
            return com.cosmic.ai.runtime.tools.ToolResult.Success("Connected; discovered ${discovered.size} tools")
        } catch (cancelled: CancellationException) {
            candidate?.close()
            throw cancelled
        } catch (error: Exception) {
            candidate?.close()
            val safeMessage = com.cosmic.ai.security.SensitiveTextRedactor.redact(error.message ?: "MCP server launch failed").take(300)
            setState(config.id, McpConnectionState.Error(safeMessage))
            runCatching { audit?.record(null, taskId, "mcp", "mcp_server_connect", "connect", currentScope, "CONNECT_FAILED", "Connection failed", error.javaClass.simpleName) }
            // Once process initialization was attempted, startup side effects cannot be ruled out.
            return com.cosmic.ai.runtime.tools.ToolResult.Unknown("MCP launch outcome is uncertain: ${error.javaClass.simpleName}: $safeMessage")
        } finally {
            if (self != null) connectionJobs.remove(config.id, self)
        }
    }

    /** Digest includes all settings that can affect the child process or connection. */
    fun launchScope(config: McpServerConfig): String {
        val canonical = JSONObject()
            .put("id", config.id).put("name", config.name).put("transport", config.transport.name)
            .put("command", config.command ?: JSONObject.NULL)
            .put("args", JSONArray().apply { config.args.forEach { put(it) } })
            .put("url", config.url ?: JSONObject.NULL).put("enabled", config.enabled)
            .put("headers", JSONObject().also { obj -> config.headers.toSortedMap().forEach { (k, v) -> obj.put(k, v) } })
            .put("env", JSONObject().also { obj -> config.env.toSortedMap().forEach { (k, v) -> obj.put(k, v) } })
        return sha256(canonical.toString())
    }

    suspend fun callTool(serverId: String, name: String, argumentsJson: String): Result<String> {
        val session = clients[serverId] ?: return Result.failure(IllegalStateException("Server not connected"))
        val scopeHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest((serverId + "\u0000" + name + "\u0000" + argumentsJson).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val requestId = java.util.UUID.randomUUID().toString()
        audit?.record(requestId, null, "mcp", "mcp:$serverId:$name", "call", scopeHash, "REQUESTED", "MCP tool invocation requested")
        val decision = authorizeToolCall(serverId, name, argumentsJson)
        if (decision !is com.cosmic.ai.security.PermissionDecision.Allow) {
            val reason = when (decision) {
                is com.cosmic.ai.security.PermissionDecision.Deny -> decision.reason
                is com.cosmic.ai.security.PermissionDecision.NeedsApproval -> "MCP tool requires an approval flow that is not available for this call"
                else -> "MCP tool execution denied"
            }
            audit?.record(requestId, null, "mcp", "mcp:$serverId:$name", "call", scopeHash,
                if (decision is com.cosmic.ai.security.PermissionDecision.NeedsApproval) "NEEDS_APPROVAL" else "DENIED", "Execution blocked", reason)
            return Result.failure(SecurityException(reason))
        }
        audit?.record(requestId, null, "mcp", "mcp:$serverId:$name", "call", scopeHash, "EXECUTION_STARTED")
        val result = try { session.callTool(name, argumentsJson) }
        catch (cancelled: CancellationException) {
            audit?.record(requestId, null, "mcp", "mcp:$serverId:$name", "call", scopeHash, "EXECUTION_UNKNOWN", "Cancellation after dispatch; outcome may be unknown")
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        audit?.record(requestId, null, "mcp", "mcp:$serverId:$name", "call", scopeHash,
            if (result.isSuccess) "EXECUTION_COMPLETED" else "EXECUTION_UNKNOWN",
            if (result.isSuccess) "MCP tool completed" else "MCP call failed after dispatch; outcome may be unknown",
            result.exceptionOrNull()?.javaClass?.simpleName)
        return result
    }

    fun authorizeToolCall(serverId: String, name: String, argumentsJson: String): com.cosmic.ai.security.PermissionDecision {
        val scopeHash = sha256(serverId + "\u0000" + name + "\u0000" + argumentsJson)
        return permissionGateway.check(
            com.cosmic.ai.security.PermissionLevel.ADMIN,
            "mcp:$serverId:$name",
            scopeHash
        )
    }

    fun allTools(): List<McpTool> = _tools.value

    internal fun publishConnectedSession(serverId: String, session: McpSession, discovered: List<McpTool>) {
        clients.put(serverId, session)?.close()
        _tools.value = _tools.value.filter { it.serverId != serverId } + discovered
        setState(serverId, McpConnectionState.Connected(discovered.size))
    }

    /** Stable namespaced IDs prevent MCP tool name collisions with local tools and other servers. */
    fun agentToolName(tool: McpTool): String {
        val identity = sha256(tool.serverId + "\u0000" + tool.name).take(10)
        return "mcp_${slug(tool.serverId)}_${slug(tool.name)}_$identity"
    }

    fun resolveAgentTool(agentName: String): McpTool? = _tools.value.firstOrNull { agentToolName(it) == agentName }

    fun isAgentToolName(agentName: String): Boolean = agentName.startsWith("mcp_")

    fun isConnected(tool: McpTool): Boolean = clients.containsKey(tool.serverId)

    private fun slug(value: String): String = value.lowercase().map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
        .joinToString("").trim('_').take(14).ifBlank { "server" }

    fun approvalScope(tool: McpTool, argumentsJson: String): String = sha256(tool.serverId + "\u0000" + tool.name + "\u0000" + argumentsJson)

    suspend fun executeApproved(
        tool: McpTool, argumentsJson: String,
        approval: com.cosmic.ai.security.ApprovalAuthorization?, taskId: String, callId: String,
        scopeHash: String,
        onAuthorized: suspend () -> Unit = {}
    ): com.cosmic.ai.runtime.tools.ToolResult {
        val action = "mcp:${tool.serverId}:${tool.name}"
        val decision = permissionGateway.check(
            com.cosmic.ai.security.PermissionLevel.ADMIN, action, scopeHash,
            approval = approval, expectedTaskId = taskId, expectedCallId = callId,
            approvalScopeHash = scopeHash
        )
        when (decision) {
            com.cosmic.ai.security.PermissionDecision.Allow -> Unit
            is com.cosmic.ai.security.PermissionDecision.Deny -> return com.cosmic.ai.runtime.tools.ToolResult.Failure(decision.reason)
            is com.cosmic.ai.security.PermissionDecision.NeedsApproval -> return com.cosmic.ai.runtime.tools.ToolResult.NeedsApproval(decision.reason, agentToolName(tool), argumentsJson)
        }
        onAuthorized()
        val session = clients[tool.serverId] ?: return com.cosmic.ai.runtime.tools.ToolResult.Failure("MCP server is not connected")
        return try {
            session.callTool(tool.name, argumentsJson).fold(
                onSuccess = { com.cosmic.ai.runtime.tools.ToolResult.Success(it) },
                onFailure = { com.cosmic.ai.runtime.tools.ToolResult.Unknown("MCP call failed after dispatch; outcome may be unknown: ${it.javaClass.simpleName}") }
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { com.cosmic.ai.runtime.tools.ToolResult.Unknown("MCP call outcome is unknown: ${error.javaClass.simpleName}") }
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun setState(id: String, state: McpConnectionState) {
        _states.value = _states.value.toMutableMap().apply { put(id, state) }
    }
}

internal interface McpSession {
    suspend fun initialize()
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, argumentsJson: String): Result<String>
    fun close()
}

internal class StdioMcpSession(private val config: McpServerConfig) : McpSession {
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val idGen = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var stderrJob: Job? = null

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        val cmd = mutableListOf(config.command ?: error("STDIO requires command"))
        cmd.addAll(config.args)
        val pb = ProcessBuilder(cmd)
        config.env.forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(false)
        process = pb.start()
        writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
        reader = BufferedReader(InputStreamReader(process!!.inputStream))
        stderrJob = processScope.launch {
            process?.errorStream?.bufferedReader()?.use { stream -> while (stream.readLine() != null) Unit }
        }
        readerJob = processScope.launch {
            try {
                while (true) {
                    val line = reader?.readLine() ?: break
                    if (line.isBlank()) continue
                    try {
                        val json = JSONObject(line)
                        val id = json.optLong("id", -1)
                        if (id >= 0) pending.remove(id)?.complete(json)
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) {
            } finally {
                pending.values.forEach { it.completeExceptionally(java.io.IOException("MCP session closed")) }
                pending.clear()
            }
        }
        request(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "COSMIC CODEX").put("version", "0.1.0"))
        )
        notify("notifications/initialized", JSONObject())
    }

    override suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        val resp = request("tools/list", JSONObject())
        val result = resp.optJSONObject("result") ?: return@withContext emptyList()
        val arr = result.optJSONArray("tools") ?: return@withContext emptyList()
        val out = mutableListOf<McpTool>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            out.add(
                McpTool(
                    serverId = config.id,
                    name = t.getString("name"),
                    description = t.optString("description", ""),
                    inputSchemaJson = t.optJSONObject("inputSchema")?.toString() ?: "{}"
                )
            )
        }
        out
    }

    override suspend fun callTool(name: String, argumentsJson: String): Result<String> {
        return try {
            val args = try {
                JSONObject(argumentsJson)
            } catch (_: Exception) {
                return Result.failure(IllegalArgumentException("MCP tool arguments must be a JSON object"))
            }
            val resp = request("tools/call", JSONObject().put("name", name).put("arguments", args))
            if (resp.has("error")) {
                Result.failure(Exception(resp.getJSONObject("error").optString("message", "tool error")))
            } else {
                val content = resp.optJSONObject("result")?.optJSONArray("content")
                val text = buildString {
                    if (content != null) for (i in 0 until content.length()) {
                        val c = content.getJSONObject(i)
                        if (c.optString("type") == "text") append(c.optString("text"))
                    }
                }
                Result.success(text.ifBlank { resp.optJSONObject("result")?.toString() ?: "{}" })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun request(method: String, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = idGen.getAndIncrement()
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        writer?.apply { write(msg.toString()); newLine(); flush() }
        withTimeout(30_000) { deferred.await() }
    }

    private fun notify(method: String, params: JSONObject) {
        val msg = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        writer?.apply { write(msg.toString()); newLine(); flush() }
    }

    override fun close() {
        readerJob?.cancel()
        stderrJob?.cancel()
        processScope.cancel()
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        process?.destroy()
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}

internal class HttpMcpSession(private val config: McpServerConfig) : McpSession {
    private val endpoint = (config.url ?: error("HTTP transport requires url")).toHttpUrl()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val idGen = AtomicLong(1)

    init {
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty()) { "MCP URL must not embed credentials" }
        val isLoopback = endpoint.host in setOf("localhost", "127.0.0.1", "::1")
        require(endpoint.isHttps || (endpoint.scheme == "http" && isLoopback)) {
            "Remote MCP connections require HTTPS; cleartext is limited to localhost"
        }
    }

    override suspend fun initialize() {
        rpc(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "COSMIC CODEX").put("version", "0.1.0"))
        )
    }

    override suspend fun listTools(): List<McpTool> {
        val resp = rpc("tools/list", JSONObject())
        val result = resp.optJSONObject("result") ?: return emptyList()
        val arr = result.optJSONArray("tools") ?: return emptyList()
        val out = mutableListOf<McpTool>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            out.add(
                McpTool(
                    serverId = config.id,
                    name = t.getString("name"),
                    description = t.optString("description", ""),
                    inputSchemaJson = t.optJSONObject("inputSchema")?.toString() ?: "{}"
                )
            )
        }
        return out
    }

    override suspend fun callTool(name: String, argumentsJson: String): Result<String> {
        return try {
            val args = try {
                JSONObject(argumentsJson)
            } catch (_: Exception) {
                return Result.failure(IllegalArgumentException("MCP tool arguments must be a JSON object"))
            }
            val resp = rpc("tools/call", JSONObject().put("name", name).put("arguments", args))
            if (resp.has("error")) {
                Result.failure(Exception(resp.getJSONObject("error").optString("message")))
            } else {
                val result = resp.optJSONObject("result") ?: JSONObject()
                val content = result.optJSONArray("content")
                val text = buildString {
                    if (content != null) for (index in 0 until content.length()) {
                        val block = content.optJSONObject(index) ?: continue
                        if (block.optString("type") == "text") append(block.optString("text"))
                    }
                }
                Result.success(text.ifBlank { result.toString() })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun rpc(method: String, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = idGen.getAndIncrement()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
        val builder = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
        config.headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("MCP HTTP request failed: HTTP ${response.code}")
            JSONObject(text)
        }
    }

    override fun close() { }
}
