package com.cosmic.ai.runtime.search

/** Provider-neutral source discovery contract used by COSMIC tools and agents. */
interface SearchProvider {
    val providerName: String
    suspend fun search(query: String, limit: Int = 5): Result<List<SearchResult>>
}

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String
)
