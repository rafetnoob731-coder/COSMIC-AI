# Getting Started

## 1. Choose an AI connection

Open **Settings → Providers**. Add a provider preset or enter a compatible custom API endpoint. Supply the model identifier and credentials required by that service, then use **Test** before saving. API credentials are stored in encrypted app storage; they are not included in requests to unrelated services.

COSMIC CODEX currently has distinct request adapters for OpenAI-compatible APIs, Anthropic Messages, and Google Gemini. OpenRouter, DeepSeek, Ollama, and custom endpoints use the OpenAI-compatible adapter. Compatibility depends on the provider's supported API features and model; tool calling and streaming are not uniform across services.

See [Provider and MCP Resources](PROVIDER_AND_MCP_RESOURCES.md) for setup links and compatibility notes.

## 2. Open a project

Go to **Projects → Open Project Folder** and select a folder using Android's system folder picker. Android grants access to the selected tree. COSMIC CODEX cannot access files outside granted locations. Keep a backup or use version control before approving edits.

## 3. Work with the agent

Open **Agent**, select a session and mode, and describe a task. Keep instructions scoped and review tool requests, diffs, command output, and errors. A generated explanation is not proof that a file changed or a command succeeded; check the activity result.

## 4. Use the terminal carefully

Commands run in the Android app's restricted process environment. This is not a remote Linux machine or a general-purpose development container. Android permissions and available binaries limit what can run.

## 5. Configure MCP

Open **Settings → MCP** and configure a server supported by the app's available transport. Treat a connected server's tools as external code with the permissions and data access described by that server. Do not paste secrets into untrusted server configuration.

## 6. Sessions and commands

Sessions and messages are stored locally. Type `/bugs` in chat to open Bug Center, or `/help` for chat commands.

## Troubleshooting

- **Provider test fails:** check the base URL, model ID, account access, network, and provider-specific API requirements.
- **Tool call is unavailable:** confirm the selected model supports tool calling through that provider's API.
- **Project files are missing:** reopen the folder with the system picker and check Android's granted access.
- **MCP connection fails:** verify the endpoint, transport, authentication headers, and server logs. Not every MCP transport feature is implemented in this Android client.
- **Offline startup:** provider requests and update checks need network access; local sessions remain available.
