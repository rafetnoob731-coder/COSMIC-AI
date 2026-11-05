# COSMIC AI

Mobile-first autonomous coding agent for Android.

Native Kotlin + Jetpack Compose. Not a WebView shell.

## Features (spine)

- Multi-provider AI (OpenAI-compatible first: OpenAI, OpenRouter, DeepSeek, custom base URL)
- Tool-calling agent loop with permission gates
- SAF project folders (persistable URI permissions)
- File tools: list / read / write
- Sandboxed terminal (`sh -c` under app UID)
- Room sessions + message history
- Encrypted API key storage (Android Keystore + EncryptedSharedPreferences)
- Foreground service channel for long agent runs
- Material 3 dark developer workspace

## Requirements

- Android Studio Ladybug+ / AGP 8.5+
- JDK 17
- minSdk 26, targetSdk 35

## Build

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Setup

1. Open project in Android Studio.
2. Sync Gradle.
3. Run on device/emulator.
4. Settings → Add Provider → paste API key + base URL + model.
5. Projects → Open Project Folder (SAF).
6. Agent tab → switch to Agent mode → instruct.

## Architecture

```
ui/          Compose screens + navigation
domain/      pure models (provider, agent, permission)
data/        Room, DataStore, SecureStore, repositories
runtime/     AgentRuntime, ToolRegistry, provider adapters
```

## Security

- API keys never logged, stored encrypted.
- File/terminal access gated by user toggles + approval for destructive ops.
- No mandatory account. No backend. No analytics SDK in this tree.

## Known limitations (v0.1)

- Anthropic / Gemini native adapters not yet separate (OpenAI-compatible path covers most gateways).
- MCP server transport scaffolding only (not full protocol client).
- Diff review UI for write_file is approval-gated but not a full visual diff viewer yet.
- Git integration not implemented.
- Background WorkManager chain is prepared; long runs currently use FGS + in-process job.
- No automated instrumented tests shipped in this drop.

## License

Apache-2.0


## Milestone 3

- Permission levels: READ / WRITE / EXECUTE / ADMIN + audit log
- Path traversal blocked
- diff_file, write_file_safe (checkpoint), rollback_file
- Ollama preset (localhost)
- Unit tests under `app/src/test`
- See ARCHITECTURE.md and FEATURE_REPORT.md

## App updates

On startup the app calls the update API (`UpdateConfig.ENDPOINT`).

- **Mandatory** → full-screen PLEASE UPDATE APP (no Later)
- **Optional** → UPDATE NOW / REMIND ME LATER
- **Network error** → continue offline (no crash)
- APK download via DownloadManager → SHA-256 verify → package name verify → system installer

Backend: `backend/update-api/server.py`  
Schema: `backend/update-api/schema.sql`  
Admin token: env `COSMIC_UPDATE_ADMIN_TOKEN` only (never in APK)

Unit tests: `VersionComparatorTest`, `UpdateRepositoryParseTest`


## GitHub Releases updates

Repository: https://github.com/rafetnoob731-coder/COSMIC-AI

Publish:
```bash
git tag v0.4.0
git push origin v0.4.0
```

`android-release.yml` builds APK, writes SHA256SUMS + release.json, creates the GitHub Release.

App startup reads `GET /repos/rafetnoob731-coder/COSMIC-AI/releases/latest` (no token).

## Bug Center

In chat: `/bugs` · `/bugs new` · `/bugs my` · `/bugs help`

## Product resources

- Welcome copy: [`docs/WELCOME.md`](docs/WELCOME.md)
- Setup and troubleshooting: [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md)
- Provider and MCP connection guide, official links, security guidance, and current implementation boundaries: [`docs/PROVIDER_AND_MCP_RESOURCES.md`](docs/PROVIDER_AND_MCP_RESOURCES.md)
- Architecture: [`ARCHITECTURE.md`](ARCHITECTURE.md)

Provider availability changes by vendor. COSMIC currently supports native adapters for OpenAI-compatible APIs, Anthropic, and Gemini, with gateway/custom endpoint configurations. It does not yet contain a dynamic 100+ provider directory or universal MCP server support; see the resource guide for the precise status.

## Build verification

Requirements: JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and the checked-in Gradle Wrapper (Gradle 8.9). From a fresh checkout with the SDK configured:

```bash
./gradlew --version
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

The authoritative workflow is [Android CI](.github/workflows/android-ci.yml). It validates the wrapper, compiles Kotlin, runs unit tests and lint, builds the debug APK, and uploads the APK and reports. Android release signing remains configured outside this debug CI workflow; no signing secrets belong in the repository.
