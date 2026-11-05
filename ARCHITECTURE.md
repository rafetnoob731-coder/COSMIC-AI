# COSMIC AI Architecture (Milestone 3)

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
- Agent task rows persist terminal states and approval pauses. Automatic recovery/resumption of a provider/tool run is not implemented.
