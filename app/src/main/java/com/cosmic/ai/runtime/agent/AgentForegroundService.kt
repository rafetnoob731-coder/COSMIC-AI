// language: kotlin, file: AgentForegroundService.kt, runtime: FGS dataSync, target: long agent tasks
package com.cosmic.ai.runtime.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.R
import com.cosmic.ai.ui.MainActivity

class AgentForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Agent running")
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CosmicApplication.CHANNEL_AGENT)
            .setContentTitle("COSMIC CODEX")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 4201
    }
}
