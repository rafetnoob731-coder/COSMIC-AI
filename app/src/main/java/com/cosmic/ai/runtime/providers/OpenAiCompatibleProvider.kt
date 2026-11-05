// language: kotlin, file: OpenAiCompatibleProvider.kt, runtime: OkHttp SSE, target: OpenAI / OpenRouter / DeepSeek / custom
package com.cosmic.ai.runtime.providers

import com.cosmic.ai.domain.provider.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class OpenAiCompatibleProvider(
    private val client: OkHttpClient = defaultClient()
) : AiProvider {

    override val type: ProviderType = ProviderType.OPENAI_COMPATIBLE

    override suspend fun discoverModels(config: ProviderConfig, apiKey: String): Result<List<ProviderModel>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/models")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("Model discovery failed: HTTP ${response.code}"))
                val data = JSONObject(response.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
                val models = buildList {
                    for (index in 0 until data.length()) {
                        val item = data.optJSONObject(index) ?: continue
                        val id = item.optString("id").trim()
                        if (id.isNotEmpty()) add(ProviderModel(id = id, displayName = item.optString("name").ifBlank { id }))
                    }
                }
                Result.success(models)
            }
        } catch (error: Exception) {
            Result.failure(IOException("Model discovery failed: ${error.javaClass.simpleName}"))
        }
    }

    override suspend fun testConnection(config: ProviderConfig, apiKey: String): Result<String> {
        return try {
            val body = JSONObject().apply {
                put("model", config.defaultModel)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                put("max_tokens", 5)
            }
            val request = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success("OK ${response.code}")
            } else {
                Result.failure(IOException("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        request: ChatRequest
    ): Flow<ChatEvent> = callbackFlow {
        val payload = buildPayload(request, stream = true)
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    trySend(ChatEvent.Finished("stop"))
                    close()
                    return
                }
                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices") ?: return
                    if (choices.length() == 0) return
                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: return
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        trySend(ChatEvent.Delta(content))
                    }
                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (i in 0 until toolCalls.length()) {
                            val tc = toolCalls.getJSONObject(i)
                            val fn = tc.optJSONObject("function") ?: continue
                            trySend(
                                ChatEvent.ToolCallDelta(
                                    ToolCall(
                                        id = tc.optString("id", "call_$i"),
                                        name = fn.optString("name"),
                                        arguments = fn.optString("arguments", "{}")
                                    )
                                )
                            )
                        }
                    }
                    val usage = json.optJSONObject("usage")
                    if (usage != null) {
                        trySend(
                            ChatEvent.Usage(
                                usage.optInt("prompt_tokens", 0),
                                usage.optInt("completion_tokens", 0)
                            )
                        )
                    }
                    val finish = choice.optString("finish_reason", "")
                    if (finish.isNotEmpty() && finish != "null") {
                        trySend(ChatEvent.Finished(finish))
                    }
                } catch (_: Exception) {
                    // ignore malformed chunk
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(ChatEvent.Error(t?.message ?: "stream failure", response?.code))
                close(t)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val source = EventSources.createFactory(client).newEventSource(httpRequest, listener)
        awaitClose { source.cancel() }
    }

    override suspend fun completeChat(
        config: ProviderConfig,
        apiKey: String,
        request: ChatRequest
    ): Result<ChatMessage> = suspendCancellableCoroutine { cont ->
        val payload = buildPayload(request, stream = false)
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resume(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    cont.resume(Result.failure(IOException("HTTP ${response.code}")))
                    return
                }
                try {
                    val json = JSONObject(body)
                    val choice = json.getJSONArray("choices").getJSONObject(0)
                    val message = choice.getJSONObject("message")
                    val content = message.optString("content", "")
                    val toolCallsJson = message.optJSONArray("tool_calls")
                    val toolCalls = mutableListOf<ToolCall>()
                    if (toolCallsJson != null) {
                        for (i in 0 until toolCallsJson.length()) {
                            val tc = toolCallsJson.getJSONObject(i)
                            val fn = tc.getJSONObject("function")
                            toolCalls.add(
                                ToolCall(
                                    id = tc.optString("id"),
                                    name = fn.getString("name"),
                                    arguments = fn.optString("arguments", "{}")
                                )
                            )
                        }
                    }
                    cont.resume(
                        Result.success(
                            ChatMessage(
                                role = "assistant",
                                content = content,
                                toolCalls = toolCalls.ifEmpty { null }
                            )
                        )
                    )
                } catch (e: Exception) {
                    cont.resume(Result.failure(e))
                }
            }
        })
    }

    private fun buildPayload(request: ChatRequest, stream: Boolean): JSONObject {
        return JSONObject().apply {
            put("model", request.model)
            put("stream", stream)
            request.temperature?.let { put("temperature", it) }
            request.maxTokens?.let { put("max_tokens", it) }
            val msgs = JSONArray()
            request.messages.forEach { m ->
                val obj = JSONObject().put("role", m.role).put("content", m.content)
                m.name?.let { obj.put("name", it) }
                m.toolCallId?.let { obj.put("tool_call_id", it) }
                m.toolCalls?.let { tcs ->
                    val arr = JSONArray()
                    tcs.forEach { tc ->
                        arr.put(
                            JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", tc.name).put("arguments", tc.arguments)
                                )
                        )
                    }
                    obj.put("tool_calls", arr)
                }
                msgs.put(obj)
            }
            put("messages", msgs)
            request.tools?.let { tools ->
                val arr = JSONArray()
                tools.forEach { t ->
                    arr.put(
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", t.name)
                                    .put("description", t.description)
                                    .put("parameters", JSONObject(t.parametersJsonSchema))
                            )
                    )
                }
                put("tools", arr)
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
