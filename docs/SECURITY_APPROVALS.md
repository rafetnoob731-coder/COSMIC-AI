# Approval lifecycle and security audit

## Execution boundary

Local agent and Terminal invocations enter `ToolRegistry.execute()`. It resolves the registered tool, validates relative file paths, classifies capability, and asks the shared `PermissionGateway` immediately before dispatch. The gateway can accept only an in-memory single-use authorization token issued by the gateway itself. That token is bound to the approval ID, task ID, call ID, tool name, exact argument digest, and expiry. A token is consumed when authorization is checked and cannot be reused.

The Terminal and local Agent paths use the same ToolRegistry. MCP tools exposed to the model have stable namespaced IDs and execute through the MCP manager's shared PermissionGateway check. A `NeedsApproval` MCP call is persisted against the task/call and resumes through the same approval lifecycle. The low-level direct `callTool()` API has no task identity, so it fails closed if approval is needed.

## Durable approval states

`approval_requests` stores the stable approval ID, task/call identity, agent, tool, action, resource scope, risk, sanitized reason, exact argument digest, status, decision mode, creation/expiry/decision time, and outcome. The full tool arguments are kept only in the existing tool execution ledger for exact recovery; they are not copied into the audit event table.

Typical transitions:

```text
PENDING -> APPROVED -> EXECUTING -> COMPLETED
PENDING -> DENIED
PENDING -> CANCELLED
PENDING/APPROVED -> EXPIRED
EXECUTING -> UNKNOWN
```

A process restart can display pending requests again. An approved request not yet claimed requires an explicit Continue action. A claimed operation interrupted by process death, cancellation, or timeout is marked `UNKNOWN`, and is never automatically replayed.

`ALLOW_FOR_TASK` is intentionally narrow: only byte-identical `write_file` arguments can reuse it in the same task. Other operations, especially commands with external side effects, require a fresh decision. There is no unrestricted permanent grant.

## Audit

`security_audit_events` stores request, decision, authorization, execution, completion, failure, cancellation, expiry, and unknown-outcome events. Every current event writer routes through `SecurityAuditRepository`; credentials are redacted and sensitive arguments/output contents are not copied into audit rows. Each row has a sequence, previous hash, and SHA-256 hash over canonical, length-prefixed fields. Existing unchained rows are sealed in deterministic timestamp/event-ID order on first verification. The Security Center displays pending requests and exposes Allow Once / Allow for Task (only for supported exact `write_file` reuse) / Deny / Continue / Cancel, plus a chain verification status.

This is tamper-evident for edits/deletions while the prior chain is intact. It is not tamper-proof against someone who can rewrite the whole database and recompute the chain; no external anchor/signature is implemented.

## Database

Room migration path is `1 -> 2 -> 3 -> 4`. Version 3 creates approval/audit tables; version 4 adds hash-chain columns. The v1 historical fixture remains and a v3 database fixture test checks 3→4 preservation and sealing. This environment has no Android SDK, so KSP did not generate the current schema and neither migration test ran. The schema and migration path remain unverified until Android SDK-enabled CI executes them.

## MCP connection and recovery boundary

MCP server definitions are stored encrypted using the existing `SecureStore` and restored as disconnected entries. They are not auto-started on process launch. The user reconnects explicitly, after which a persisted approved tool request can be continued against the rediscovered server/tool. The connection itself checks the shared gateway (`EXECUTE` for STDIO process start; `NETWORK` for HTTP). If the policy requires approval, connection is blocked before process/network initialization. STDIO server launch now creates a durable `mcp_server_launch` approval bound to a digest of the encrypted server configuration. Security Center displays the executable/arguments with credential-pattern redaction; environment values are not shown. Continue re-loads the persisted config, verifies the same digest, atomically claims the approval, issues a one-use gateway authorization, and only then starts the process. A startup failure after process initialization begins is recorded as `UNKNOWN`, not replayed. Legacy SSE remains unsupported.

## Hash chain threat model

The chain uses transactional sequence allocation and SHA-256 over length-prefixed canonical persisted fields. An optional app-installed `SecureAuditHeadAnchor` stores the latest sequence/hash in EncryptedSharedPreferences protected by the Android Keystore key and uses a synchronous commit plus monotonic update checks. It detects latest-event deletion or database rollback after a checkpoint has been established, along with chain edits and sequence discontinuity. A crash between the Room commit and anchor commit can leave the database ahead; verification reconciles forward only when the old anchored checkpoint still matches the verified chain. A nested Room transaction can update the checkpoint before its outer transaction commits; an outer rollback can therefore cause a fail-closed mismatch. First installation/legacy sealing has no historical external checkpoint. Restoring/replacing both app-private data and its encrypted preferences, or resetting/replacing the Keystore key, can roll back or remove the checkpoint. This is tamper-evident, not tamper-proof. Legacy records are redacted and sealed on verification/write.


## Finalization notes

- Reusing a `(taskId, callId)` now requires the incoming tool name, action, and argument digest to match the persisted request; changed scope is rejected. Resource/reason fields are redacted before persistence.
- A shell process with a non-zero exit code is reported as a tool failure, retaining the actual `exit=<code>` output instead of signaling success. This source change has a regression test, which remains unexecuted until an Android SDK-enabled build is available.
- The audit chain is transactionally appended and checked within the Room database transaction. SHA-256 chaining is tamper-evident under the documented limits, not tamper-proof.
- Raw SQLite smoke verified migration SQL data retention through 1→2→3→4. Room/KSP schema validation and Room migration tests remain unverified.


## M4 blocker-resolution addendum

- STDIO server launch requests are persisted as synthetic task/tool records. The tool ledger stores only server ID and configuration digest; the actual command/environment remains in encrypted configuration storage. Allow/Deny/Cancel/expiry are provided by the existing approval repository/UI; Continue validates the digest and dispatches through `PermissionGateway`. Process death after a claimed launch is `UNKNOWN` and is not replayed.
- Added a deterministic STDIO fixture test that checks no marker process starts before approval and checks the approved MCP initialize/tools-list handshake. It is authored but not run here.
- A Keystore-backed audit head checkpoint implementation is installed as the default audit repository anchor during application startup. Tests were added for checkpoint recreation and latest-event deletion detection; not run without SDK.
