package com.cosmic.ai.runtime.tools

import android.app.Application
import android.net.Uri
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.security.PermissionGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ToolRegistryBoundaryTest.TestApplication::class, sdk = [35])
class ToolRegistryBoundaryTest {
    class TestApplication : Application()

    @Test fun everyRegisteredToolIsStoppedAtGatewayWhenItsCapabilityIsDisabled() = runBlocking {
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), PermissionGateway())
        val examples = mapOf(
            "list_files" to "{\"path\":\"\"}",
            "read_file" to "{\"path\":\"src/main.kt\"}",
            "write_file" to "{\"path\":\"src/main.kt\",\"content\":\"x\"}",
            "edit_file" to "{\"path\":\"src/main.kt\",\"old_text\":\"a\",\"new_text\":\"b\"}",
            "create_directory" to "{\"path\":\"src\"}",
            "delete_file" to "{\"path\":\"src/main.kt\"}",
            "run_command" to "{\"command\":\"printf must_not_run\"}",
            "git_status" to "{}",
            "git_diff" to "{}",
            "git_log" to "{}",
            "duckduckgo_instant_answer" to "{\"query\":\"COSMIC permission boundary test\"}"
        )
        assertTrue("Test must cover all registered tools", examples.keys == registry.definitions().map { it.name }.toSet())
        val policy = PermissionPolicy(allowFileRead = false, allowFileWrite = false, allowTerminal = false, allowNetwork = false)
        for ((name, args) in examples) {
            val result = registry.execute(name, args, policy, Uri.parse("content://not-opened"))
            assertTrue("$name escaped the permission gateway: $result", result !is ToolResult.Success)
            assertTrue("$name should fail closed pending user permission: $result",
                result is ToolResult.NeedsApproval || result is ToolResult.Failure)
        }
    }


    @Test fun fileOperationsDoNotRequestApprovalUntilAProjectScopeExists() = runBlocking {
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), PermissionGateway())
        val result = registry.execute("write_file", "{\"path\":\"site/index.html\",\"content\":\"x\"}",
            PermissionPolicy(allowFileWrite = false), null)
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Select a project folder"))
    }

    @Test fun destructiveTerminalCallAlwaysRequiresApprovalEvenIfOrdinaryExecutionIsAllowed() = runBlocking {
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), PermissionGateway())
        val policy = PermissionPolicy(allowTerminal = true, requireApprovalForDestructive = false)
        val result = registry.execute("run_command", "{\"command\":\"rm -r project\"}", policy, null)
        assertTrue(result is ToolResult.NeedsApproval)
    }


    @Test fun writeAndTerminalActionsRequestApprovalUntilExactApprovalIsPresented() = runBlocking {
        val gateway = PermissionGateway()
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), gateway)
        val deniedCapabilities = PermissionPolicy(allowFileRead = false, allowFileWrite = false, allowTerminal = false, allowNetwork = false)
        val writeArgs = """{"path":"site/index.html","content":"<h1>Hi</h1>"}"""
        val writeResult = registry.execute("write_file", writeArgs, deniedCapabilities, Uri.parse("content://not-opened"))
        assertTrue("A restricted write must create an approval request, not fail or execute: $writeResult", writeResult is ToolResult.NeedsApproval)

        val commandArgs = """{"command":"printf authorized"}"""
        val commandResult = registry.execute("run_command", commandArgs, deniedCapabilities, null)
        assertTrue("A restricted command must create an approval request: $commandResult", commandResult is ToolResult.NeedsApproval)

        val taskId = "task-approved"
        val callId = "call-approved"
        val auth = gateway.issueApprovalAuthorization(
            "request-approved", taskId, callId, "run_command", registry.approvalScope("run_command", commandArgs),
            System.currentTimeMillis() + 60_000
        )
        val approved = registry.execute("run_command", commandArgs, deniedCapabilities, null, auth, taskId, callId)
        assertTrue("Exact approval should execute the command: $approved", approved is ToolResult.Success)
        assertTrue((approved as ToolResult.Success).output.contains("exit=0"))
        assertTrue(approved.output.contains("authorized"))
    }

    @Test fun exactWriteApprovalPassesGatewayWithoutGrantingAnUnrelatedWrite() = runBlocking {
        val gateway = PermissionGateway()
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), gateway)
        val args = "{\"path\":\"site/index.html\",\"content\":\"<h1>Hi</h1>\"}"
        val taskId = "write-task"
        val callId = "write-call"
        val authorization = gateway.issueApprovalAuthorization(
            "write-request", taskId, callId, "write_file", registry.approvalScope("write_file", args),
            System.currentTimeMillis() + 60_000
        )
        // The deliberately unregistered content URI ensures this test cannot alter a real file.
        // It must pass the permission gate and fail only at the storage provider boundary.
        val result = registry.execute("write_file", args, PermissionPolicy(allowFileWrite = false),
            Uri.parse("content://not-opened"), authorization, taskId, callId)
        assertTrue("Expected the unavailable test storage provider to fail safely: $result", result is ToolResult.Failure)
        assertFalse((result as ToolResult.Failure).error.contains("permission denied", ignoreCase = true))
    }

    @Test fun malformedNullToolArgumentsAreRejectedBeforeApprovalOrExecution() = runBlocking {
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), PermissionGateway())
        val result = registry.execute("run_command", "{\"command\":null}", PermissionPolicy(allowTerminal = false), null)
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Required tool argument is missing"))
    }

    @Test fun traversalArgumentsAreRejectedBeforeAWriteApprovalCanBeIssued() = runBlocking {
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), PermissionGateway())
        val result = registry.execute("write_file", "{\"path\":\"../escape.txt\",\"content\":\"x\"}",
            PermissionPolicy(allowFileWrite = false), Uri.parse("content://not-opened"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("traversal"))
    }

    @Test fun approvalCannotAuthorizeChangedTerminalArguments() = runBlocking {
        val gateway = PermissionGateway()
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), gateway)
        val original = """{"command":"printf original"}"""
        val changed = """{"command":"printf changed"}"""
        val auth = gateway.issueApprovalAuthorization(
            "request-scope", "task-scope", "call-scope", "run_command",
            registry.approvalScope("run_command", original), System.currentTimeMillis() + 60_000
        )
        val result = registry.execute("run_command", changed,
            PermissionPolicy(allowTerminal = false, requireApprovalForDestructive = true), null,
            auth, "task-scope", "call-scope")
        assertTrue("Changed arguments must not execute: $result", result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("scope"))
    }

    @Test fun nonZeroShellExitIsReportedAsFailure() = runBlocking {
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), PermissionGateway())
        val policy = PermissionPolicy(allowTerminal = true, requireApprovalForDestructive = false)
        val result = registry.execute("run_command", "{\"command\":\"printf out; printf err >&2; exit 7\"}", policy, null)
        assertTrue("A non-zero process exit must not be reported as success: $result", result is ToolResult.Failure)
        val output = (result as ToolResult.Failure).error
        assertTrue(output.contains("exit=7"))
        assertTrue(output.contains("stdout:\nout"))
        assertTrue(output.contains("stderr:\nerr"))
    }

    @Test fun commandCannotPretendToRunInsideSelectedSafProject() = runBlocking {
        val registry = ToolRegistry(RuntimeEnvironment.getApplication(), PermissionGateway())
        val policy = PermissionPolicy(allowTerminal = true, requireApprovalForDestructive = false)
        val result = registry.execute("run_command", """{"command":"printf must_not_run"}""", policy,
            Uri.parse("content://selected-project/tree/root"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Project-scoped command execution is unavailable"))
    }
}
