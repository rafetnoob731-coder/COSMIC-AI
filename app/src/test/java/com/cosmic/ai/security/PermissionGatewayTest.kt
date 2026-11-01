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
    fun lanCleartextDefaultOff() {
        val g = PermissionGateway()
        assertFalse(g.allowLanCleartext())
    }
}
