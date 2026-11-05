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
- Brave Search is a separate HTTP service and tool. Its API key has a dedicated encrypted-store alias and separate Settings entry; results contain the source URLs returned by Brave. No key is hardcoded or included in this repository. The key shared in chat should be rotated before production use and entered again in Settings.
- OpenAI-compatible, Anthropic, and Gemini model discovery follow provider pagination indicators when present. Settings supports filtering the returned model list and retains manual model-ID entry.

## Verification

- Commit `e9b09cd` passed wrapper validation, Kotlin compilation, 56 unit tests (0 failed, 0 skipped), Android lint, debug APK assembly, and artifact uploads in [Android CI run 42070890889](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42070890889).
- The [`app-debug.apk` workflow artifact](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42070890889/artifacts/13436211838) is 19,125,910 bytes and currently expires November 19, 2026.
- End-to-end runtime tests cover a successful `hi` response through the actual OpenAI-compatible streaming adapter and Room, completion-after-persistence, missing credentials, HTTP 401 with sanitized response text, and a dropped network connection. Tool tests cover multi-call fragment reconstruction and malformed/truncated arguments. Provider tests cover model pagination and streaming 429 handling. Brave tests use deterministic MockWebServer responses.
- Local `./gradlew help` succeeds. Android build tasks cannot run locally because this Termux environment has no Android SDK.

## Remaining limitations

- The app has not been installed or exercised on a physical Android device here. Thus no screenshot or UI instrumentation result is claimed.
- The test suite does not yet cover timeout, HTTP 500, lifecycle/process-death recovery, or every provider's tool-call format. The adapter's HTTP tests include 429; runtime end-to-end tests include 401 and network disconnect.
- Provider-independent tool validation currently checks complete JSON objects and top-level required fields/types; it is not a full JSON Schema validator. Safe repair/retry of malformed model arguments is not implemented.
- Task state is persisted, but process-death recovery does not resume an interrupted provider stream or tool execution. Existing task records do not yet carry a full replayable request/approval contract.
- Brave Search returns and cites search result titles, URLs, and snippets. It does not fetch full pages or extract page content. A configured Brave key and live Brave API account are needed for external search; deterministic tests use a mock HTTP server.
- The central multi-agent planner/handoff system, model-cost router, structured result contracts, loop detection, and autonomous result verification remain incomplete. Current runtime is a single model's bounded tool loop.
- MCP remains the implemented JSON-RPC subset. Approval-mediated MCP invocation, OAuth, resources/prompts, and complete Streamable HTTP/SSE interoperability remain incomplete.
- Notifications/foreground service are not yet connected to the agent execution lifecycle; background completion notifications and durable task resume are not verified.
- Attachments, rendered Markdown/code blocks, full appearance controls, original logo redesign, and responsive device screenshots remain outstanding. The existing orbital COSMIC mark is retained.
