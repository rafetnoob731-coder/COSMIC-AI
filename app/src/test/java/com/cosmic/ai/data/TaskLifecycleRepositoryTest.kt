package com.cosmic.ai.data

import android.app.Application
import androidx.room.Room
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.db.TaskLifecycleRepository
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.domain.agent.TaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = TaskLifecycleRepositoryTest.TestApplication::class, sdk = [35])
class TaskLifecycleRepositoryTest {
    class TestApplication : Application()
    private lateinit var dbName: String
    private lateinit var db: CosmicDatabase
    private lateinit var lifecycle: TaskLifecycleRepository

    @Before fun setUp() {
        dbName = "task-lifecycle-${UUID.randomUUID()}.db"
        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), CosmicDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
        lifecycle = TaskLifecycleRepository(db)
    }

    @After fun tearDown() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    @Test fun transitionHistoryPersistsAndInvalidTransitionIsRejected() = runBlocking {
        lifecycle.create(task())
        lifecycle.transition("task", TaskStatus.STARTING, "Starting")
        lifecycle.transition("task", TaskStatus.WAITING_FOR_MODEL, "Model request")
        val result = lifecycle.transition("task", TaskStatus.RUNNING, "Receiving")
        assertEquals(TaskStatus.RUNNING.name, result.status)
        assertEquals(4, org.json.JSONArray(result.transitionHistoryJson).length())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { lifecycle.transition("task", TaskStatus.QUEUED, "invalid") }
        }
        Unit
    }

    @Test fun interruptedTaskIsRecoverableAndRunningToolIsNeverReplayed() = runBlocking {
        lifecycle.create(task())
        lifecycle.transition("task", TaskStatus.STARTING, "Starting")
        lifecycle.transition("task", TaskStatus.WAITING_FOR_TOOL, "Tool")
        lifecycle.prepareToolCall("task", "call-1", "side_effect", "{}", "hash", idempotent = true)
        assertEquals("RUNNING", lifecycle.startToolCall("task", "call-1").state)

        val recoveredIds = lifecycle.markInterruptedTasksForRecovery()
        assertEquals(listOf("task"), recoveredIds)
        assertEquals(TaskStatus.RECOVERY_REQUIRED.name, db.taskDao().getById("task")?.status)
        assertEquals("UNKNOWN", db.toolExecutionDao().get("task", "call-1")?.state)
        assertEquals("UNKNOWN", lifecycle.startToolCall("task", "call-1").state)
    }

    @Test fun reusedToolCallIdWithDifferentArgumentsIsRejected() = runBlocking {
        lifecycle.create(task())
        lifecycle.prepareToolCall("task", "call-1", "read_file", "{\"path\":\"a\"}", "hash-a", true)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { lifecycle.prepareToolCall("task", "call-1", "read_file", "{\"path\":\"b\"}", "hash-b", true) }
        }
        assertNotNull(db.toolExecutionDao().get("task", "call-1"))
    }

    private fun task() = TaskEntity(
        id = "task", sessionId = "session", status = TaskStatus.QUEUED.name,
        stepsJson = "[]", progressMessage = "Queued", createdAt = 1L, updatedAt = 1L
    )
}
