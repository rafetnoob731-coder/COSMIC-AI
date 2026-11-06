package com.cosmic.ai.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.cosmic.ai.data.local.db.CosmicDatabase
import com.cosmic.ai.data.local.db.SecurityAuditRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RoomMigrationV3ToV4Test.TestApplication::class, sdk = [35])
class RoomMigrationV3ToV4Test {
    class TestApplication : Application()
    private val databaseName = "cosmic-v3-migration-test.db"
    private lateinit var database: CosmicDatabase

    @Before fun createActualV3Database() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
        val schema = JSONObject(javaClass.classLoader!!.getResource("schemas/cosmic-db-v2.json")!!.readText())
            .getJSONObject("database")
        val raw = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        val entities = schema.getJSONArray("entities")
        for (index in 0 until entities.length()) {
            val entity = entities.getJSONObject(index)
            val tableName = entity.getString("tableName")
            raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = entity.optJSONArray("indices")
            if (indices != null) {
                for (j in 0 until indices.length()) {
                    raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
            }
        }
        for (index in 0 until schema.getJSONArray("setupQueries").length()) {
            raw.execSQL(schema.getJSONArray("setupQueries").getString(index))
        }
        raw.execSQL("CREATE TABLE approval_requests (id TEXT NOT NULL, taskId TEXT NOT NULL, callId TEXT NOT NULL, agentId TEXT NOT NULL, toolName TEXT NOT NULL, action TEXT NOT NULL, resourceScope TEXT NOT NULL, riskLevel TEXT NOT NULL, reason TEXT NOT NULL, argumentsHash TEXT NOT NULL, status TEXT NOT NULL, decisionMode TEXT, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, decidedAt INTEGER, outcome TEXT, PRIMARY KEY(id))")
        raw.execSQL("CREATE UNIQUE INDEX index_approval_requests_taskId_callId ON approval_requests(taskId, callId)")
        raw.execSQL("CREATE INDEX index_approval_requests_status_createdAt ON approval_requests(status, createdAt)")
        raw.execSQL("CREATE TABLE security_audit_events (eventId TEXT NOT NULL, requestId TEXT, taskId TEXT, agentId TEXT, toolName TEXT NOT NULL, action TEXT NOT NULL, resourceScope TEXT NOT NULL, decision TEXT NOT NULL, occurredAt INTEGER NOT NULL, outcome TEXT, sanitizedError TEXT, PRIMARY KEY(eventId))")
        raw.execSQL("CREATE INDEX index_security_audit_events_requestId_occurredAt ON security_audit_events(requestId, occurredAt)")
        raw.execSQL("CREATE INDEX index_security_audit_events_decision_occurredAt ON security_audit_events(decision, occurredAt)")
        raw.execSQL("CREATE INDEX index_security_audit_events_toolName_occurredAt ON security_audit_events(toolName, occurredAt)")
        raw.execSQL("CREATE INDEX index_security_audit_events_taskId_occurredAt ON security_audit_events(taskId, occurredAt)")
        raw.execSQL("INSERT INTO security_audit_events VALUES ('legacy-event', NULL, 'task', 'builder', 'write_file', 'write', 'src/Main.kt', 'COMPLETED', 1234, 'ok', 'api_key=legacysecret')")
        raw.version = 3
        raw.close()
        database = Room.databaseBuilder(context, CosmicDatabase::class.java, databaseName)
            .addMigrations(CosmicDatabase.MIGRATION_3_4)
            .allowMainThreadQueries().build()
    }

    @After fun closeDatabase() {
        database.close()
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test fun migratesV3HistoryAndSealsItWithoutDataLoss() = runBlocking {
        val row = database.securityAuditDao().allBySequence().single()
        assertEquals("legacy-event", row.eventId)
        assertEquals("COMPLETED", row.decision)
        val result = SecurityAuditRepository(database).verifyIntegrity()
        assertTrue(result.valid)
        assertEquals(1, result.verifiedEvents)
        val sealed = database.securityAuditDao().allBySequence().single()
        assertEquals(1L, sealed.sequence)
        assertTrue(sealed.sanitizedError?.contains("legacysecret") == false)
    }
}
