package com.cosmic.ai.runtime.mcp

import android.app.Application
import androidx.room.Room
import com.cosmic.ai.data.local.db.ApprovalLifecycleRepository
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.domain.mcp.McpServerConfig
import com.cosmic.ai.domain.mcp.McpTransport
import com.cosmic.ai.security.PermissionGateway
import com.cosmic.ai.security.SecurityPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = McpLaunchApprovalTest.TestApplication::class, sdk = [35])
class McpLaunchApprovalTest {
    class TestApplication : Application()
    private var db: CosmicDatabase? = null
    private var databaseName: String? = null

    @After fun cleanup() {
        db?.close()
        databaseName?.let { RuntimeEnvironment.getApplication().deleteDatabase(it) }
    }

    @Test fun stdioProcessDoesNotStartUntilPersistedApprovalIsClaimed() = runBlocking {
        val name = "mcp-launch-${UUID.randomUUID()}.db"
        databaseName = name
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), CosmicDatabase::class.java, name)
            .allowMainThreadQueries().build()
        db = database
        val approvals = ApprovalLifecycleRepository(database)
        val gateway = PermissionGateway(SecurityPolicy(allowExecute = true, requireApprovalExecute = true))
        val manager = McpClientManager(gateway, null, approvals)
        val marker = File(RuntimeEnvironment.getApplication().cacheDir, "mcp-start-${UUID.randomUUID()}")
        val fixture = "if [ ! -e '${marker.absolutePath}' ]; then touch '${marker.absolutePath}'; fi; while IFS= read -r line; do case \"\$line\" in *initialize*) printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}}';; *tools/list*) printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[]}}';; esac; done"
        val config = McpServerConfig(
            id = "fixture-${UUID.randomUUID()}", name = "Approval fixture", transport = McpTransport.STDIO,
            command = "sh", args = listOf("-c", fixture)
        )
        manager.upsertConfiguration(config)
        manager.connect(config)
        val request = withTimeout(5_000) {
            approvals.observePending().first { requests -> requests.any { it.toolName == "mcp_server_launch" } }
                .first { it.toolName == "mcp_server_launch" }
        }
        assertFalse("STDIO child process launched before approval", marker.exists())
        approvals.decide(request.id, allow = true, mode = "ALLOW_ONCE")
        val (claimed, call) = approvals.claimApproved(request.id)
        val token = gateway.issueApprovalAuthorization(
            requestId = claimed.id, taskId = claimed.taskId, callId = claimed.callId,
            action = claimed.action, scopeHash = manager.launchScope(config), expiresAt = claimed.expiresAt
        )
        val result = manager.connectApproved(config, token, claimed.taskId, claimed.callId, manager.launchScope(config))
        assertTrue("Approved MCP launch did not connect: $result", result is com.cosmic.ai.runtime.tools.ToolResult.Success)
        assertTrue("Approval did not start the MCP process", marker.exists())
        assertTrue("Approval call was not the persisted launch call", call.argumentsJson.contains(config.id))
        manager.disconnect(config.id)
        marker.delete()
    }

    @Test fun deniedStdioLaunchNeverCreatesChildProcess() = runBlocking {
        val name = "mcp-denied-${UUID.randomUUID()}.db"
        databaseName = name
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), CosmicDatabase::class.java, name)
            .allowMainThreadQueries().build()
        db = database
        val approvals = ApprovalLifecycleRepository(database)
        val gateway = PermissionGateway(SecurityPolicy(allowExecute = true, requireApprovalExecute = true))
        val manager = McpClientManager(gateway, null, approvals)
        val marker = File(RuntimeEnvironment.getApplication().cacheDir, "mcp-denied-${UUID.randomUUID()}")
        val config = McpServerConfig(
            id = "denied-${UUID.randomUUID()}", name = "Denied fixture", transport = McpTransport.STDIO,
            command = "sh", args = listOf("-c", "touch '${marker.absolutePath}'; sleep 2")
        )
        manager.connect(config)
        val request = withTimeout(5_000) {
            approvals.observePending().first { requests -> requests.any { it.toolName == "mcp_server_launch" } }
                .first { it.toolName == "mcp_server_launch" }
        }
        approvals.decide(request.id, allow = false, mode = "DENY")
        assertFalse("Denied launch must not start a process", marker.exists())
        assertTrue(database.toolExecutionDao().get(request.taskId, request.callId)?.state == "FAILED")
    }

}
