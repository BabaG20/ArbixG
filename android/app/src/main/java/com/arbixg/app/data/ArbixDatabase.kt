package com.arbixg.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TradeRecord::class, TrackedOrderEntity::class],
    version = 2,
    exportSchema = true
)
abstract class ArbixDatabase : RoomDatabase() {
    abstract fun tradeRecordDao(): TradeRecordDao
    abstract fun trackedOrderDao(): TrackedOrderDao

    companion object {
        /** Preserve existing trade history while adding the durable order journal. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS tracked_orders (
                        orderId TEXT NOT NULL PRIMARY KEY,
                        instrumentId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        requestedSize TEXT NOT NULL,
                        cumulativeFilledSize TEXT NOT NULL,
                        remainingSize TEXT NOT NULL,
                        averageFillPrice TEXT,
                        cumulativeFee TEXT NOT NULL,
                        feeCurrency TEXT,
                        lastExchangeUpdateEpochMs INTEGER NOT NULL,
                        locallyUpdatedAtEpochMs INTEGER NOT NULL
                    )"""
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tracked_orders_locallyUpdatedAtEpochMs ON tracked_orders(locallyUpdatedAtEpochMs)"
                )
            }
        }

        @Volatile private var INSTANCE: ArbixDatabase? = null

        fun getInstance(context: Context): ArbixDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArbixDatabase::class.java,
                    "arbixg-local.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
