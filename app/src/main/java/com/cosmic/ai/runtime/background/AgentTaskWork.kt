package com.cosmic.ai.runtime.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object AgentTaskWork {
    const val INPUT_TASK_ID = "task_id"
    fun uniqueName(taskId: String) = "cosmic-agent-task-$taskId"

    fun enqueue(context: Context, taskId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInputData(workDataOf(INPUT_TASK_ID to taskId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(uniqueName(taskId))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueName(taskId), ExistingWorkPolicy.KEEP, request)
    }
}
