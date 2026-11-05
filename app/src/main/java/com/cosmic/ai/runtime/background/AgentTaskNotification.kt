package com.cosmic.ai.runtime.background

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.cosmic.ai.CosmicApplication
import com.cosmic.ai.R
import com.cosmic.ai.ui.MainActivity

object AgentTaskNotification {
    fun foregroundInfo(context: Context, taskId: String, progress: String): ForegroundInfo {
        val id = taskId.hashCode().let { if (it == 0) 1 else it }
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(context, id,
            Intent(context, AgentTaskCancelReceiver::class.java).putExtra(AgentTaskWork.INPUT_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CosmicApplication.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_cosmic_notification)
            .setContentTitle("COSMIC CODEX task running")
            .setContentText(progress.take(120))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
        return ForegroundInfo(id, notification)
    }
}
