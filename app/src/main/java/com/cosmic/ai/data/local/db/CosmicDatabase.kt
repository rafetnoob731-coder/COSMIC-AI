// language: kotlin, file: CosmicDatabase.kt, runtime: Room 2.6, target: local persistence
package com.cosmic.ai.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import com.cosmic.ai.data.local.db.dao.MessageDao
import com.cosmic.ai.data.local.db.dao.ProviderDao
import com.cosmic.ai.data.local.db.dao.SessionDao
import com.cosmic.ai.data.local.db.dao.TaskDao
import com.cosmic.ai.data.local.db.dao.ToolExecutionDao
import com.cosmic.ai.data.local.db.dao.ApprovalRequestDao
import com.cosmic.ai.data.local.db.dao.SecurityAuditDao
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.ProviderEntity
import com.cosmic.ai.data.local.db.entity.SessionEntity
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.data.local.db.entity.ToolExecutionEntity
import com.cosmic.ai.data.local.db.entity.ApprovalRequestEntity
import com.cosmic.ai.data.local.db.entity.SecurityAuditEventEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ProviderEntity::class,
        TaskEntity::class,
        ToolExecutionEntity::class,
        ApprovalRequestEntity::class,
        SecurityAuditEventEntity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CosmicDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun taskDao(): TaskDao
    abstract fun toolExecutionDao(): ToolExecutionDao
    abstract fun approvalRequestDao(): ApprovalRequestDao
    abstract fun securityAuditDao(): SecurityAuditDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN providerConfigJson TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN modelName TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN mode TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN maxSteps INTEGER NOT NULL DEFAULT 12")
                db.execSQL("ALTER TABLE tasks ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN projectUri TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN requestMessageId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN cancelRequested INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN transitionHistoryJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("CREATE TABLE IF NOT EXISTS tool_executions (taskId TEXT NOT NULL, callId TEXT NOT NULL, toolName TEXT NOT NULL, argumentsJson TEXT NOT NULL, argumentsHash TEXT NOT NULL, state TEXT NOT NULL, idempotent INTEGER NOT NULL, resultJson TEXT, error TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(taskId, callId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_executions_taskId_state ON tool_executions(taskId, state)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS approval_requests (id TEXT NOT NULL, taskId TEXT NOT NULL, callId TEXT NOT NULL, agentId TEXT NOT NULL, toolName TEXT NOT NULL, action TEXT NOT NULL, resourceScope TEXT NOT NULL, riskLevel TEXT NOT NULL, reason TEXT NOT NULL, argumentsHash TEXT NOT NULL, status TEXT NOT NULL, decisionMode TEXT, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, decidedAt INTEGER, outcome TEXT, PRIMARY KEY(id))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_approval_requests_taskId_callId ON approval_requests(taskId, callId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_approval_requests_status_createdAt ON approval_requests(status, createdAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS security_audit_events (eventId TEXT NOT NULL, requestId TEXT, taskId TEXT, agentId TEXT, toolName TEXT NOT NULL, action TEXT NOT NULL, resourceScope TEXT NOT NULL, decision TEXT NOT NULL, occurredAt INTEGER NOT NULL, outcome TEXT, sanitizedError TEXT, PRIMARY KEY(eventId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_security_audit_events_requestId_occurredAt ON security_audit_events(requestId, occurredAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_security_audit_events_decision_occurredAt ON security_audit_events(decision, occurredAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_security_audit_events_toolName_occurredAt ON security_audit_events(toolName, occurredAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_security_audit_events_taskId_occurredAt ON security_audit_events(taskId, occurredAt)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE security_audit_events ADD COLUMN sequence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE security_audit_events ADD COLUMN previousHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE security_audit_events ADD COLUMN eventHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_security_audit_events_sequence ON security_audit_events(sequence)")
            }
        }

        fun build(context: Context): CosmicDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                CosmicDatabase::class.java,
                "cosmic_ai.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
