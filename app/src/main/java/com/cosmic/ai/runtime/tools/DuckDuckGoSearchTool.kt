package com.cosmic.ai.runtime.tools

import android.net.Uri
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.domain.provider.ToolDefinition
import com.cosmic.ai.runtime.search.DuckDuckGoSearchProvider
import com.cosmic.ai.runtime.search.SearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class DuckDuckGoSearchTool(
    private val searchProvider: SearchProvider = DuckDuckGoSearchProvider()
) : Tool {
    override val definition = ToolDefinition(
        name = "duckduckgo_instant_answer",
        description = "Query DuckDuckGo's documented Instant Answer API for available topic summaries, definitions, and related-topic citations. This is not a full organic web search API; it may return no results.",
        parametersJsonSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Focused question or technical search phrase"},"count":{"type":"integer","description":"Maximum number of instant-answer items, from 1 to 20"}},"required":["query"]}"""
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowNetwork) return ToolResult.Failure("Network permission denied")
        val args = try { JSONObject(argsJson) } catch (_: Exception) {
            return ToolResult.Failure("Invalid DuckDuckGo search arguments")
        }
        val query = args.optString("query").trim()
        if (query.isEmpty() || query.length > 500) return ToolResult.Failure("Search query must contain 1–500 characters")
        return withContext(Dispatchers.IO) {
            searchProvider.search(query, args.optInt("count", 5)).fold(
                onSuccess = { hits ->
                    if (hits.isEmpty()) ToolResult.Success("DuckDuckGo Instant Answers returned no result for this query. It does not expose full organic search listings.")
                    else ToolResult.Success("DuckDuckGo Instant Answer results (not a complete web search):\n\n" + hits.mapIndexed { index, hit ->
                        "[${index + 1}] ${hit.title}\n${hit.url}\n${hit.snippet}"
                    }.joinToString("\n\n"))
                },
                onFailure = { ToolResult.Failure(it.message ?: "DuckDuckGo request failed") }
            )
        }
    }
}
