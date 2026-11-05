package com.cosmic.ai.runtime.background

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AgentTaskWorkTest.TestApplication::class, sdk = [35])
class AgentTaskWorkTest {
    class TestApplication : Application()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun initializeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    @After fun cancelTestWork() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
    }

    @Test fun repeatedSchedulingUsesOneUniqueWorkRecordPerTaskId() {
        AgentTaskWork.enqueue(context, "task-same")
        AgentTaskWork.enqueue(context, "task-same")
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AgentTaskWork.uniqueName("task-same")).get()
        assertEquals(1, infos.size)
    }

    @Test fun distinctTasksUseDistinctUniqueWorkNames() {
        assertEquals("cosmic-agent-task-a", AgentTaskWork.uniqueName("a"))
        assertEquals("cosmic-agent-task-b", AgentTaskWork.uniqueName("b"))
    }
}
