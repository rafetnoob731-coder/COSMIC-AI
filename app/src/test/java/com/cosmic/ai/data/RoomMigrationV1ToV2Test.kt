package com.cosmic.ai.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.cosmic.ai.data.local.db.CosmicDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RoomMigrationV1ToV2Test.TestApplication::class, sdk = [35])
class RoomMigrationV1ToV2Test {
    class TestApplication : Application()
    private val databaseName = "cosmic-v1-migration-test.db"
    private lateinit var database: CosmicDatabase

    @Before fun createActualV1Database() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
        val schema = JSONObject(javaClass.classLoader!!.getResource("schemas/cosmic-db-v1.json")!!.readText())
            .getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (index in 0 until setup.length()) raw.execSQL(setup.getString(index))
        raw.execSQL("INSERT INTO tasks (id, sessionId, status, stepsJson, progressMessage, createdAt, updatedAt) VALUES ('legacy-task', 'legacy-session', 'QUEUED', '[]', 'Legacy task', 10, 20)")
        raw.version = 1
        raw.close()
        database = Room.databaseBuilder(context, CosmicDatabase::class.java, databaseName)
            .addMigrations(CosmicDatabase.MIGRATION_1_2, CosmicDatabase.MIGRATION_2_3, CosmicDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
    }

    @After fun closeDatabase() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test fun migratesV1TaskWithoutDataLossAndAddsRecoveryFields() = runBlocking {
        val migrated = database.taskDao().getById("legacy-task")
        assertEquals("legacy-session", migrated?.sessionId)
        assertEquals("QUEUED", migrated?.status)
        assertEquals("Legacy task", migrated?.progressMessage)
        assertEquals(12, migrated?.maxSteps)
        assertEquals("[]", migrated?.transitionHistoryJson)
        assertNull(migrated?.providerConfigJson)
        database.openHelper.writableDatabase.query("SELECT * FROM tool_executions").use { rows ->
            assertEquals(0, rows.count)
        }
        database.openHelper.writableDatabase.query("SELECT * FROM approval_requests").use { rows -> assertEquals(0, rows.count) }
        database.openHelper.writableDatabase.query("SELECT * FROM security_audit_events").use { rows -> assertEquals(0, rows.count) }
    }
}
