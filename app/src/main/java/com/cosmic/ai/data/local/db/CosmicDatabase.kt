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
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.ProviderEntity
import com.cosmic.ai.data.local.db.entity.SessionEntity
import com.cosmic.ai.data.local.db.entity.TaskEntity
import com.cosmic.ai.data.local.db.entity.ToolExecutionEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ProviderEntity::class,
        TaskEntity::class,
        ToolExecutionEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CosmicDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun taskDao(): TaskDao
    abstract fun toolExecutionDao(): ToolExecutionDao

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

        fun build(context: Context): CosmicDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                CosmicDatabase::class.java,
                "cosmic_ai.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
