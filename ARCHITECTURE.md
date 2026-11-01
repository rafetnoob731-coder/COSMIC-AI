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

Structured tool calling via provider adapters. Real tool execution. Cancellation. Task state machine for persistence surface.

## Tests

`app/src/test/...` PermissionGatewayTest, TaskStateMachineTest, UnifiedDiffTest  
Run: `./gradlew :app:testDebugUnitTest`
