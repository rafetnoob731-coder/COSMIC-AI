# Search integration: DuckDuckGo Instant Answers

COSMIC CODEX removed its Brave-specific client, API-key setting, and search tool. The runtime uses a provider-neutral `SearchProvider` abstraction with `DuckDuckGoSearchProvider` and the `duckduckgo_instant_answer` tool.

## Integration behavior

- HTTPS GET to `https://api.duckduckgo.com/` with query parameters `q`, `format=json`, `no_html=1`, `no_redirect=1`, and `skip_disambig=1`.
- Parses only DuckDuckGo Instant Answer fields: abstract, answer, definition, direct results, and related topics.
- Keeps only HTTP(S) source URLs present in the response and labels results as DuckDuckGo Instant Answers.
- No API key is required. Network access remains behind COSMIC's existing permission policy.
- Requests have bounded query size, result count, connect/read/call timeouts, and support coroutine cancellation.
- Empty results are a valid response and are described as empty; the app does not silently fall back to another engine.

The official API describes Instant Answers, not a complete organic web-result feed: [DuckDuckGo Publisher API notes](https://github.com/duckduckgo/duckduckgo-publisher/blob/master/share/site/duckduckgo/api.tx). COSMIC therefore does not claim full web search, fetch full pages, or invent citations.

## Credential migration

The former Brave implementation looked up a dedicated encrypted credential alias. The DuckDuckGo migration no longer reads that alias. It is intentionally retained in encrypted storage to avoid silently deleting user data; users can remove it through app data reset / credential management when available. No Brave key is included in source, resources, or logs.

## Verification

`DuckDuckGoSearchProviderTest` uses MockWebServer to validate parsing, source attribution, query encoding (including Bangla), empty/oversized queries, malformed JSON, HTTP failures, unsafe links, and cancellation. These tests do not prove live DuckDuckGo availability or provide organic search results.
