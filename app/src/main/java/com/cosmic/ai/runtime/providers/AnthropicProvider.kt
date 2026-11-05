// language: kotlin, file: AnthropicProvider.kt, runtime: OkHttp, target: Anthropic Messages API
package com.cosmic.ai.runtime.providers

import com.cosmic.ai.domain.provider.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class AnthropicProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
) : AiProvider {

    override val type: ProviderType = ProviderType.ANTHROPIC

    override suspend fun discoverModels(config: ProviderConfig, apiKey: String): Result<List<ProviderModel>> = withContext(Dispatchers.IO) {
        try {
            val found = linkedMapOf<String, ProviderModel>()
            var afterId: String? = null
            for (page in 0 until 20) {
                val url = (config.baseUrl.trimEnd('/') + "/v1/models").toHttpUrl().newBuilder()
                    .addQueryParameter("limit", "1000").apply { afterId?.let { addQueryParameter("after_id", it) } }.build()
                val request = Request.Builder().url(url)
                    .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
                    .header("Accept", "application/json").get().build()
                val root = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext Result.failure(IOException("Model discovery failed: HTTP ${response.code}"))
                    JSONObject(response.body?.string().orEmpty())
                }
                val data = root.optJSONArray("data") ?: JSONArray()
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isNotEmpty()) found.putIfAbsent(id, ProviderModel(id, item.optString("display_name").ifBlank { id }))
                }
                if (!root.optBoolean("has_more")) break
                val next = root.optString("last_id").ifBlank { data.optJSONObject(data.length() - 1)?.optString("id").orEmpty() }
                if (next.isBlank() || next == afterId) break
                afterId = next
            }
            Result.success(found.values.toList())
        } catch (error: Exception) {
            Result.failure(IOException("Model discovery failed: ${error.javaClass.simpleName}"))
        }
    }

    override suspend fun testConnection(config: ProviderConfig, apiKey: String): Result<String> {
        return try {
            val body = JSONObject().apply {
                put("model", config.defaultModel.ifBlank { "claude-3-5-sonnet-20241022" })
                put("max_tokens", 8)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            }
            val req = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) Result.success("OK ${resp.code}")
            else Result.failure(IOException("HTTP ${resp.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun streamChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val payload = buildAnthropicPayload(request, stream = true)
        val httpReq = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(httpReq)
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                trySend(ChatEvent.Error("HTTP ${response.code}", response.code))
                close()
                return@callbackFlow
            }
            val reader = BufferedReader(response.body!!.charStream())
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                if (!l.startsWith("data:")) continue
                val data = l.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    trySend(ChatEvent.Finished("end_turn"))
                    break
                }
                try {
                    val json = JSONObject(data)
                    when (json.optString("type")) {
                        "content_block_delta" -> {
                            val delta = json.optJSONObject("delta")
                            val text = delta?.optString("text", "") ?: ""
                            if (text.isNotEmpty()) trySend(ChatEvent.Delta(text))
                        }
                        "message_delta" -> {
                            val delta = json.optJSONObject("delta")
                            val stop = delta?.optString("stop_reason")
                            if (!stop.isNullOrBlank()) trySend(ChatEvent.Finished(stop))
                        }
                        "error" -> trySend(ChatEvent.Error(json.optJSONObject("error")?.optString("message") ?: "error"))
                    }
                } catch (_: Exception) { }
            }
            close()
        } catch (e: Exception) {
            trySend(ChatEvent.Error(e.message ?: "stream fail"))
            close(e)
        }
        awaitClose { call.cancel() }
    }

    override suspend fun completeChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Result<ChatMessage> =
        suspendCancellableCoroutine { cont ->
            val payload = buildAnthropicPayload(request, stream = false)
            val httpReq = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(httpReq).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resume(Result.failure(e))
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        cont.resume(Result.failure(IOException("HTTP ${response.code}")))
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        val contentArr = json.optJSONArray("content") ?: JSONArray()
                        val text = buildString {
                            for (i in 0 until contentArr.length()) {
                                val block = contentArr.getJSONObject(i)
                                if (block.optString("type") == "text") append(block.optString("text"))
                            }
                        }
                        cont.resume(Result.success(ChatMessage("assistant", text)))
                    } catch (e: Exception) {
                        cont.resume(Result.failure(e))
                    }
                }
            })
        }

    private fun buildAnthropicPayload(request: ChatRequest, stream: Boolean): JSONObject {
        return JSONObject().apply {
            put("model", request.model)
            put("max_tokens", request.maxTokens ?: 4096)
            put("stream", stream)
            request.temperature?.let { put("temperature", it) }
            val msgs = JSONArray()
            var systemText: String? = null
            request.messages.forEach { m ->
                when (m.role) {
                    "system" -> systemText = (systemText ?: "") + m.content
                    else -> {
                        // Anthropic only allows user/assistant in messages; map tool → user
                        val role = if (m.role == "tool") "user" else m.role
                        msgs.put(JSONObject().put("role", role).put("content", m.content))
                    }
                }
            }
            put("messages", msgs)
            systemText?.let { put("system", it) }
            // tools (Anthropic tool format)
            request.tools?.let { tools ->
                val arr = JSONArray()
                tools.forEach { t ->
                    arr.put(
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("input_schema", JSONObject(t.parametersJsonSchema))
                    )
                }
                put("tools", arr)
            }
        }
    }
}
