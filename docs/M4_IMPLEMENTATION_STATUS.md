# M4 Implementation Status — Security Lifecycle

**Status: Incomplete / unverified.** This report reflects the current uncommitted working tree, not a release.

## Implemented in source

- Durable Room approval requests with stable IDs, task/tool/call identity, exact argument digest, resource, risk, expiry, and one-use/task-scoped decisions.
- Approval and denial UI in Agent and Security Center, including explicit Continue for persisted approvals not yet claimed and cancellation.
- Atomic request decision/claim transitions; denial/expiry fail closed; interrupted claimed operations become `UNKNOWN` and are not replayed.
- Agent-dispatched MCP tool names are namespaced, and MCP calls use the same PermissionGateway token scope and persisted approval lifecycle as other tools. Direct MCP calls without task context fail closed if approval is required.
- Audit rows persist sequence number, previous hash, and SHA-256 event hash; event appends are transactional and validate the chain. Legacy unchained rows are deterministically sealed when first checked. Audit fields pass through redaction and do not include raw tool arguments.
- Room migration 3→4 preserves old event rows and adds chain fields. v1 historical fixture remains; a v3 migration fixture test was added.
- Application startup gateway initialization order was corrected, and startup now verifies audit integrity before approval/task recovery.

## Verification

- `git diff --check`: PASS (exit 0).
- `./gradlew :app:compileDebugKotlin :app:kspDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug --no-daemon`: FAIL before compilation/KSP/tests/lint/APK tasks because Android SDK location is unavailable. Exact error: `SDK location not found`; `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and project `local.properties` are absent.
- Unit tests added/modified but not executed.
- Generated Room v4 schema unavailable; migration validation not executed.
- ADB device is connected (`localhost:43405`, itel S665L, Android 12/API 31), but there is no APK from the current source tree; no install or runtime test was performed. Existing installed app was not changed.
- No CI workflow was run for the uncommitted tree. No commit, push, merge, or release was performed.

## Limitations / unresolved risks

- An optional SecureAuditHeadAnchor now stores a monotonic sequence/hash checkpoint in Keystore-backed EncryptedSharedPreferences. It detects tail deletion after checkpoint establishment, but not coordinated rollback/replacement of both app-private DB and encrypted preferences or first-install history loss. A nested transaction rollback can cause a conservative fail-closed checkpoint mismatch; this is not tamper-proof storage.
- Low-level MCP `callTool()` cannot create a durable approval without task/call identity; it fails closed. The model/Agent MCP dispatch path is the path with approval/resume.
- MCP sessions are process-local. If a pending approved MCP call is resumed after process death before its server reconnects, it will fail safely; MCP server reconnection/recovery is not established here.
- Android/KSP compilation could expose additional type/schema errors; none can be ruled out until SDK-enabled verification.
- No device interactions were tested against the changed source.

## Device inventory (read-only)

- ADB: connected at `localhost:43405`.
- Device: itel S665L, Android 12 / API 31, arm64-v8a.
- Installed COSMIC package detected: `com.cosmic.ai.codexpreview`, version `0.1.0-codexpreview`, versionCode 1. It is an existing build, not built from this working tree; it was left untouched.
- No matching fatal-exception lines were present in the last 300 logcat records checked. This does not establish runtime correctness for the modified source.

## Finalization review addendum

- MCP server definitions now persist in `SecureStore` (EncryptedSharedPreferences backed by Android Keystore) and are restored as disconnected configurations after process restart. They are deliberately not auto-connected; the user must explicitly reconnect before a pending MCP approval can continue.
- MCP server connection startup checks the shared permission gateway: STDIO requires `EXECUTE`, HTTP requires `NETWORK`. If STDIO launch needs approval, a persistent `mcp_server_launch` request is created; Security Center can approve/deny/cancel and Continue validates the configuration digest before launching. No process is started before authorization. HTTP launch approval remains fail-closed if a policy requires it.
- MCP connection jobs are cancellable/replaced by connection ID; cancellation closes an unregistered candidate session. This reduces the chance that a late connect completes after the user disconnects.
- Legacy audit rows are redacted before sealing, and hashes now authenticate exact canonical persisted values. The SHA-256 chain is serialized through Room transactions. A later-event edit or non-tail deletion is detectable; without an external head anchor, deleting only the newest tail event cannot be detected. This is not tamper-proof against full database replacement/recomputation.
- Added test sources for concurrent approval decision/claim, concurrent audit appends, MCP tool re-resolution after reconnect, encrypted MCP config round-trip, and legacy audit secret redaction. They remain unexecuted because the Android SDK is absent.


## Finalization verification update

- A source review found and fixed two verified issues: non-zero shell exit codes were previously represented as `ToolResult.Success`; they now return `Failure` with the actual exit code, and a regression test was added. Duplicate `(taskId, callId)` approval submissions now reject a changed tool/action/argument digest instead of returning the existing request for a mismatched scope. Approval resource and terminal reason strings are redacted before persistence.
- Raw SQLite migration SQL smoke — **PASS**: created a database from the checked-in v1 fixture, applied the migration SQL 1→2→3→4, retained the v1 task fields, and retained a v3 audit row with v4 chain columns initialized. This is not a Room/KSP migration test and does not verify Room schema validation or application-level legacy sealing.
- Final Gradle verification command was rerun and **FAILED before task execution** (exit 1): `SDK location not found`; therefore Kotlin compilation, KSP, unit tests, Android test compilation, lint, APK assembly, and the new regression tests remain unverified.
- `git diff --check` and complete working-tree/branch inventory are recorded in the final report. No commit, push, merge, APK install, or data clearing was performed.


## Blocker-resolution implementation (unverified build)

- Added persistent STDIO MCP launch approval creation, exact configuration digest validation, visible executable/argument details with credential masking, explicit Continue, and task-bound one-use gateway authorization before process creation. Interrupted claimed launch is conservatively `UNKNOWN`.
- Added Keystore-backed encrypted audit head storage with synchronous preference commit and monotonic sequence checks; installed as the app's default audit anchor. Tail truncation after anchor establishment is now detectable if app-private anchor data remains intact.
- Added tests for MCP launch approval gating/handshake, audit anchor recreation, and latest-event deletion. They are not executed.
- Build verification remains blocked by absent Android SDK; only raw SQLite migration SQL smoke and Gradle `help` have run.
