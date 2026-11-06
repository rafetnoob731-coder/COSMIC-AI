# Project Execution Engine M2 Status

## Implemented in this slice

- `ProjectWorkspaceProvider` abstracts SAF trees without converting content URIs into paths.
- SAF operations are relative-path scoped and return typed failures for revoked access, invalid paths, missing entries, wrong node types, oversized content and I/O failures.
- App-private internal workspaces enforce canonical path containment and use temporary-file replacement.
- ZIP import rejects absolute/traversal paths and limits entries, per-entry expansion and total expanded bytes; failed or cancelled imports remove their partial workspace.
- Project scans now use the SAF workspace adapter and preserve relative paths.
- Build/test candidates are derived from detected manifests and declared scripts. They are displayed as candidates requiring approval, not as executed operations.
- Runtime probes distinguish language detection, a detected executable/version, and project execution/build/test support. Runtime availability does not assert an execution adapter.
- Existing HTML preview remains the only integrated project execution-like capability; it is a WebView preview, not a Node.js runtime.

## Not implemented

- No non-HTML project command is run by a new execution adapter. A safe project runner needs an actual compatible runtime plus a project-scoped process sandbox. Android's app process shares the app's network identity, and SAF URIs cannot serve as process working directories. A plain `ProcessBuilder` or `sh -c` over a copied workspace would not provide the requested network/process isolation.
- No package installation is performed. Package-manager commands can write files and access networks and need a multi-capability approval contract before execution.
- Remote execution is not available because no user-configured remote worker exists.
- Candidate plans are not proof of runtime availability or success. They are informational and are not sent to a shell.

## Verification

See the turn report for the exact attempted Gradle command and environment blocker. Added tests are authored but not executed when Android SDK resolution is unavailable.
