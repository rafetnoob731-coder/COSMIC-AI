package com.cosmic.ai.runtime.tools

import android.net.Uri
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.domain.provider.ToolDefinition
import com.cosmic.ai.runtime.search.BraveSearchClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class BraveSearchTool(apiKeyReader: () -> String?) : Tool {
    companion object { const val SECRET_ALIAS = "brave_search_api_key" }
    private val client = BraveSearchClient(apiKeyReader)
    override val definition = ToolDefinition(
        name = "brave_web_search",
        description = "Search the public web for current technical documentation, API references, libraries, or research. Cite returned source URLs. Use when external facts are needed.",
        parametersJsonSchema = """{"type":"object","properties":{"query":{"type":"string","description":"Focused web search query"},"count":{"type":"integer","description":"Number of results from 1 to 10"}},"required":["query"]}"""
    )

    override suspend fun execute(argsJson: String, policy: PermissionPolicy, projectUri: Uri?): ToolResult {
        if (!policy.allowNetwork) return ToolResult.Failure("Network permission denied")
        val input = try { JSONObject(argsJson) } catch (_: Exception) { return ToolResult.Failure("Invalid search arguments") }
        val query = input.optString("query").trim()
        if (query.isEmpty() || query.length > 500) return ToolResult.Failure("Search query must contain 1–500 characters")
        return withContext(Dispatchers.IO) {
            client.search(query, input.optInt("count", 5)).fold(
                onSuccess = { results ->
                    if (results.isEmpty()) ToolResult.Success("No web results found for: $query")
                    else ToolResult.Success(results.mapIndexed { i, r ->
                        "[${i + 1}] ${r.title}\n${r.url}\n${r.snippet}"
                    }.joinToString("\n\n"))
                },
                onFailure = { ToolResult.Failure(it.message ?: "Brave Search failed") }
            )
        }
    }
}
