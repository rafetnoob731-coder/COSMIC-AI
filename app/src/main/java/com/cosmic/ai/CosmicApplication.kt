// language: kotlin, file: CosmicApplication.kt, runtime: Android 8+, target: app process
package com.cosmic.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.db.ApprovalLifecycleRepository
import com.cosmic.ai.data.local.keystore.SecureStore
import com.cosmic.ai.data.local.keystore.McpServerConfigStore
import com.cosmic.ai.runtime.agent.AgentRuntime
import com.cosmic.ai.runtime.mcp.McpClientManager
import com.cosmic.ai.security.PermissionGateway
import com.cosmic.ai.domain.agent.PermissionPolicy
import com.cosmic.ai.data.local.datastore.AppPreferences
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

class CosmicApplication : Application(), Configuration.Provider {

    lateinit var database: CosmicDatabase
        private set
    lateinit var secureStore: SecureStore
        private set
    lateinit var mcpServerConfigStore: McpServerConfigStore
        private set
    lateinit var agentRuntime: AgentRuntime
        private set
    lateinit var mcpManager: McpClientManager
        private set
    lateinit var permissionGateway: PermissionGateway
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        WorkManager.initialize(this, workManagerConfiguration)
        database = CosmicDatabase.build(this)
        secureStore = SecureStore(this)
        mcpServerConfigStore = McpServerConfigStore(secureStore)
        com.cosmic.ai.data.local.db.SecurityAuditRepository.installDefaultAnchor(
            com.cosmic.ai.data.local.keystore.SecureAuditHeadAnchor(secureStore)
        )
        permissionGateway = PermissionGateway()
        val approvalLifecycle = ApprovalLifecycleRepository(database)
        mcpManager = McpClientManager(
            permissionGateway,
            com.cosmic.ai.data.local.db.SecurityAuditRepository(database),
            approvalLifecycle
        )
        runCatching { mcpManager.restoreConfigurations(mcpServerConfigStore.load()) }
            .onFailure { android.util.Log.e("CosmicMcpConfig", "Could not restore encrypted MCP configuration (${it.javaClass.simpleName})") }
        agentRuntime = AgentRuntime(
            this, database, secureStore::getSecret, mcpManager,
            permissionGateway = permissionGateway,
            approvalLifecycleRepository = approvalLifecycle
        )
        val appPreferences = AppPreferences(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(
                appPreferences.allowFileRead,
                appPreferences.allowFileWrite,
                appPreferences.allowTerminal,
                appPreferences.allowNetwork,
                appPreferences.requireApproval
            ) { read, write, terminal, network, approval ->
                PermissionPolicy(
                    allowFileRead = read,
                    allowFileWrite = write,
                    allowTerminal = terminal,
                    allowNetwork = network,
                    requireApprovalForDestructive = approval
                )
            }.collect(agentRuntime::updatePolicy)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val auditIntegrity = com.cosmic.ai.data.local.db.SecurityAuditRepository(database).verifyIntegrity()
                check(auditIntegrity.valid) { "Security audit chain invalid at ${auditIntegrity.firstInvalidSequence}" }
                val approvals = approvalLifecycle
                approvals.expirePending()
                approvals.recoverInterruptedExecutions()
            } catch (error: Exception) {
                android.util.Log.e("CosmicSecurityRecovery", "Could not reconcile approval state (${error.javaClass.simpleName})")
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Restore WorkManager requests for tasks persisted before a process interruption.
            try {
                agentRuntime.recoverInterruptedTasks()
            } catch (error: Exception) {
                android.util.Log.e("CosmicTaskRecovery", "Could not reconcile interrupted tasks (${error.javaClass.simpleName})")
            }
        }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_AGENT,
                "Agent Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background agent execution" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    companion object {
        const val CHANNEL_AGENT = "cosmic_agent"
        lateinit var instance: CosmicApplication
            private set
    }
}
