// language: kotlin, file: PermissionGatewayTest.kt, runtime: JUnit, target: COSMIC CORE unit tests
package com.cosmic.ai.security

import org.junit.Assert.*
import org.junit.Test

class PermissionGatewayTest {

    @Test
    fun readAllowedByDefault() {
        val g = PermissionGateway()
        val d = g.check(PermissionLevel.READ, "read_file", "src/Main.kt")
        assertTrue(d is PermissionDecision.Allow)
    }

    @Test
    fun writeRequiresApprovalByDefault() {
        val g = PermissionGateway(SecurityPolicy(allowWrite = true, requireApprovalWrite = true))
        val d = g.check(PermissionLevel.WRITE, "write_file", "src/Main.kt")
        assertTrue(d is PermissionDecision.NeedsApproval)
    }

    @Test
    fun writeDeniedWhenDisabled() {
        val g = PermissionGateway(SecurityPolicy(allowWrite = false))
        val d = g.check(PermissionLevel.WRITE, "write_file", "x")
        assertTrue(d is PermissionDecision.Deny)
    }

    @Test
    fun pathTraversalBlocked() {
        val g = PermissionGateway()
        val d = g.validateRelativePath("../etc/passwd")
        assertTrue(d is PermissionDecision.Deny)
        assertTrue(g.validateRelativePath("src/\u0000/evil.kt") is PermissionDecision.Deny)
        assertTrue(g.validateRelativePath("C:/Windows/system32") is PermissionDecision.Deny)
        assertTrue(g.validateRelativePath("src/%2e%2e/etc/passwd") is PermissionDecision.Deny)
    }

    @Test
    fun absolutePathBlocked() {
        val g = PermissionGateway()
        val d = g.validateRelativePath("/etc/passwd")
        assertTrue(d is PermissionDecision.Deny)
    }

    @Test
    fun relativePathOk() {
        val g = PermissionGateway()
        val d = g.validateRelativePath("src/main/java/App.kt")
        assertTrue(d is PermissionDecision.Allow)
    }

    @Test
    fun blockedCommandDenied() {
        val g = PermissionGateway(SecurityPolicy(allowExecute = true, requireApprovalExecute = false))
        val d = g.check(PermissionLevel.EXECUTE, "run", "rm -rf /")
        assertTrue(d is PermissionDecision.Deny)
    }

    @Test
    fun auditLogRecords() {
        val g = PermissionGateway()
        g.check(PermissionLevel.READ, "list", ".")
        assertTrue(g.auditLog().isNotEmpty())
    }

    @Test
    fun auditDetailRedactsCredentialsBeforeStorage() {
        val g = PermissionGateway()
        g.check(PermissionLevel.READ, "inspect", "Authorization: Bearer abc123 {\"api_key\":\"xyz789\"}")
        val detail = g.auditLog().single().detail
        assertFalse(detail.contains("abc123"))
        assertFalse(detail.contains("xyz789"))
        assertTrue(detail.contains("[REDACTED]"))
    }

    @Test
    fun lanCleartextDefaultOff() {
        val g = PermissionGateway()
        assertFalse(g.allowLanCleartext())
    }

    @Test
    fun networkCanBeDeniedByPerOperationPolicy() {
        val gateway = PermissionGateway()
        val decision = gateway.check(
            PermissionLevel.NETWORK,
            "search",
            "docs query",
            SecurityPolicy(allowNetwork = false)
        )
        assertTrue(decision is PermissionDecision.Deny)
    }

    @Test
    fun operationPolicyOverrideDoesNotChangeStoredPolicy() {
        val gateway = PermissionGateway()
        val decision = gateway.check(
            PermissionLevel.EXECUTE,
            "run_command",
            "printf ok",
            SecurityPolicy(allowExecute = true, requireApprovalExecute = false)
        )
        assertTrue(decision is PermissionDecision.Allow)
        assertFalse(gateway.currentPolicy().allowExecute)
    }

    @Test
    fun destructiveExecutionStillRequiresApprovalWithOverride() {
        val gateway = PermissionGateway()
        val decision = gateway.check(
            PermissionLevel.EXECUTE,
            "run_command",
            "rm -r project",
            SecurityPolicy(allowExecute = true, requireApprovalExecute = false)
        )
        assertTrue(decision is PermissionDecision.NeedsApproval)
    }


    @Test
    fun approvalGrantOnlyAllowsExactActionAndScope() {
        val gateway = PermissionGateway()
        val policy = SecurityPolicy(allowWrite = true, requireApprovalWrite = true)
        val grant = gateway.issueApprovalAuthorization("approval-id", "task-id", "call-id", "write_file", "exact-hash", System.currentTimeMillis() + 60_000)
        assertTrue(gateway.check(PermissionLevel.WRITE, "write_file", "exact-hash", policy, grant, "task-id", "call-id") is PermissionDecision.Allow)
        assertTrue(gateway.check(PermissionLevel.WRITE, "write_file", "exact-hash", policy, grant, "task-id", "call-id") is PermissionDecision.NeedsApproval)
        val other = gateway.issueApprovalAuthorization("approval-id-2", "task-id", "call-id-2", "write_file", "exact-hash", System.currentTimeMillis() + 60_000)
        assertTrue(gateway.check(PermissionLevel.WRITE, "write_file", "other-hash", policy, other, "task-id", "call-id-2") is PermissionDecision.NeedsApproval)
        val wrongTask = gateway.issueApprovalAuthorization("approval-id-3", "task-id", "call-id-3", "different_tool", "exact-hash", System.currentTimeMillis() + 60_000)
        assertTrue(gateway.check(PermissionLevel.WRITE, "different_tool", "exact-hash", policy, wrongTask, "other-task", "call-id-3") is PermissionDecision.NeedsApproval)
    }

    @Test
    fun expiredApprovalDoesNotAuthorize() {
        val gateway = PermissionGateway()
        val policy = SecurityPolicy(allowWrite = true, requireApprovalWrite = true)
        val grant = runCatching { gateway.issueApprovalAuthorization("approval-id", "task", "call", "write_file", "exact-hash", System.currentTimeMillis() - 1) }.getOrNull()
        assertNull(grant)
    }

}
