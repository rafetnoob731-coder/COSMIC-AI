# COSMIC CODEX Overnight Audit

Audit date: 2026-11-06 (Asia/Dhaka)  
Repository branch: `codex/build-stabilization`  
Audited source: `bb736c50a95baa83e4b4b1c030685cca468ba809`  
Scope: physical-device and source inspection. No application source files were changed.

## Executive summary

The exact audited source passes the GitHub Actions compile, unit-test, lint, and APK-build pipeline. A separate `com.cosmic.ai.codexpreview` APK was installed and exercised on the connected itel device. The app did not visibly crash during the actions performed. Filtered logcat during that attached session contained no matching `FATAL EXCEPTION` or `ANR in` entry, but this does not establish stability for untested paths.

A high-severity security defect was found: the Terminal screen invokes the shell executor directly and bypasses the terminal permission policy enforced by the agent tool path. Additional confirmed problems include a clipped navigation drawer, automatic persistence of empty conversations, missing approval controls, and permission switches that are not connected to persistent policy. Workflows and some other centers are explicitly marked incomplete. No source change was made because the highest-risk findings need focused fixes and regression tests.

The supplied composite UI reference is [`docs/assets/cosmic_codex_ui_reference.png`](../docs/assets/cosmic_codex_ui_reference.png). Visual comparison was manual; no pixel-diff or automated image alignment was run.

## A. Device and environment

| Item | Observed |
|---|---|
| Device | itel S665L |
| Android | 12 / API 31 |
| Display | 720 × 1612, density 320 dpi |
| Package exercised | `com.cosmic.ai.codexpreview`, parallel-install preview; existing app package/data was not replaced |
| Preview version | `0.1.0-codexpreview`, version code 1; target SDK 35 |
| Initial ADB | Connected over a local ADB endpoint during the device interaction session |
| Final ADB | Reconnected on the user-provided `localhost:43405`. Final `adb devices -l` listed the itel S665L. The old port `41083` had refused connection. |
| Permissions | No permission grants or device security settings were changed. No provider credentials were added. Manifest requests internet/network, foreground service/data sync, notifications, wake lock, package-install request, and download notification permissions. No broad shared-storage permission was requested. `REQUEST_INSTALL_PACKAGES` was observed denied. API 31 does not use the Android 13 notification runtime prompt. Final permission snapshot was collected after reconnect: install-time grants were present for INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, WAKE_LOCK, RECEIVE_BOOT_COMPLETED and DOWNLOAD_WITHOUT_NOTIFICATION; REQUEST_INSTALL_PACKAGES remained denied; no runtime permissions were granted. |

No destructive command, bug submission, provider credential, project write, or release operation was used. A safe `printf` command in the app sandbox returned actual stdout and `exit=0`. An initial command had literal `%20` due to ADB text encoding and correctly failed with exit 127; this was not attributed to the app. The system folder picker was opened and canceled; no folder grant was retained.

## B. Screen inventory

“Inspected” means opened and observed on the physical device; “partial” means only reachable/configured states were tested. Screenshots are actual device captures in [`device-screenshots/`](device-screenshots/).

| Screen | Result |
|---|---|
| Cold launch / splash | Partial. Blank dark-blue startup frames at ~0.75s and ~1.25s; Home by ~3.25s. Splash animation was not observed. `am start -W` returned `Status: ok`; measured total launch was ~4.9s in that run. |
| Welcome/onboarding | Not reached. Preview proceeded to Home; welcome route was not exposed. |
| Home | Inspected. New Chat/New Coding Task, quick access, and bottom navigation are present. Logo is a prominent black rectangular image on the cosmic surface. Bottom navigation worked. Full Home vertical scroll was not verified; the attempted final capture was empty. |
| Navigation drawer | Inspected; defect reproduced. Opens, but lower Workspace Tools/Settings entries are clipped. Swipe did not reveal them in the recorded attempt. Empty “New conversation” entries accumulated after repeated Chat entry. |
| Chat | Inspected. Empty state, composer, mode menu, provider/model selector, drawer, keyboard visibility exercised. No provider configured; no request was sent. Keyboard kept composer visible. |
| Conversations/history | Partial. Drawer/history opened. Repeated empty conversations were visible; search and nonempty selection were not verified. |
| Projects | Inspected. Empty project state and system folder picker opened, then canceled. No project file/Git action was tested. |
| Agents | Inspected and scrolled. Capability profiles shown; UI states independent handoffs/per-agent runtime status are unavailable. No agent task could run without a provider. |
| Tasks | Inspected. Empty state and All/Running/Completed/Failed horizontal filter row tested; swipe revealed Failed and selected it. No live task controls exercised. |
| Terminal | Inspected. Labeled Android app shell/app sandbox. Safe `printf COSMIC_AUDIT_OK` returned stdout and `exit=0`. Cancellation, stderr separation, timeout, destructive approval not exercised. |
| Tools / MCP | Inspected. Tool catalog and MCP Servers tab opened. No server configured or connected. |
| Memory | Inspected. Four memory categories described; no browse/edit/search action available in observed screen. |
| Workflows / Automations | Inspected. UI explicitly reports persistence, scheduling, and execution engine are not implemented. No fake execution was observed. |
| Activity | Inspected; empty state. No task run was available to populate it. |
| Bug Center | Inspected. `/bugs` navigated there. Report form and tabs opened; no submission made. Source discards parsed `/bugs` subcommands. |
| Settings | Inspected and scrolled. Appearance, provider settings, MCP, permissions, search and About reachable. System→Light worked; System restored. |
| Providers | Inspected. Empty state, add dialog and form opened/scrolled. API key remained blank; no provider saved, no network test/discovery. |
| Model Management | Inspected. Opens the same provider section as AI Providers; not a distinct screen. |
| About | Inspected. Preview version and license shown; website not configured. |
| Update Center | Not reached; no signed update caused it to open, and no ordinary drawer route was observed. |
| Security Center | No separate destination observed. Agent Permissions settings were inspected, not treated as a full security center. |

See [`README.md`](README.md) for the screenshot index.

## C. UI and interaction findings

### Confirmed visual defects

- **UI-01 — Drawer content unreachable:** no scroll container for drawer entries; lower tools/settings are offscreen. Evidence: [`12-chat-drawer.png`](device-screenshots/12-chat-drawer.png), [`13-drawer-scrolled.png`](device-screenshots/13-drawer-scrolled.png).
- **UI-02 — Logo treatment differs from supplied reference:** black rectangular bitmap stands out against the cosmic background and occupies substantial Home/chat/About area. Evidence: [`02-home.png`](device-screenshots/02-home.png), [`06-chat.png`](device-screenshots/06-chat.png), [`22-about.png`](device-screenshots/22-about.png).
- **UI-03 — Cold-start blank frames:** startup captures at ~0.75s and ~1.25s show only background; Home appears by ~3.25s. Evidence: [`62-startup-750ms.png`](device-screenshots/62-startup-750ms.png), [`63-startup-1250ms.png`](device-screenshots/63-startup-1250ms.png), [`64-startup-final.png`](device-screenshots/64-startup-final.png).
- **UI-04 — Duplicate empty history:** every visit to a fresh conversation leaves another identical “New conversation” entry. Evidence: [`12-chat-drawer.png`](device-screenshots/12-chat-drawer.png).
- **UI-05 — Model Management duplicate route:** opens provider screen. Evidence: [`59-model-management.png`](device-screenshots/59-model-management.png), [`15-providers.png`](device-screenshots/15-providers.png).

Navy/purple/cyan styling is generally consistent and Light theme selection works. Automated screenshot alignment, pixel difference, landscape, alternate sizes and reduced-motion behavior were not tested.

### Interaction checks

- Bottom navigation and Back worked for Home, Chat, Projects, Agents and Tasks.
- Drawer open/close works; lower destination reachability does not.
- Settings/provider forms vertically scrolled; preset row horizontally scrolled.
- Task filters and Bug Center tabs horizontally scrolled and selected.
- System→Light theme change and restoration worked.
- Composer remained visible above keyboard; no send without provider.
- Model picker reported no providers configured.
- Terminal ran one safe shell command and reported real exit code/output.
- Project picker opened/canceled; no persistent grant.
- No credentials saved, report submitted, data deleted, project modified, or MCP server connected.

## D. Bugs and source findings

| ID | Severity | Evidence / reproduction | Expected vs actual |
|---|---|---|---|
| SEC-01 | **High** | `app/src/main/java/com/cosmic/ai/ui/screens/terminal/TerminalScreen.kt` calls `RunCommandTool.performRun()` directly. `runtime/tools/ToolRegistry.kt` guarded `execute(args, policy, ...)` checks `allowTerminal` and destructive approval; the UI skips it. Safe `printf` succeeded through the UI. | All shell invocations should pass centralized authorization. UI path reaches `sh -c` without policy check. No destructive command was run. |
| SEC-02 | **High** | `SettingsScreen.kt` `PermissionsSection()` holds five `remember` local booleans; switches only mutate them. | Expected persisted settings to govern tool policy. Actual state is transient and not wired to `PermissionPolicy`/DataStore; compounded by SEC-01. |
| FLOW-01 | **High** | `AgentScreen.kt` handles `AgentUiEvent.NeedsApproval` by setting a status string and `busy=false`; no approve/deny/resume action. Runtime persists `AWAITING_APPROVAL` and emits the event. | User should be able to approve/deny and resume; the current UI has no checkpoint completion path. |
| UI-01 | **Medium** | `AgentScreen.kt` drawer lacks scroll container; device swipe reproduced clip. | All destinations should be reachable; lower entries remain outside viewport. |
| DATA-01 | **Medium** | `AgentScreen.kt` `LaunchedEffect(sessionId)` inserts “New conversation” as soon as a fresh session opens. Repeated visits generated duplicate empty rows in isolated preview data. | Empty chats should not pollute history; currently persisted before a message is sent. |
| MCP-01 | **Medium** | `SettingsScreen.kt` stores configs in `remember { mutableStateListOf<McpServerConfig>() }`. No server configured during device session. | Config should survive navigation/process recreation; this UI list is transient. Existing MCP is a documented subset, not full spec compliance. |
| NAV-01 | **Medium** | Settings maps both AI Providers and Model Management to section `0`; device tap reproduced same screen. | Distinct feature screen or accurate label expected; currently duplicate route. |
| CMD-01 | **Medium** | `CommandRegistry` parses `/bugs` subcommand; `AgentScreen.kt` routes all `OpenBugs` to `Routes.BUGS` and discards `sub`. | `/bugs new`, `/bugs my`, `/bugs help` should select actions; plain `/bugs` works, subcommands are ignored by navigation. |
| TERM-02 | **Medium** | `RunCommandTool.performRun()` merges stderr into stdout and polls with `Thread.sleep(50)`; cancellation cleanup/process destruction is absent. Terminal history is in-memory and only a count is rendered. | Separate streams, clean up processes on cancel/timeout, and usable command history expected. These paths were not stress-tested. |
| PROD-01 | **Medium** | Workflows report persistence/scheduling/execution incomplete; Memory screen is informational; Projects show empty SAF context and lack fuller project management surface. | Incomplete capabilities, not false-success behavior. |
| SEARCH-01 | **Low / known** | Search is documented as DuckDuckGo Instant Answers. | Not full organic web search. Live query not tested; documentation correctly limits claims. |

### Architecture/functionality assessment

- Packages are separated conceptually (`core`, `runtime`, `data`, `security`, `providers`, `ui`), but the build has one Android Gradle `:app` module; core is not an independently built Gradle module.
- Agent execution is a bounded single-model tool loop. Full independent multi-agent planning/handoffs and result verification are incomplete; the UI discloses this.
- Eighteen provider presets/custom config exist. Adapters/model discovery cover OpenAI-compatible, Anthropic and Gemini families; no live provider, secret, pagination or model discovery call was tested.
- MCP has HTTP JSON-RPC/stdio source/test coverage; settings also exposes SSE. Full Streamable HTTP/SSE lifecycle, OAuth, resources/prompts and complete interoperability are not established here.
- Bug reports are local JSON when no remote service is configured; no report was submitted.
- Persistent tasks, recovery, WorkManager and provider parsing have unit coverage. OS process-death and OEM background behavior were not device-tested.
- Source search found no `IntrinsicSize` use under `app/src`; Settings/provider screens opened without the prior intrinsic measurement crash. Model popup could not be tested without a configured provider/model.

## E. Animation and performance

- Drawer open/close was usable; animations were not measured frame by frame. No actual streaming/agent/task animation could be observed without a configured provider/task.
- Not verified: message entrance, generation stream, agent/task progress, terminal cancellation, reduced-motion behavior, landscape/responsive breakpoints.
- Session-wide `dumpsys gfxinfo`: 2,695 frames, 517 janky (19.18%), p50 15ms, p90 44ms, p95 93ms, p99 300ms, one frame in >1s bucket. This is uncontrolled across all navigation, not a per-screen benchmark; it indicates performance risk but not a single root cause.
- One cold launch reached Home around 3.25s; Activity start total ~4.9s. Single observation, not a repeatable benchmark.

## F. Build and automated verification

GitHub Actions for the exact audited source [run 42115527084](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42115527084) concluded **success** on 2026-11-05 UTC.

Successful steps included JDK 17/Android SDK API 35 setup, license acceptance, Gradle wrapper validation, Kotlin compilation, unit tests, lint, debug APK build, parallel-install preview APK build, APK archive/signature checks and report/artifact upload.

- Unit tests: 19 XML suites, **75 tests, 0 failures, 0 errors, 0 skipped**.
- Lint: completed with **53 warnings and 2 informational findings**, 0 errors. Findings include incompatible third-party navigation lint registry API references and source/resource warnings; this is not warning-free lint.
- APKs: debug and parallel-install preview APK were built. Preview APK SHA-256 observed: `2e9ed0627316072803b1bd946402161c2d5137c48fdd05fbafa9a7575e1a86d9`. Preview was installed and launched on the connected phone. This is not a Play-signed release APK.
- Artifacts: [debug APK](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42115527084/artifacts/13456756172), [preview/device APK](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42115527084/artifacts/13456971034), [build/test/lint reports](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42115527084/artifacts/13456251371).
- Local Android Gradle build was not completed because no Android SDK was configured in this Termux environment. CI supplies Android build evidence.
- No `app/src/androidTest` UI suite was present. CI’s “parallel-install” variant build is not itself an emulator/device install test. Manual device interactions are reported separately above.
- `git diff --check` passed after evidence curation. No local test rerun was made.

## G. Git and modifications

- Initial/final branch: `codex/build-stabilization`.
- Audited commit: `bb736c50a95baa83e4b4b1c030685cca468ba809`.
- Application source changes: none. An exploratory drawer scrolling edit was reverted because local compile/verification was unavailable.
- New audit files: `audit/OVERNIGHT_AUDIT.md`, `audit/README.md`, and curated device screenshots.
- No application files deleted; no merge, push, release, commit, or user-data clearing.
- Preview package data was left intact. The repeated empty conversation rows remain only in the isolated preview app.

## H. Remaining work and blocker

1. Fix Terminal permission bypass and test both UI and agent command paths against the same gateway; implement cancellation/process cleanup and separate stdout/stderr.
2. Persist permission settings and inject policy centrally; add visible approve/deny/resume UI for `AWAITING_APPROVAL`.
3. Make drawer scrollable and stop persisting empty conversations before first message.
4. Persist MCP configuration and align transport choices with tested protocol behavior.
5. Correct/rename Model Management and route `/bugs` subcommands.
6. Complete Workflows, project management and Memory CRUD only with real backing implementations.
7. On a future verification pass, test chat with an authorized test provider, cancellation/timeouts, model discovery, MCP, task recovery/notifications and screenshot comparisons after the identified fixes.
8. Add Compose UI tests and controlled per-screen performance benchmarks.

ADB reconnected on `localhost:43405`. A final warm `am start -W` returned `Status: ok` in 698ms and the preview Activity was resumed. The final 15,000-line logcat search returned no `FATAL EXCEPTION`, `ANR in`, or `Fatal signal` matches. The remaining limitations are the untested flows and missing provider/MCP/task configuration described above, rather than an ADB connection blocker.
