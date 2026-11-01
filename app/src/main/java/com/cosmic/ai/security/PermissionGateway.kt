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
    READ, WRITE, EXECUTE, ADMIN
}

data class SecurityPolicy(
    val allowRead: Boolean = true,
    val allowWrite: Boolean = false,
    val allowExecute: Boolean = false,
    val allowAdmin: Boolean = false,
    val requireApprovalWrite: Boolean = true,
    val requireApprovalExecute: Boolean = true,
    val requireApprovalAdmin: Boolean = true,
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

    fun update(policy: SecurityPolicy) {
        this.policy = policy
        record(PermissionLevel.ADMIN, "policy_update", policy.toString(), "Allow")
    }

    fun currentPolicy(): SecurityPolicy = policy

    fun auditLog(): List<AuditEntry> = audit.toList()

    fun check(level: PermissionLevel, action: String, detail: String = ""): PermissionDecision {
        val decision = when (level) {
            PermissionLevel.READ ->
                if (policy.allowRead) PermissionDecision.Allow
                else PermissionDecision.Deny("READ disabled")

            PermissionLevel.WRITE -> when {
                !policy.allowWrite -> PermissionDecision.Deny("WRITE disabled")
                policy.requireApprovalWrite -> PermissionDecision.NeedsApproval("Write: $action $detail", level)
                else -> PermissionDecision.Allow
            }

            PermissionLevel.EXECUTE -> {
                if (!policy.allowExecute) return logAnd(level, action, detail, PermissionDecision.Deny("EXECUTE disabled"))
                val blocked = policy.blockedCommandPatterns.any { detail.contains(it, ignoreCase = true) }
                when {
                    blocked -> PermissionDecision.Deny("Command blocked by policy pattern")
                    policy.requireApprovalExecute || looksDestructive(detail) ->
                        PermissionDecision.NeedsApproval("Execute: $detail", level)
                    else -> PermissionDecision.Allow
                }
            }

            PermissionLevel.ADMIN -> when {
                !policy.allowAdmin && policy.requireApprovalAdmin ->
                    PermissionDecision.NeedsApproval("Admin: $action $detail", level)
                !policy.allowAdmin -> PermissionDecision.Deny("ADMIN disabled")
                else -> PermissionDecision.Allow
            }
        }
        return logAnd(level, action, detail, decision)
    }

    /** Validate relative path — reject traversal and absolute escapes. */
    fun validateRelativePath(path: String): PermissionDecision {
        val normalized = path.replace('\\', '/').trim()
        if (normalized.isEmpty()) return PermissionDecision.Deny("Empty path")
        if (normalized.startsWith("/")) return PermissionDecision.Deny("Absolute path not allowed")
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

    private fun record(level: PermissionLevel, action: String, detail: String, decision: String) {
        if (audit.size >= auditCapacity) audit.removeFirst()
        audit.addLast(
            AuditEntry(
                id = "aud_${System.currentTimeMillis()}_${audit.size}",
                level = level,
                action = action,
                detail = detail,
                decision = decision
            )
        )
    }
}
