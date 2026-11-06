package com.cosmic.ai.tools

import android.app.Application
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.runtime.tools.ToolResult
import com.cosmic.ai.tools.git.GitDiffTool
import com.cosmic.ai.tools.git.GitLogTool
import com.cosmic.ai.tools.git.GitStatusTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = GitToolsTest.TestApplication::class, sdk = [35])
class GitToolsTest {
    class TestApplication : Application()

    @Test
    fun gitToolsDenyExecutionWhenTerminalForbidden() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val policy = PermissionPolicy(allowTerminal = false)

        val statusResult = GitStatusTool(context).execute("{}", policy, null)
        assertTrue(statusResult is ToolResult.Failure)
        assertEquals("Terminal permission denied", (statusResult as ToolResult.Failure).error)

        val diffResult = GitDiffTool(context).execute("{}", policy, null)
        assertTrue(diffResult is ToolResult.Failure)
        assertEquals("Terminal permission denied", (diffResult as ToolResult.Failure).error)

        val logResult = GitLogTool(context).execute("{}", policy, null)
        assertTrue(logResult is ToolResult.Failure)
        assertEquals("Terminal permission denied", (logResult as ToolResult.Failure).error)
    }

    @Test
    fun gitToolsReportFailureWhenDirectoryIsNotGitRepository() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val policy = PermissionPolicy(allowTerminal = true)

        val statusResult = GitStatusTool(context).execute("{}", policy, null)
        assertTrue(statusResult is ToolResult.Failure)

        val diffResult = GitDiffTool(context).execute("{}", policy, null)
        assertTrue(diffResult is ToolResult.Failure)

        val logResult = GitLogTool(context).execute("{}", policy, null)
        assertTrue(logResult is ToolResult.Failure)
    }
}
