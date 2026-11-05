package com.cosmic.ai.runtime.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.cosmic.ai.CosmicApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AgentTaskCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AgentTaskWork.INPUT_TASK_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as? CosmicApplication)?.agentRuntime?.cancelTask(taskId)
                WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(AgentTaskWork.uniqueName(taskId))
            } finally {
                pending.finish()
            }
        }
    }
}
