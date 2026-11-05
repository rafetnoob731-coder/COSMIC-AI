# Agent execution reliability

## Root cause found in the execution path

The installed-device screenshot could not be reproduced in this environment because no Android device/emulator is attached. Repository inspection found a direct code path that produces the reported text: `AgentScreen` built a fixed OpenAI configuration (`provider_default`, `api.openai.com`, and a fixed model) instead of using the providers saved in Room. `AgentRuntime` then broke out when that fixed credential alias was absent and reported the generic `Execution ended before completion` message. This diagnosis is strongly consistent with the observed error, but the screenshot's exact runtime state remains unverified.

There were independent reliability defects in the same path:

- Starting a second request cancelled the current request without explaining why.
- A provider stream that closed without a terminal event could be treated as a successful stop.
- Empty model output could complete successfully.
- The OpenAI SSE adapter emitted each function fragment as a whole tool call, used synthetic IDs for missing IDs, and discarded malformed chunks.
- The execution loop did not validate tool arguments against the registered schema before dispatch.
- The conversation screen used a fixed session ID and did not reload messages from persistent storage.

## Implemented

- The conversation now uses the configured Room provider and its saved model/key alias. It reports missing provider setup and invalid provider configuration directly.
- A new send is rejected while an execution is active; it does not cancel the running task.
- Runtime task records use the task UUID as the execution ID and persist provider, model, timestamps, stage, stream state, token usage, HTTP status, and step diagnostics in the task record. The final assistant message is inserted before `COMPLETED` is persisted.
- Missing credentials, HTTP/provider failures, empty output, truncated streams, malformed tool calls, cancellation, and task-persistence failures produce explicit errors. Active tasks are persisted through `STARTING`, `WAITING_FOR_MODEL`, `RUNNING`, `WAITING_FOR_TOOL`, `COMPLETING`, and terminal states.
- OpenAI-compatible streamed tool calls are assembled by provider index and stable ID. JSON is parsed and top-level registered-schema requirements/types are checked before dispatch. Partial, missing-ID, unknown-name, and invalid-schema calls fail closed.
- Basic chat sends no tool definitions. The conversation is now the application landing screen, with a drawer for the other areas. The permanent Chat / Agent / Plan row is replaced with a compact mode menu. Settings uses a vertical category list rather than horizontal tabs.
- Search is implemented through a provider-independent `SearchProvider` contract. DuckDuckGo Instant Answers is a separate HTTP tool and needs no credential. It only returns topic answers/related topics, not full organic listings; see `docs/SEARCH_MIGRATION.md`.
- OpenAI-compatible, Anthropic, and Gemini model discovery follow provider pagination indicators when present. Settings supports filtering the returned model list and retains manual model-ID entry.

## Phase 2 task durability work

- Added schema v2 fields for provider recovery metadata, selected model/mode, max steps, attempt count, project URI, cancellation request, and transition history. Provider snapshot contains only the credential alias; it does not contain the API key or arbitrary provider extras.
- Added an explicit Room migration from schema v1 and removed destructive fallback migration. Existing task rows are retained.
- Added a validated task transition repository and an execution ledger keyed by task ID and provider tool-call ID. Tool argument hashes detect call-ID reuse with different input. A tool marked RUNNING during interruption is changed to UNKNOWN; it is never blindly replayed because its side effect may already have happened.
- The runtime records task transitions and durable tool outcomes, applies a 120-second tool timeout, persists final messages before completion, and restores conversation context for recovery. A transactionally claimed task cannot be started by two local workers at once.
- WorkManager schedules unique persistent work per task ID, constrained to connected network and non-low battery. Startup re-enqueues unfinished Room tasks in case the process died between task commit and work enqueue. A foreground worker reports the actual persisted stage and offers notification cancellation.
- Worker retries are bounded to five attempts for scheduler contention or worker infrastructure errors. Provider/task errors stay in the Room state; uncertain RUNNING tool calls become UNKNOWN and are never automatically repeated.
- Resume restores the persisted conversation, provider identity/configuration, model, mode, step budget, and selected SAF URI. If an interrupted tool call is repeated, its durable ledger blocks duplicate execution and gives the model the stored result or an explicit unknown-outcome failure.
- Added Room-backed tests for transition history/validation, interrupted tool safety, and call-ID argument mismatch. The implementation passed Android CI compilation and the regression suite on the feature branch; see the verification record below.

## Verification

- Feature branch: `codex/build-stabilization`; verified code commit: `f0f8f7116a44f442e860355e2b011bda7f30a6ed` (`test(tasks): model valid tool wait transition`). No merge to `main` was performed.
- GitHub Actions run [42080123047](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42080123047) passed wrapper validation, Android SDK setup (API 35), Kotlin compilation, unit tests, Android lint, and debug APK assembly.
- Unit test report: 18 test suites, 66 tests, 0 failures, 0 errors, 0 skipped. This includes opening and migrating a fixture created from the actual Room v1 schema, task transition persistence, interrupted task/tool recovery, UNKNOWN outcome handling, and unique WorkManager scheduling.
- KSP-generated Room schema v2 from CI was downloaded and byte-compared with the committed `app/schemas/com.cosmic.ai.data.local.db.CosmicDatabase/2.json`; they match. The v1 fixture migration test passed.
- Android lint completed with 0 errors and 51 warnings. Warnings include dependency-age recommendations, SDK checks/resources that are redundant for minSdk 26, unused resources, and a missing adaptive-icon monochrome layer. These are reported rather than suppressed.
- Debug APK workflow artifact: [download](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42080123047/artifacts/13440188176), `app-debug.apk`, 19,744,052 bytes in the downloaded artifact; ZIP integrity check passed. Verification reports and Room schemas: [download](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42080123047/artifacts/13440411708).
- Local `./gradlew help` succeeds. Full Android tasks used CI because this Termux environment has no Android SDK. No emulator or physical device is attached, so installation/startup and Android notification behavior were not device-verified.

## Remaining limitations

- The app has not been installed or exercised on a physical Android device here. Thus no screenshot or UI instrumentation result is claimed.
- The new suite covers persisted interrupted-task/tool recovery and duplicate WorkManager scheduling, but it does not simulate operating-system process death on an Android device or exercise all provider tool-call streaming formats. Device lifecycle behavior remains unverified.
- Provider-independent tool validation currently checks complete JSON objects and top-level required fields/types; it is not a full JSON Schema validator. Safe repair/retry of malformed model arguments is not implemented.
- Phase 2 persists a replayable user conversation/provider configuration snapshot and offers explicit task resume. A live provider stream is not resumed byte-for-byte; resume submits the persisted conversation to the model again. Uncertain in-flight side effects are blocked as UNKNOWN and require a new user-directed attempt. Room migration and repository-level recovery paths were run in CI. Physical process death and OEM background behavior remain unverified on-device.
- DuckDuckGo Instant Answers may return no results and is not a general web search service. It does not fetch pages. Provider HTTP/parser behaviors have deterministic MockWebServer coverage; live external search and device behavior remain unverified.
- The central multi-agent planner/handoff system, model-cost router, structured result contracts, loop detection, and autonomous result verification remain incomplete. Current runtime is a single model's bounded tool loop.
- MCP remains the implemented JSON-RPC subset. Approval-mediated MCP invocation, OAuth, resources/prompts, and complete Streamable HTTP/SSE interoperability remain incomplete.
- WorkManager and foreground notifications are now connected. Device-level behavior under OEM battery managers, force-stop, Android notification denial, and process death remains unverified until emulator/device tests run. Background work is deferred when network or battery constraints are unmet.
- Attachments, rendered Markdown/code blocks, full appearance controls, full Markdown/code rendering, responsive device screenshots, and full organic web search remain outstanding. The supplied COSMIC CODEX logo is now included for onboarding; the launcher retains a simplified vector symbol for legibility.
