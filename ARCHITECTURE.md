# COSMIC CODEX Architecture (Milestone 3)

## Principle

COSMIC CORE is pure Kotlin. Android is a platform adapter (SAF, ProcessBuilder, UI, Keystore).

```
com.cosmic.ai.core.*          pure — task state machine
com.cosmic.ai.security.*      pure — permission gateway + audit
com.cosmic.ai.agents.*        pure — specialist profiles
com.cosmic.ai.intelligence.*  pure — model router
com.cosmic.ai.domain.*        pure — shared models
com.cosmic.ai.runtime.*       Android-aware adapters (providers, tools, agent loop, MCP)
com.cosmic.ai.ui.*            Compose studio
```

## Security

PermissionLevel: READ | WRITE | EXECUTE | ADMIN  
PermissionGateway.check → Allow | Deny | NeedsApproval  
validateRelativePath blocks `..` and absolute paths  
Audit ring buffer (500) on every decision  
Destructive commands never auto-allow

## Filesystem

SAF DocumentFile only. Tools: list/read/write, diff_file, write_file_safe (checkpoint), rollback_file.

## Providers

OpenAI-compatible (incl. OpenRouter, DeepSeek, Ollama, custom) · Anthropic · Gemini  
Ollama preset: `http://127.0.0.1:11434/v1`  
Cleartext only for localhost/127.0.0.1 in network_security_config

## Agent

Structured tool calling via provider adapters. Real tool execution. Task state and tool claims are persisted in Room. `TaskLifecycleRepository` validates transitions and appends a transition history. Tool claims use `(taskId, callId)` plus an argument hash. A RUNNING call recovered after process death becomes UNKNOWN and is not automatically replayed because its side effect may already have happened. Resume replays the persisted conversation through the selected provider and restores model/mode/project context; it does not continue a network stream byte-for-byte.

Schema v2 migrates v1 task rows without destructive fallback. Provider recovery metadata stores provider identity and the Keystore alias only, never the credential itself. App startup restores unique WorkManager requests for persisted unfinished tasks. The worker claims task state transactionally, resumes persisted context, and records progress back into the same Room task row. A tool left in RUNNING is made UNKNOWN and cannot be automatically replayed.

## Background execution

A unique `AgentTaskWorker` runs with connected-network and battery-not-low constraints. It promotes to a foreground worker with a task-specific notification and cancel action. Worker retries are bounded for scheduling contention and infrastructure exceptions; task/provider failures remain visible in Room and are not automatically resubmitted. Android may defer constrained work, and force-stop/battery policies remain platform-controlled.

## Tests

`app/src/test/...` PermissionGatewayTest, TaskStateMachineTest, UnifiedDiffTest  
Run: `./gradlew :app:testDebugUnitTest`

## Build and provider catalog status (stabilization)

- Gradle Wrapper is checked in (`gradle-8.9`, JDK 17 toolchain/bytecode, Android API 35, min API 26). Wrapper distribution checksum is pinned.
- The provider selector has 18 named presets plus one custom endpoint. OpenAI-compatible vendors share one adapter; Anthropic and Gemini use distinct adapters. Model discovery is implemented for these three API families and does not imply feature parity for every compatible gateway.
- MCP is limited to basic JSON-RPC initialization, `tools/list`, and `tools/call` over the HTTP POST path, plus stdio process launch. Legacy SSE is explicitly unsupported. Streamable HTTP session negotiation/notifications, OAuth, resources/prompts, reconnect, and persistent server configuration are not complete. Android stdio servers require the executable/runtime to exist in the app sandbox.
- Remote MCP endpoints must use HTTPS; cleartext is allowed only for loopback. MCP calls are not yet routed through the central permission approval UI, so don't enable untrusted MCP servers for agent execution.
- Agent task lifecycle transitions and tool execution outcomes are persisted in Room. A Room v1-to-v2 migration preserves existing task rows; schema v2 is checked in and generated/compared in CI. The Android adapter schedules unique WorkManager work per task ID, restores unfinished tasks at startup, applies connected-network and battery-not-low constraints, and exposes foreground progress/cancellation notifications. Recovery claims are transactionally guarded. A RUNNING tool operation becomes UNKNOWN after interruption and is never automatically replayed. Resume re-submits persisted conversation context to the provider; it cannot resume a byte-level streaming session. CI verifies the migration using a database fixture created from the actual v1 schema and verifies unique scheduling and repository-level recovery.

## Product workspace and project technology detection

- Conversation is the landing surface. Its drawer groups conversations, automations, projects, and Activity; integrations, memory, models/settings are secondary. Terminal is reachable as a contextual execution console from conversation actions; task rows and UNKNOWN tool outcomes are shown in Activity. Existing feature screens and routes remain in the Android module.
- Theme preference now applies System, Light, and Dark palettes at the application root. Text/source attachments use the Android document picker, are limited to 1 MB, and enter the user-submitted prompt only after explicit selection.
- `core.project.ProjectTechnologyDetector` is pure Kotlin. It detects multiple languages and manifest-backed technology evidence without assuming only one language. `AndroidRuntimeProbe` separately checks a fixed set of executable names in the app-visible Android shell PATH. A detected language does not assert a usable compiler/runtime or imply remote/container support.
- Current project inspection is SAF scoped and bounded to 500 entries and five directory levels. It detects common language extensions and selected manifests/framework dependencies. This is a fingerprinting foundation, not a language server or a universal build/test executor.
- The current Automations screen states that workflow persistence, scheduling, and execution are not implemented; it offers no false create/run controls. Android-local shell remains app-sandboxed and is not a general Linux/container toolchain.

The conversation-first product grouping and language-neutral project fingerprinting implementation and limitations are documented in [`docs/PRODUCT_REDESIGN_STATUS.md`](docs/PRODUCT_REDESIGN_STATUS.md) and [`docs/UNIVERSAL_PROJECTS.md`](docs/UNIVERSAL_PROJECTS.md). Automation and universal build execution are still future implementation work; current UI does not claim these operations are available.
