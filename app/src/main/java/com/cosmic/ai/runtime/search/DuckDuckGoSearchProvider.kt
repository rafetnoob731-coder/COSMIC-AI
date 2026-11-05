package com.cosmic.ai.runtime.search

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Uses DuckDuckGo's documented Instant Answer API. This is not an organic web-results API;
 * empty results are a normal outcome and are never filled from another provider.
 */
class DuckDuckGoSearchProvider(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = "https://api.duckduckgo.com/"
) : SearchProvider {
    override val providerName: String = "DuckDuckGo Instant Answers"

    override suspend fun search(query: String, limit: Int): Result<List<SearchResult>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return Result.failure(IllegalArgumentException("Search query is empty"))
        if (normalized.length > MAX_QUERY_LENGTH) return Result.failure(IllegalArgumentException("Search query exceeds $MAX_QUERY_LENGTH characters"))
        val url = try {
            endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("q", normalized)
                .addQueryParameter("format", "json")
                .addQueryParameter("no_html", "1")
                .addQueryParameter("no_redirect", "1")
                .addQueryParameter("skip_disambig", "1")
                .build()
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("DuckDuckGo endpoint is invalid"))
        }
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build())
        return try {
            val response = awaitResponse(call)
            response.use {
                if (!it.isSuccessful) return Result.failure(IOException("DuckDuckGo Instant Answer request failed with HTTP ${it.code}"))
                val body = it.body?.string().orEmpty()
                val root = try { JSONObject(body) } catch (_: Exception) {
                    return Result.failure(IOException("DuckDuckGo returned invalid JSON"))
                }
                Result.success(parse(root, limit.coerceIn(1, MAX_RESULTS)))
            }
        } catch (error: IOException) {
            Result.failure(IOException("DuckDuckGo request failed (${error.javaClass.simpleName})"))
        }
    }

    private suspend fun awaitResponse(call: okhttp3.Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                resumeSafely(continuation, response)
            }
        })
    }

    private fun resumeSafely(continuation: CancellableContinuation<Response>, response: Response) {
        if (continuation.isActive) continuation.resume(response) else response.close()
    }

    private fun parse(root: JSONObject, limit: Int): List<SearchResult> {
        val results = LinkedHashMap<String, SearchResult>()
        fun add(title: String, url: String, snippet: String) {
            val cleanTitle = title.trim()
            val cleanUrl = safeHttpUrl(url.trim()) ?: return
            val cleanSnippet = snippet.trim()
            if (cleanTitle.isEmpty() || cleanSnippet.isEmpty()) return
            results.putIfAbsent(cleanUrl, SearchResult(cleanTitle, cleanUrl, cleanSnippet, providerName))
        }

        val heading = root.optString("Heading").trim()
        add(heading, root.optString("AbstractURL"), root.optString("AbstractText"))
        add(heading.ifBlank { root.optString("AnswerType") }, root.optString("AnswerURL"), root.optString("Answer"))
        add(root.optString("DefinitionSource").ifBlank { heading }, root.optString("DefinitionURL"), root.optString("Definition"))
        val directResults = root.optJSONArray("Results")
        if (directResults != null) for (index in 0 until directResults.length()) {
            addTopic(directResults.optJSONObject(index), results, 0)
            if (results.size >= limit) return results.values.take(limit)
        }
        val related = root.optJSONArray("RelatedTopics")
        if (related != null) for (index in 0 until related.length()) {
            addTopic(related.optJSONObject(index), results, 0)
            if (results.size >= limit) break
        }
        return results.values.take(limit)
    }

    private fun addTopic(topic: JSONObject?, results: LinkedHashMap<String, SearchResult>, depth: Int) {
        if (topic == null || depth > 3) return
        val nested = topic.optJSONArray("Topics")
        if (nested != null) {
            for (index in 0 until nested.length()) addTopic(nested.optJSONObject(index), results, depth + 1)
            return
        }
        val text = topic.optString("Text")
        val url = safeHttpUrl(topic.optString("FirstURL")) ?: return
        val title = text.substringBefore(" - ").ifBlank { text }
        results.putIfAbsent(url, SearchResult(title, url, text, providerName))
    }

    private fun safeHttpUrl(value: String): String? = try {
        value.toHttpUrl().takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()
    } catch (_: Exception) { null }

    companion object {
        private const val MAX_QUERY_LENGTH = 500
        private const val MAX_RESULTS = 20
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
