package com.cosmic.ai.runtime.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Brave is an independent research service and never participates in model credential routing. */
class BraveSearchClient(
    private val apiKeyReader: () -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = "https://api.search.brave.com/res/v1/web/search"
) {
    suspend fun search(query: String, count: Int = 5): Result<List<BraveSearchResult>> = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Search query is empty"))
        val apiKey = apiKeyReader()?.takeIf(String::isNotBlank)
            ?: return@withContext Result.failure(IllegalStateException("Brave Search is not configured. Add a Brave API key in Settings → API Credentials."))
        try {
            val url = endpoint.toHttpUrl().newBuilder()
                .addQueryParameter("q", normalized)
                .addQueryParameter("count", count.coerceIn(1, 20).toString())
                .build()
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("Brave Search failed with HTTP ${response.code}"))
                val root = JSONObject(response.body?.string().orEmpty())
                val results = root.optJSONObject("web")?.optJSONArray("results")
                val parsed = buildList {
                    if (results != null) for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val title = item.optString("title").trim()
                        val target = item.optString("url").trim()
                        val snippet = item.optString("description").trim()
                        if (title.isNotEmpty() && isHttpUrl(target)) add(BraveSearchResult(title, target, snippet))
                    }
                }
                Result.success(parsed)
            }
        } catch (error: Exception) {
            Result.failure(IOException("Brave Search request failed (${error.javaClass.simpleName})"))
        }
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val url = value.toHttpUrl()
        url.scheme == "https" || url.scheme == "http"
    }.getOrDefault(false)

    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).build()
    }
}

data class BraveSearchResult(val title: String, val url: String, val snippet: String)
