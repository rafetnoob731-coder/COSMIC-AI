# Product workspace redesign and multi-language foundation

## Implemented in this branch

- Conversation remains the application workspace landing surface. The navigation drawer groups Conversations, Automations, Projects, and Activity; models, integrations, memory, and settings are secondary entries. Terminal remains available contextually from conversation actions. Existing routes and backend screens were retained.
- The drawer includes a local/no-account profile footer, title search for recent conversations, and active conversation selection. Session IDs are routed to the conversation view so selected history restores its persisted messages.
- The conversation composer supports multiline prompts, execution mode selection, stop, and an explicit Android document picker for text/source attachments capped at 1 MB. Messages display fenced code blocks with copy actions and assistant copy actions. Actual agent status and tool activity is reflected in the transcript area, not in the composer row.
- Activity reads the existing Room task and tool-execution ledger. It displays execution IDs, persisted status/progress, elapsed wall time, tool state/result/error, and explicit UNKNOWN side-effect warnings. It reuses the persistent task engine.
- System/Light/Dark is stored in DataStore and applied by the app root. Dark palette uses the supplied charcoal/navy and restrained violet values.
- Project workspace uses the SAF-selected tree, persists the URI grant where Android permits, and scans bounded entries for multi-language file and manifest evidence. The detector is pure Kotlin; runtime probes are Android-only and report executables actually visible on the app shell PATH.
- Added unit coverage for mixed-language project detection, manifest-backed framework clues, unknown projects, and runtime catalog lookup.
- Automations is intentionally an honest capability-state page: it clearly says triggers, persistence, and an execution engine are not implemented. It does not expose fake create/run controls.

## Not implemented / partial

- No persistent automation workflow model, editor, scheduler, integrations, approval flow, test-run engine, pause/resume, or workflow history exists. The Automations workspace is currently informational only.
- Project workspaces do not yet persist project metadata/instructions/memory, associate conversations and automations, show a complete project tree, or present build/test history. Project detection is bounded and heuristic; it reads only selected manifests and does not index source semantics.
- Android-local runtime probing is not equivalent to a complete development toolchain. Project-specific command planning/execution, dependency installation, remote Linux, containers, runtime isolation, multi-language build/test execution, static analyzers, formatter/refactor adapters, and language servers are not implemented. Detection does not imply execution capability.
- The message renderer handles paragraphs and fenced code blocks with copy controls, but is not full CommonMark: headings, tables, rendered links, syntax highlighting, image previews, and attachment previews are missing. Attachments currently support bounded text-like content only.
- Model selection currently reflects configured provider defaults. A full provider/model catalogue browser in the conversation header, context/capability presentation, and persistent per-conversation model overrides are not implemented.
- Drawer transitions use Material navigation drawer behavior; conversation entry motion, reduced-motion policy, and comprehensive adaptive tablet layouts need further work.
- UI/Compose instrumentation tests, emulator screenshots, keyboard/inset testing, and physical-device verification have not been run in this environment.
- The reported truncated `Execution error: Unexpected cha...` was not reproduced from a device trace or captured provider response. The existing provider/runtime regression suite passes; this does not prove that screenshot-specific failure is resolved.

## Verification

- CI run [42089618465](https://github.com/rafetnoob731-coder/COSMIC-AI/actions/runs/42089618465) ran on feature code SHA `b130c109dae199c39f6f60f463ea51714dcb3fe6` and passed JDK/Android SDK setup, Gradle wrapper validation, Kotlin compile, unit tests, lint, debug APK assembly, and artifact uploads.
- Unit tests: 71 across 19 suites; 0 failures, 0 errors, 0 skipped. This includes the new project detection tests and the existing agent, provider, MCP, permission, Room migration, and WorkManager tests.
- Lint completed with 0 errors and 51 warnings. Warnings are reported in the CI artifact rather than suppressed.
- Debug APK is available as workflow artifact `cosmic-ai-debug-apk-b130c109dae199c39f6f60f463ea51714dcb3fe6` (artifact ID `13445180967`) from the linked CI run. No Android device/emulator was attached for installation or screenshots.
- The Termux environment has no configured Android SDK; the full Android validation was performed by CI.
