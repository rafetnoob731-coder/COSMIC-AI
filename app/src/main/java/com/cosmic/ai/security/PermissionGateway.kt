// language: kotlin, file: PermissionGateway.kt, runtime: pure Kotlin, target: COSMIC SECURITY M3
package com.cosmic.ai.security

/**
 * Permission levels (Milestone 3):
 * READ    — read project files
 * WRITE   — create/modify project files
 * EXECUTE — terminal / process execution
 * ADMIN   — destructive / policy changes / unrestricted paths
 */
enum class PermissionLevel {
    READ, WRITE, EXECUTE, ADMIN, NETWORK
}

data class SecurityPolicy(
    val allowRead: Boolean = true,
    val allowWrite: Boolean = false,
    val allowExecute: Boolean = false,
    val allowAdmin: Boolean = false,
    val allowNetwork: Boolean = true,
    val requireApprovalRead: Boolean = false,
    val requireApprovalWrite: Boolean = true,
    val requireApprovalExecute: Boolean = true,
    val requireApprovalAdmin: Boolean = true,
    val requireApprovalNetwork: Boolean = false,
    val allowLanCleartext: Boolean = false,
    val blockedCommandPatterns: List<String> = listOf(
        "rm -rf /", "mkfs", "dd if=", ":(){", "shutdown", "reboot", "su ",
        "chmod 777 /", "chown -R", "> /dev/"
    )
)

sealed class PermissionDecision {
    data object Allow : PermissionDecision()
    data class Deny(val reason: String) : PermissionDecision()
    data class NeedsApproval(val reason: String, val level: PermissionLevel) : PermissionDecision()
}

object SensitiveTextRedactor {
    private val credentialPattern = Regex(
        "(?i)(\\b\"?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|password|secret)\"?\\s*[:=]\\s*[\"']?)(?:Bearer\\s+)?([^\\s,;}\"']+)"
    )

    fun redact(text: String): String = credentialPattern.replace(text) { match ->
        "${match.groupValues[1]}[REDACTED]"
    }
}

class ApprovalAuthorization internal constructor(
    val requestId: String,
    val taskId: String,
    val callId: String,
    val action: String,
    val scopeHash: String,
    val expiresAt: Long
)

data class AuditEntry(
    val id: String,
    val level: PermissionLevel,
    val action: String,
    val detail: String,
    val decision: String,
    val at: Long = System.currentTimeMillis()
)

/**
 * Pure Kotlin permission gateway + in-memory audit ring buffer.
 * Android UI observes via bridge; no Android imports here.
 */
class PermissionGateway(
    private var policy: SecurityPolicy = SecurityPolicy(),
    private val auditCapacity: Int = 500
) {
    private val audit = ArrayDeque<AuditEntry>(auditCapacity)
    private val issuedApprovals = java.util.IdentityHashMap<ApprovalAuthorization, Boolean>()

    @Synchronized
    fun issueApprovalAuthorization(requestId: String, taskId: String, callId: String, action: String, scopeHash: String, expiresAt: Long): ApprovalAuthorization {
        val now = System.currentTimeMillis()
        issuedApprovals.keys.removeAll { it.expiresAt <= now }
        require(expiresAt > now) { "Approval authorization is already expired" }
        val token = ApprovalAuthorization(requestId, taskId, callId, action, scopeHash, expiresAt)
        issuedApprovals[token] = true
        return token
    }

    @Synchronized
    fun update(policy: SecurityPolicy) {
        this.policy = policy
        record(PermissionLevel.ADMIN, "policy_update", policy.toString(), "Allow")
    }

    @Synchronized
    fun currentPolicy(): SecurityPolicy = policy

    @Synchronized
    fun auditLog(): List<AuditEntry> = audit.toList()

    @Synchronized
    fun check(
        level: PermissionLevel,
        action: String,
        detail: String = "",
        policyOverride: SecurityPolicy? = null,
        approval: ApprovalAuthorization? = null,
        expectedTaskId: String? = null,
        expectedCallId: String? = null,
        approvalScopeHash: String? = null
    ): PermissionDecision {
        val activePolicy = policyOverride ?: policy
        val normalizedAction = action.take(120)
        val tokenIssued = approval != null && issuedApprovals.remove(approval) == true
        val approvalMatches = tokenIssued && approval != null && approval.expiresAt > System.currentTimeMillis() &&
            approval.action == normalizedAction && approval.scopeHash == (approvalScopeHash ?: detail) &&
            approval.taskId == expectedTaskId && approval.callId == expectedCallId
        val policyDecision = when (level) {
            PermissionLevel.READ -> when {
                !activePolicy.allowRead -> PermissionDecision.Deny("READ disabled")
                activePolicy.requireApprovalRead -> PermissionDecision.NeedsApproval("Read: $action", level)
                else -> PermissionDecision.Allow
            }

            PermissionLevel.WRITE -> when {
                !activePolicy.allowWrite -> PermissionDecision.Deny("WRITE disabled")
                activePolicy.requireApprovalWrite -> PermissionDecision.NeedsApproval("Write: $action $detail", level)
                else -> PermissionDecision.Allow
            }

            PermissionLevel.EXECUTE -> when {
                !activePolicy.allowExecute -> PermissionDecision.Deny("EXECUTE disabled")
                activePolicy.blockedCommandPatterns.any { detail.contains(it, ignoreCase = true) } ->
                    PermissionDecision.Deny("Command blocked by policy pattern")
                activePolicy.requireApprovalExecute || looksDestructive(detail) ->
                    PermissionDecision.NeedsApproval("Execute: $detail", level)
                else -> PermissionDecision.Allow
            }

            PermissionLevel.ADMIN -> when {
                !activePolicy.allowAdmin && activePolicy.requireApprovalAdmin ->
                    PermissionDecision.NeedsApproval("Admin: $action $detail", level)
                !activePolicy.allowAdmin -> PermissionDecision.Deny("ADMIN disabled")
                else -> PermissionDecision.Allow
            }

            PermissionLevel.NETWORK -> when {
                !activePolicy.allowNetwork -> PermissionDecision.Deny("NETWORK disabled")
                activePolicy.requireApprovalNetwork -> PermissionDecision.NeedsApproval("Network: $action $detail", level)
                else -> PermissionDecision.Allow
            }
        }
        val decision = if (approvalMatches && policyDecision is PermissionDecision.NeedsApproval) PermissionDecision.Allow else policyDecision
        val auditDetail = when {
            approvalMatches && decision is PermissionDecision.Allow -> "approval:${approval?.requestId}"
            approvalScopeHash != null -> approvalScopeHash
            else -> detail
        }
        return logAnd(level, action, auditDetail, decision)
    }

    /** Validate relative path — reject traversal and absolute escapes. */
    fun validateRelativePath(path: String): PermissionDecision {
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty()) return PermissionDecision.Deny("Empty path")
        if (normalized.contains('\u0000')) return PermissionDecision.Deny("Null byte in path blocked")
        if (normalized.startsWith("/")) return PermissionDecision.Deny("Absolute path not allowed")
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return PermissionDecision.Deny("Drive letter not allowed")
        if (normalized.contains("%2e", ignoreCase = true) || normalized.contains("%2f", ignoreCase = true)) {
            return PermissionDecision.Deny("Encoded path traversal blocked")
        }
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == ".." }) return PermissionDecision.Deny("Path traversal blocked")
        if (segments.any { it == "." && segments.size == 1 }) return PermissionDecision.Allow
        return PermissionDecision.Allow
    }

    fun allowLanCleartext(): Boolean = policy.allowLanCleartext

    private fun looksDestructive(cmd: String): Boolean {
        val c = cmd.lowercase()
        return listOf(
            "rm ", "rm\t", "unlink ", "rmdir ", "del ", "format ",
            "drop table", "truncate ", "git push --force", "git reset --hard",
            "git clean -fd", "chmod -R", "chown "
        ).any { c.contains(it) }
    }

    @Synchronized
    private fun logAnd(
        level: PermissionLevel,
        action: String,
        detail: String,
        decision: PermissionDecision
    ): PermissionDecision {
        val label = when (decision) {
            is PermissionDecision.Allow -> "Allow"
            is PermissionDecision.Deny -> "Deny:${decision.reason}"
            is PermissionDecision.NeedsApproval -> "NeedsApproval:${decision.reason}"
        }
        record(level, action, detail.take(500), label)
        return decision
    }

    @Synchronized
    private fun record(level: PermissionLevel, action: String, detail: String, decision: String) {
        if (audit.size >= auditCapacity) audit.removeFirst()
        audit.addLast(
            AuditEntry(
                id = "aud_${System.currentTimeMillis()}_${audit.size}",
                level = level,
                action = action,
                detail = SensitiveTextRedactor.redact(detail).take(500),
                decision = decision
            )
        )
    }
}
