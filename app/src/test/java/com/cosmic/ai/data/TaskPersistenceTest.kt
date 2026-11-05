package com.cosmic.ai.data

import androidx.room.Room
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.db.entity.TaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TaskPersistenceTest {
    private lateinit var databaseName: String
    private lateinit var database: CosmicDatabase

    @Before fun setUp() {
        databaseName = "cosmic-task-test-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After fun tearDown() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test fun taskStateCanBeRecoveredAfterDatabaseReopen() = runBlocking {
        val task = TaskEntity(
            id = "task-1", sessionId = "session-1", status = "PAUSED",
            stepsJson = "[{\"step\":1}]", progressMessage = "Waiting for approval",
            createdAt = 100L, updatedAt = 200L
        )
        database.taskDao().upsert(task)
        database.close()

        database = openDatabase()
        val recovered = database.taskDao().getById("task-1")
        assertNotNull(recovered)
        assertEquals("PAUSED", recovered?.status)
        assertEquals("Waiting for approval", recovered?.progressMessage)
        assertEquals(task.stepsJson, recovered?.stepsJson)
    }

    private fun openDatabase() = Room.databaseBuilder(
        RuntimeEnvironment.getApplication(), CosmicDatabase::class.java, databaseName
    ).allowMainThreadQueries().build()
}
