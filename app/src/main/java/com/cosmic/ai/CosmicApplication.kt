// language: kotlin, file: CosmicApplication.kt, runtime: Android 8+, target: app process
package com.cosmic.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.keystore.SecureStore
import com.cosmic.ai.runtime.agent.AgentRuntime
import com.cosmic.ai.runtime.mcp.McpClientManager
import com.cosmic.ai.security.PermissionGateway

class CosmicApplication : Application(), Configuration.Provider {

    lateinit var database: CosmicDatabase
        private set
    lateinit var secureStore: SecureStore
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
        database = CosmicDatabase.build(this)
        secureStore = SecureStore(this)
        mcpManager = McpClientManager()
        permissionGateway = PermissionGateway()
        agentRuntime = AgentRuntime(this, database, secureStore, mcpManager)
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
