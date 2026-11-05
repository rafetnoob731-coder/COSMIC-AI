# Provider and MCP Resources

COSMIC AI is designed to connect to provider APIs directly or through compatible gateways. Provider catalogs, model names, API capabilities, pricing, and access policies change over time. Always confirm current details with the service before configuring it.

## Provider connection options

### Distinct API adapters in this project

| API family | COSMIC adapter | Official documentation |
| --- | --- | --- |
| OpenAI Chat Completions compatible | OpenAI-compatible adapter | [OpenAI API](https://platform.openai.com/docs/api-reference/chat) |
| Anthropic Messages | Anthropic adapter | [Anthropic API](https://docs.anthropic.com/en/api/messages) |
| Google Gemini | Gemini adapter | [Gemini API](https://ai.google.dev/api) |

### Existing presets and compatible endpoint patterns

These are configuration starting points. A preset does not guarantee every model capability or every vendor-specific API feature.

| Service | API style | Endpoint / documentation |
| --- | --- | --- |
| OpenAI | OpenAI-compatible | [API docs](https://platform.openai.com/docs/api-reference) |
| Anthropic | Native Messages | [API docs](https://docs.anthropic.com/en/api/messages) |
| Google Gemini | Native Gemini | [API docs](https://ai.google.dev/api) |
| OpenRouter | OpenAI-compatible gateway | [API docs](https://openrouter.ai/docs/api-reference/overview) |
| DeepSeek | OpenAI-compatible | [API docs](https://api-docs.deepseek.com/) |
| Groq | OpenAI-compatible | [API docs](https://console.groq.com/docs/openai) |
| Together AI | OpenAI-compatible | [API docs](https://docs.together.ai/docs/openai-api-compatibility) |
| Fireworks AI | OpenAI-compatible | [API docs](https://docs.fireworks.ai/api-reference/post-chatcompletions) |
| Cerebras | OpenAI-compatible | [API docs](https://inference-docs.cerebras.ai/) |
| Mistral | OpenAI-compatible | [API docs](https://docs.mistral.ai/api/) |
| xAI | OpenAI-compatible | [API docs](https://docs.x.ai/docs) |
| Perplexity | OpenAI-compatible API pattern | [API docs](https://docs.perplexity.ai/) |
| NVIDIA NIM | OpenAI-compatible API pattern | [API docs](https://docs.api.nvidia.com/nim/reference) |
| DeepInfra | OpenAI-compatible | [API docs](https://deepinfra.com/docs) |
| SambaNova | OpenAI-compatible | [API docs](https://docs.sambanova.ai/) |
| LiteLLM Proxy | OpenAI-compatible gateway | [Provider documentation](https://docs.litellm.ai/docs/providers) |
| Ollama | OpenAI-compatible local API | [OpenAI compatibility](https://github.com/ollama/ollama/blob/main/docs/openai.md) |
| LocalAI | OpenAI-compatible local API | [API documentation](https://localai.io/api/) |

For services not listed, use **Custom OpenAI-compatible** only when the service documents compatible Chat Completions requests. Enter the provider's documented base URL, model ID, and key. Do not assume compatibility from a similar URL or branding.

### Broad model/provider catalogs

An aggregator can expose a large and changing catalog through one configured endpoint. For example, OpenRouter currently describes one OpenAI-compatible API spanning hundreds of models and dozens of upstream providers; its live catalog and provider list are authoritative for current availability. This is gateway access, not a separate native COSMIC adapter for every upstream company. Model/tool capabilities, data handling, and routing may differ by selected upstream provider.

- [OpenRouter models endpoint](https://openrouter.ai/docs/api/api-reference/models/get-models)
- [OpenRouter provider directory](https://openrouter.ai/providers)
- [OpenRouter API overview](https://openrouter.ai/docs/api-reference/overview)
- [LiteLLM supported providers](https://docs.litellm.ai/docs/providers)

The app currently offers presets for OpenAI, Anthropic, Gemini, OpenRouter, DeepSeek, Groq, Together AI, Fireworks AI, Cerebras, Mistral, xAI, Perplexity, NVIDIA NIM, DeepInfra, SambaNova, LiteLLM Proxy, Ollama, LocalAI, and a custom endpoint. Some share the OpenAI-compatible adapter. The configuration UI does not dynamically import every vendor from external catalogs. A reliable 100+ connection directory requires an endpoint catalog, per-provider capability metadata, model discovery, and protocol-specific tests. This resource pack does not label that unfinished directory as implemented.

### API key and network guidance

- Create credentials only through the selected provider's official account/settings pages.
- Store a key only in COSMIC AI's encrypted provider configuration. Do not put keys in chat, project files, screenshots, issue reports, or logs.
- Prefer HTTPS. For local inference, configure a reachable local service intentionally; Android emulator `localhost` refers to the emulator itself, not automatically to a computer host.
- Do not expose an unauthenticated inference endpoint to a public network.
- Review provider retention, region, and billing terms before sending private source code.

## Model Context Protocol (MCP)

MCP is a protocol and ecosystem, not a single vendor directory. Any server can expose tools or resources, but connecting it does not mean the client implements every optional MCP capability.

### Protocol references

- [MCP specification](https://modelcontextprotocol.io/specification/)
- [MCP documentation](https://modelcontextprotocol.io/docs/)
- [MCP server examples](https://github.com/modelcontextprotocol/servers)
- [MCP registry](https://registry.modelcontextprotocol.io/)

The protocol defines local stdio and HTTP transports; Streamable HTTP is the modern HTTP transport, and legacy HTTP+SSE exists for compatibility. Check the current specification and each server's installation instructions.

### Current COSMIC Android support

The repository contains JSON-RPC client scaffolding for stdio and HTTP/SSE configuration, tool listing, and tool calls. The Android app does not include a bundled Node/Python runtime or an MCP server directory. A server command such as `npx` works only if that executable and package runtime are actually available in the Android app environment. HTTP connection behavior and authentication/session features may not cover every current MCP server. Treat these paths as limited/experimental until verified against the target server.

### Safe connection checklist

1. Obtain the server URL or launch command from its maintainers' documentation.
2. Review the source, permissions, data access, and network destinations.
3. Prefer authenticated HTTPS for remote servers.
4. Grant only the headers and credentials the server needs; never reuse provider API keys as MCP credentials.
5. Inspect discovered tool names and descriptions before enabling them for an agent.
6. Require approval for operations that write, execute, delete, or send data externally.
7. Disconnect the server and revoke credentials when no longer needed.

### Example integration categories

MCP servers can expose source-control, issue tracking, databases, documentation search, browser/research, cloud APIs, observability, and local project tools. These are ecosystem categories, not bundled COSMIC integrations. Server availability and security are owned by the server publisher.

## Implementation status

- **Implemented in repository:** distinct provider adapters for OpenAI-compatible APIs, Anthropic, and Gemini; 18 named endpoint presets plus custom configuration; MCP connection scaffolding and tool discovery/call APIs.
- **Partially implemented:** provider compatibility across third-party endpoints; MCP stdio/HTTP/SSE lifecycle and protocol coverage; authentication and session handling.
- **Not implemented:** dynamic 100+ provider catalog, universal model discovery, every provider's native API, full conformance to all MCP capabilities, and a curated first-party MCP server marketplace.
