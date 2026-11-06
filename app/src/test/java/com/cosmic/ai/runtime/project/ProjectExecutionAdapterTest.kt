package com.cosmic.ai.runtime.project

import android.app.Application
import com.cosmic.ai.core.project.ProjectBuildPlan
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.security.ApprovalAuthorization
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectExecutionAdapterTest.TestApplication::class, sdk = [35])
class ProjectExecutionAdapterTest {
    class TestApplication : Application()

    @Test
    fun executionDeniedWhenPolicyForbidsTerminal() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val workspace = InternalProjectWorkspaceProvider.create(context, "test-exec-1")
        val adapter = LocalProjectExecutionAdapter(context)

        val plan = ProjectBuildPlan("package.json", "", ProjectBuildPlan.Action.BUILD, listOf("sh", "-c", "echo ok"), false, false)
        val policy = PermissionPolicy(allowTerminal = false)

        val report = adapter.execute(workspace, plan, policy)
        assertEquals(ExecutionStatus.PERMISSION_DENIED, report.status)
        assertTrue(report.errorMessage?.contains("Terminal") == true)
    }

    @Test
    fun executionDeniedWhenNetworkRequiredButForbidden() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val workspace = InternalProjectWorkspaceProvider.create(context, "test-exec-2")
        val adapter = LocalProjectExecutionAdapter(context)

        val plan = ProjectBuildPlan("package.json", "", ProjectBuildPlan.Action.BUILD, listOf("sh", "-c", "echo ok"), requiresNetwork = true, requiresApproval = false)
        val policy = PermissionPolicy(allowTerminal = true, allowNetwork = false)

        val report = adapter.execute(workspace, plan, policy)
        assertEquals(ExecutionStatus.PERMISSION_DENIED, report.status)
        assertTrue(report.errorMessage?.contains("Network") == true)
    }

    @Test
    fun executionDeniedWhenApprovalRequiredButMissing() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val workspace = InternalProjectWorkspaceProvider.create(context, "test-exec-3")
        val adapter = LocalProjectExecutionAdapter(context)

        val plan = ProjectBuildPlan("package.json", "", ProjectBuildPlan.Action.BUILD, listOf("sh", "-c", "echo ok"), requiresNetwork = false, requiresApproval = true)
        val policy = PermissionPolicy(allowTerminal = true, allowNetwork = true)

        val report = adapter.execute(workspace, plan, policy, authorization = null)
        assertEquals(ExecutionStatus.PERMISSION_DENIED, report.status)
        assertTrue(report.errorMessage?.contains("approval") == true)
    }

    @Test
    fun executionDeniedWhenWriteRequiredForBuildButForbidden() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val workspace = InternalProjectWorkspaceProvider.create(context, "test-exec-3b")
        val adapter = LocalProjectExecutionAdapter(context)

        val plan = ProjectBuildPlan("package.json", "", ProjectBuildPlan.Action.BUILD, listOf("sh", "-c", "echo ok"), requiresNetwork = false, requiresApproval = false)
        val policy = PermissionPolicy(allowTerminal = true, allowFileWrite = false)

        val report = adapter.execute(workspace, plan, policy)
        assertEquals(ExecutionStatus.PERMISSION_DENIED, report.status)
        assertTrue(report.errorMessage?.contains("write permission") == true)
    }

    @Test
    fun workingDirectoryWithNullCharacterIsRejected() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val workspace = InternalProjectWorkspaceProvider.create(context, "test-exec-3c")
        val adapter = LocalProjectExecutionAdapter(context)

        val plan = ProjectBuildPlan("package.json", "sub/\u0000/dir", ProjectBuildPlan.Action.BUILD, listOf("sh", "-c", "echo ok"), requiresNetwork = false, requiresApproval = false)
        val policy = PermissionPolicy(allowTerminal = true, allowFileWrite = true)

        val report = adapter.execute(workspace, plan, policy)
        assertEquals(ExecutionStatus.PERMISSION_DENIED, report.status)
        assertTrue(report.errorMessage?.contains("null character") == true)
    }

    @Test
    fun runtimeUnavailableReportedWhenExecutableMissing() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val workspace = InternalProjectWorkspaceProvider.create(context, "test-exec-4")
        val mockProbe = object : AndroidRuntimeProbe() {
            // override isAvailable to simulate missing tool
        }
        val adapter = LocalProjectExecutionAdapter(context, mockProbe)

        val plan = ProjectBuildPlan("Cargo.toml", "", ProjectBuildPlan.Action.BUILD, listOf("nonexistent-tool-xyz-123", "build"), false, false)
        val policy = PermissionPolicy(allowTerminal = true, allowNetwork = true)

        val report = adapter.execute(workspace, plan, policy)
        assertEquals(ExecutionStatus.RUNTIME_UNAVAILABLE, report.status)
        assertTrue(report.errorMessage?.contains("not installed") == true)
    }

    @Test
    fun workingDirectoryTraversalIsRejected() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val workspace = InternalProjectWorkspaceProvider.create(context, "test-exec-5")
        val adapter = LocalProjectExecutionAdapter(context)

        val plan = ProjectBuildPlan("package.json", "../../escaped", ProjectBuildPlan.Action.BUILD, listOf("sh", "-c", "echo ok"), false, false)
        val policy = PermissionPolicy(allowTerminal = true)

        val report = adapter.execute(workspace, plan, policy)
        assertEquals(ExecutionStatus.PERMISSION_DENIED, report.status)
        assertTrue(report.errorMessage?.contains("escapes") == true)
    }

    @Test
    fun internalWorkspaceExecutesValidCommand() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val workspace = InternalProjectWorkspaceProvider.create(context, "test-exec-6")
        val adapter = LocalProjectExecutionAdapter(context)

        val plan = ProjectBuildPlan("build.sh", "", ProjectBuildPlan.Action.BUILD, listOf("sh", "-c", "printf 'hello project'"), false, false)
        val policy = PermissionPolicy(allowTerminal = true)

        val report = adapter.execute(workspace, plan, policy)
        assertEquals(ExecutionStatus.SUCCESS, report.status)
        assertEquals(0, report.exitCode)
        assertTrue(report.stdout.contains("hello project"))
    }

    @Test
    fun packageManagerCreatesExpectedPlans() {
        val context = RuntimeEnvironment.getApplication()
        val pm = ProjectPackageManager(context)

        val npmPlan = pm.createInstallPlan("package.json", "web", "npm")
        assertEquals(listOf("npm", "install"), npmPlan.argv)
        assertEquals("web", npmPlan.workingDirectory)
        assertTrue(npmPlan.requiresNetwork)
        assertTrue(npmPlan.requiresApproval)

        val pnpmPlan = pm.createInstallPlan("pnpm-lock.yaml", "app", "pnpm")
        assertEquals(listOf("pnpm", "install"), pnpmPlan.argv)

        val cargoPlan = pm.createInstallPlan("Cargo.toml", "crates/core", "cargo")
        assertEquals(listOf("cargo", "fetch"), cargoPlan.argv)
        assertEquals("crates/core", cargoPlan.workingDirectory)

        val goPlan = pm.createInstallPlan("go.mod", "backend", "go")
        assertEquals(listOf("go", "mod", "download"), goPlan.argv)

        val pipPlan = pm.createInstallPlan("backend/requirements.txt", "backend", "pip")
        assertEquals(listOf("python3", "-m", "pip", "install", "-r", "requirements.txt"), pipPlan.argv)
    }
}
