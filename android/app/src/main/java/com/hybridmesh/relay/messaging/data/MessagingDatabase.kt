package com.hybridmesh.relay.messaging.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PeerEntity::class, MessageRecordEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MessagingDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: MessagingDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE mesh_messages ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE mesh_messages ADD COLUMN nextAttemptAt INTEGER")
                database.execSQL("ALTER TABLE mesh_messages ADD COLUMN lastError TEXT")
                database.execSQL("UPDATE mesh_messages SET status = 'QUEUED', nextAttemptAt = createdAt, lastError = 'LEGACY_TRANSPORT_STATE' WHERE status IN ('CONNECTING', 'SENDING')")
            }
        }

        fun getInstance(context: Context): MessagingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessagingDatabase::class.java,
                    "hybrid_mesh_messaging.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
