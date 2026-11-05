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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class McpClientManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<String, McpSession>()
    private val _states = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val states: StateFlow<Map<String, McpConnectionState>> = _states.asStateFlow()
    private val _tools = MutableStateFlow<List<McpTool>>(emptyList())
    val tools: StateFlow<List<McpTool>> = _tools.asStateFlow()

    fun connect(config: McpServerConfig) {
        if (!config.enabled) return
        scope.launch {
            setState(config.id, McpConnectionState.Connecting)
            try {
                val session: McpSession = when (config.transport) {
                    McpTransport.STDIO -> StdioMcpSession(config)
                    McpTransport.HTTP -> HttpMcpSession(config)
                    McpTransport.SSE -> error("Legacy MCP SSE transport is not implemented; use Streamable HTTP")
                }
                session.initialize()
                val discovered = session.listTools()
                clients[config.id] = session
                _tools.value = _tools.value.filter { it.serverId != config.id } + discovered
                setState(config.id, McpConnectionState.Connected(discovered.size))
            } catch (e: Exception) {
                setState(config.id, McpConnectionState.Error(e.message ?: "connect failed"))
            }
        }
    }

    fun disconnect(serverId: String) {
        clients.remove(serverId)?.close()
        _tools.value = _tools.value.filter { it.serverId != serverId }
        setState(serverId, McpConnectionState.Disconnected)
    }

    suspend fun callTool(serverId: String, name: String, argumentsJson: String): Result<String> {
        val session = clients[serverId] ?: return Result.failure(IllegalStateException("Server not connected"))
        return session.callTool(name, argumentsJson)
    }

    fun allTools(): List<McpTool> = _tools.value

    private fun setState(id: String, state: McpConnectionState) {
        _states.value = _states.value.toMutableMap().apply { put(id, state) }
    }
}

interface McpSession {
    suspend fun initialize()
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, argumentsJson: String): Result<String>
    fun close()
}

class StdioMcpSession(private val config: McpServerConfig) : McpSession {
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
                    val json = JSONObject(line)
                    val id = json.optLong("id", -1)
                    if (id >= 0) pending.remove(id)?.complete(json)
                }
            } catch (_: Exception) { }
        }
        request(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "COSMIC AI").put("version", "0.1.0"))
        )
        notify("notifications/initialized", JSONObject())
    }

    override suspend fun listTools(): List<McpTool> {
        val resp = request("tools/list", JSONObject())
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

    private suspend fun request(method: String, params: JSONObject): JSONObject {
        val id = idGen.getAndIncrement()
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        withContext(Dispatchers.IO) {
            writer?.apply { write(msg.toString()); newLine(); flush() }
        }
        return withTimeout(30_000) { deferred.await() }
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

class HttpMcpSession(private val config: McpServerConfig) : McpSession {
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
                .put("clientInfo", JSONObject().put("name", "COSMIC AI").put("version", "0.1.0"))
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
