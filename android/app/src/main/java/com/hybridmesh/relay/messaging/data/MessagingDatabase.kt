package com.hybridmesh.relay.messaging.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PeerEntity::class, MessageRecordEntity::class, MeshForwardingRecordEntity::class, MeshSeenPacketEntity::class],
    version = 6,
    exportSchema = false
)
abstract class MessagingDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun meshForwardingDao(): MeshForwardingDao
    abstract fun meshSeenPacketDao(): MeshSeenPacketDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE mesh_messages ADD COLUMN deliveryHopCount INTEGER")
                database.execSQL("CREATE TABLE IF NOT EXISTS mesh_forwarding (packetId TEXT NOT NULL, messageId TEXT NOT NULL, originNodeId TEXT NOT NULL, destinationNodeId TEXT NOT NULL, receivedFromNodeId TEXT, createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, ttl INTEGER NOT NULL, hopCount INTEGER NOT NULL, packetType INTEGER NOT NULL, messageType TEXT NOT NULL, content TEXT NOT NULL, deliveryHopCount INTEGER, state TEXT NOT NULL, attemptCount INTEGER NOT NULL, nextAttemptAt INTEGER, lastError TEXT, PRIMARY KEY(packetId))")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_mesh_forwarding_messageId ON mesh_forwarding(messageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_mesh_forwarding_state_nextAttemptAt ON mesh_forwarding(state, nextAttemptAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_mesh_forwarding_expiresAt ON mesh_forwarding(expiresAt)")
                database.execSQL("CREATE TABLE IF NOT EXISTS mesh_seen_packets (packetId TEXT NOT NULL, messageId TEXT NOT NULL, firstSeenAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, highestHopCount INTEGER NOT NULL, PRIMARY KEY(packetId))")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_mesh_seen_packets_expiresAt ON mesh_seen_packets(expiresAt)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE mesh_forwarding ADD COLUMN receivedFromAddress TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE mesh_seen_packets_new (" +
                        "packetId TEXT NOT NULL, " +
                        "messageId TEXT NOT NULL, " +
                        "firstSeenAt INTEGER NOT NULL, " +
                        "expiresAt INTEGER NOT NULL, " +
                        "bestHopCount INTEGER NOT NULL, " +
                        "PRIMARY KEY(packetId))"
                )
                database.execSQL(
                    "INSERT INTO mesh_seen_packets_new " +
                        "(packetId, messageId, firstSeenAt, expiresAt, bestHopCount) " +
                        "SELECT packetId, messageId, firstSeenAt, expiresAt, highestHopCount " +
                        "FROM mesh_seen_packets"
                )
                database.execSQL("DROP TABLE mesh_seen_packets")
                database.execSQL("ALTER TABLE mesh_seen_packets_new RENAME TO mesh_seen_packets")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mesh_seen_packets_expiresAt " +
                        "ON mesh_seen_packets(expiresAt)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE mesh_peers ADD COLUMN meshProtocolVersion INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE mesh_peers ADD COLUMN canRelay INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE mesh_peers ADD COLUMN canStoreForward INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): MessagingDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MessagingDatabase::class.java,
                    "hybrid_mesh_messaging.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6
                    )
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
