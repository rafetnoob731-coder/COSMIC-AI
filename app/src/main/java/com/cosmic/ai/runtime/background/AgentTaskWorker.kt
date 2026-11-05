package com.cosmic.ai.runtime.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cosmic.ai.CosmicApplication
import kotlinx.coroutines.CancellationException

class AgentTaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(AgentTaskWork.INPUT_TASK_ID) ?: return Result.failure()
        return try {
            val app = applicationContext as? CosmicApplication ?: return Result.failure()
            setForeground(AgentTaskNotification.foregroundInfo(applicationContext, taskId, "Preparing task"))
            when (app.agentRuntime.runOrRecoverPersistedTask(taskId) { progress ->
                setForeground(AgentTaskNotification.foregroundInfo(applicationContext, taskId, progress))
            }) {
                TaskWorkerOutcome.FINISHED -> Result.success()
                TaskWorkerOutcome.BLOCKED -> Result.failure()
                TaskWorkerOutcome.BUSY -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}

enum class TaskWorkerOutcome { FINISHED, BLOCKED, BUSY }
