# Build Stabilization Verification

## Required local environment

- JDK 17
- Gradle Wrapper 8.9 (checked in; distribution SHA-256 pinned)
- Android SDK Platform 35
- Android Build Tools 35.0.0
- Android Gradle Plugin 8.5.2, Kotlin 2.0.20, KSP 2.0.20-1.0.24

## CI sequence

The single authoritative Android CI workflow validates the wrapper, runs `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug`. APK upload runs only after all checks pass. Test/lint/build reports are uploaded with `if: always()` so failed runs retain diagnostic artifacts.

## Test coverage added

- Provider preset uniqueness and adapter selection.
- OpenAI-compatible, Anthropic, and Gemini model discovery using MockWebServer.
- OpenAI-compatible response parsing, request model selection, and sanitized HTTP errors.
- MCP HTTP JSON-RPC initialize, tool discovery, tool invocation, malformed argument rejection, and cleartext endpoint boundary.
- Credential masking and audit-log redaction.
- Room task persistence across a database close/reopen.
- COSMIC CORE plan/approval/tool/completion state sequencing.

Existing command, task state, permission, diff, and update parser/version tests remain in place.

## Limitations

- API key encryption relies on Android Keystore/EncryptedSharedPreferences; masking behavior is JVM-tested, while Keystore behavior needs device/instrumented validation.
- Provider tests use a deterministic mock HTTP server; no live provider credentials are used.
- MCP tests cover the implemented HTTP JSON-RPC subset, not full protocol conformance. SSE, OAuth, stateful Streamable HTTP, server resources/prompts, and safe approval-mediated tool execution remain incomplete.
- Android SDK access is environment-dependent. Record workflow run links and real results below; do not mark checks successful if they were skipped or if no run exists.

## Recorded verification

- Local environment: JDK 21 is present; Android SDK is absent. `./gradlew --version` reports Gradle 8.9. Android compile/lint/APK tasks cannot be verified locally until SDK installation or CI execution.
- GitHub Actions result: pending stabilization changes and a CI run.
