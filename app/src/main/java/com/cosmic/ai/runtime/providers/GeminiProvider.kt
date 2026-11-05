// language: kotlin, file: GeminiProvider.kt, runtime: OkHttp, target: Google Generative Language API
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class GeminiProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
) : AiProvider {

    override val type: ProviderType = ProviderType.GEMINI

    private fun endpoint(config: ProviderConfig, model: String, stream: Boolean): String {
        val base = config.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        val action = if (stream) "streamGenerateContent" else "generateContent"
        return "$base/models/$model:$action"
    }

    override suspend fun discoverModels(config: ProviderConfig, apiKey: String): Result<List<ProviderModel>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(config.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" } + "/models")
                .header("x-goog-api-key", apiKey)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("Model discovery failed: HTTP ${response.code}"))
                val data = JSONObject(response.body?.string().orEmpty()).optJSONArray("models") ?: JSONArray()
                val models = buildList {
                    for (index in 0 until data.length()) {
                        val item = data.optJSONObject(index) ?: continue
                        val methods = item.optJSONArray("supportedGenerationMethods")
                        if (methods != null && (0 until methods.length()).none { methods.optString(it) == "generateContent" }) continue
                        val resourceName = item.optString("name").trim()
                        val id = resourceName.removePrefix("models/")
                        if (id.isNotEmpty()) add(ProviderModel(id, item.optString("displayName").ifBlank { id }))
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
            val model = config.defaultModel.ifBlank { "gemini-1.5-flash" }
            val body = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "parts", JSONArray().put(JSONObject().put("text", "ping"))
                    )
                )
            )
            val req = Request.Builder()
                .url(endpoint(config, model, false))
                .header("x-goog-api-key", apiKey)
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
        val payload = buildGeminiPayload(request)
        val url = endpoint(config, request.model, true) + "?alt=sse"
        val httpReq = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
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
                if (data.isEmpty()) continue
                try {
                    val json = JSONObject(data)
                    val candidates = json.optJSONArray("candidates") ?: continue
                    if (candidates.length() == 0) continue
                    val content = candidates.getJSONObject(0).optJSONObject("content")
                    val parts = content?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val text = parts.getJSONObject(i).optString("text", "")
                        if (text.isNotEmpty()) trySend(ChatEvent.Delta(text))
                    }
                    val finish = candidates.getJSONObject(0).optString("finishReason", "")
                    if (finish.isNotEmpty()) trySend(ChatEvent.Finished(finish))
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
            val payload = buildGeminiPayload(request)
            val url = endpoint(config, request.model, false)
            val httpReq = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
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
                        val text = json.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .optString("text", "")
                        cont.resume(Result.success(ChatMessage("assistant", text)))
                    } catch (e: Exception) {
                        cont.resume(Result.failure(e))
                    }
                }
            })
        }

    private fun buildGeminiPayload(request: ChatRequest): JSONObject {
        return JSONObject().apply {
            val contents = JSONArray()
            request.messages.filter { it.role != "system" }.forEach { m ->
                val role = if (m.role == "assistant") "model" else "user"
                contents.put(
                    JSONObject()
                        .put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", m.content)))
                )
            }
            put("contents", contents)
            val system = request.messages.firstOrNull { it.role == "system" }?.content
            if (!system.isNullOrBlank()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            }
            request.temperature?.let {
                put("generationConfig", JSONObject().put("temperature", it).put("maxOutputTokens", request.maxTokens ?: 8192))
            }
            // function declarations for tools
            request.tools?.let { tools ->
                val decls = JSONArray()
                tools.forEach { t ->
                    decls.put(
                        JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", JSONObject(t.parametersJsonSchema))
                    )
                }
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
            }
        }
    }
}
