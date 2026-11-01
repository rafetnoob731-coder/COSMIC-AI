// language: kotlin, file: CosmicDatabase.kt, runtime: Room 2.6, target: local persistence
package com.cosmic.ai.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.cosmic.ai.data.local.db.dao.MessageDao
import com.cosmic.ai.data.local.db.dao.ProviderDao
import com.cosmic.ai.data.local.db.dao.SessionDao
import com.cosmic.ai.data.local.db.dao.TaskDao
import com.cosmic.ai.data.local.db.entity.MessageEntity
import com.cosmic.ai.data.local.db.entity.ProviderEntity
import com.cosmic.ai.data.local.db.entity.SessionEntity
import com.cosmic.ai.data.local.db.entity.TaskEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ProviderEntity::class,
        TaskEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class CosmicDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun taskDao(): TaskDao

    companion object {
        fun build(context: Context): CosmicDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                CosmicDatabase::class.java,
                "cosmic_ai.db"
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
