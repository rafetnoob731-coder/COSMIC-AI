# M4 Security Test Report

**Execution status: blocked before test execution.** No test is reported as passed unless its command actually ran.

## Test coverage added or extended

- `ApprovalLifecycleRepositoryTest`: denial blocks execution, approval is single-use, exact task scope, expiration, DB reopen persistence, terminal and MCP-launch request persistence, duplicate request idempotence and changed-scope rejection, concurrent decision/claim, audit chain verification, audit tampering detection, latest-event deletion detection against an external anchor, and secret redaction. `SecureAuditHeadAnchorTest` checks encrypted checkpoint recreation.
- `McpPermissionBoundaryTest`: direct MCP authorization fails closed when approval is required; MCP invocation requires a gateway-issued exact-scope, task/call-bound, single-use authorization. `McpLaunchApprovalTest` checks no STDIO child starts before persisted approval and checks the approved initialize/tools/list handshake.
- `ToolRegistryBoundaryTest`: enumerates registered local tools and verifies disabled capabilities cannot return success; destructive terminal command requires approval; a non-zero shell exit must return a failure result.
- `RoomMigrationV1ToV2Test`: retains real v1 schema fixture and opens through migrations up to current DB version.
- `RoomMigrationV3ToV4Test`: synthesizes a v3 database from the checked-in v2 schema plus v3 approval/audit tables, migrates it, verifies legacy audit data remains and is sealed.
- Existing `PermissionGatewayTest`: retains gateway policy and scoped-token checks.

## Commands and outcomes

1. `git diff --check` — PASS, exit 0.
2. `./gradlew :app:compileDebugKotlin :app:kspDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug --no-daemon` — FAIL, exit 1 during task dependency configuration: no Android SDK (`SDK location not found`). None of the requested compile, KSP, test, lint, or APK tasks executed.

## Not tested

- Runtime permission allow/deny and MCP approval on Android.
- DB reopen/process death on device.
- Room generated schema v4 validation.
- Android instrumentation/Compose tests.
- APK installation/startup on the connected device.
- CI for the current working tree.

3. `./gradlew help --no-daemon` — PASS, exit 0 (`BUILD SUCCESSFUL`, Gradle 8.9). This verifies wrapper/Gradle configuration only; it does not compile Android code.
4. ADB read-only inventory — PASS: device connected; package `com.cosmic.ai.codexpreview` version `0.1.0-codexpreview` is installed. It is not the current source build and was not modified. No fatal exception lines were found in the last 300 logcat records inspected.


## Additional execution evidence

- Raw Python `sqlite3` smoke of checked-in v1 fixture plus the literal migration SQL for 1→2→3→4 — **PASS**. Existing task data remained; a legacy v3 audit row remained and received the new v4 default columns. It does not substitute for Room migration tests or generated schema checks.
- The final full Gradle command was executed again after the source fixes. It **FAILED**, exit 1, before Kotlin/KSP/test/lint/APK tasks because no Android SDK location is configured. The added tests have not been run.
- Source check: `git diff --check` — PASS, exit 0 on the final tree; no device APK was produced or installed.


## SDK and CI recovery results

- `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset. No SDK candidate directories or `sdkmanager` are installed; Termux package search returned no Android SDK package. JDK 21, Gradle wrapper 8.9, and ADB are present. Storage has about 8.6 GB free, but no practical native Termux Android SDK package/build-tools installation path was found.
- The actual working tree is uncommitted and the user explicitly prohibits commits/pushes. GitHub Actions cannot receive these uncommitted sources through the current configured workflow; no CI run or artifact URL is claimed.
- `./gradlew help --no-daemon` — PASS, exit 0. Full Android verification fails before task execution because SDK location is missing.
