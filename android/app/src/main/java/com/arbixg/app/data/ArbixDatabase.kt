package com.arbixg.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TradeRecord::class,
        TrackedOrderEntity::class,
        RiskReservationEntity::class,
        RiskStateEntity::class,
        OrderIntentEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class ArbixDatabase : RoomDatabase() {
    abstract fun tradeRecordDao(): TradeRecordDao
    abstract fun trackedOrderDao(): TrackedOrderDao
    abstract fun riskStateDao(): RiskStateDao
    abstract fun orderIntentDao(): OrderIntentDao

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

        /** Add durable risk state without modifying existing trade/order history. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS risk_reservations (
                        cycleId TEXT NOT NULL,
                        currency TEXT NOT NULL,
                        amount TEXT NOT NULL,
                        startCurrency TEXT NOT NULL,
                        startAmount TEXT NOT NULL,
                        reservedAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(cycleId, currency)
                    )"""
                )
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS risk_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        utcDate TEXT NOT NULL,
                        dailyRealizedLoss TEXT NOT NULL,
                        killSwitchEnabled INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )"""
                )
            }
        }

        /** Persist each order intent before submission; preserve all prior Room data. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS order_intents (
                        clientOrderId TEXT NOT NULL PRIMARY KEY,
                        cycleId TEXT NOT NULL,
                        legIndex INTEGER NOT NULL,
                        instrumentId TEXT NOT NULL,
                        side TEXT NOT NULL,
                        orderType TEXT NOT NULL,
                        price TEXT NOT NULL,
                        size TEXT NOT NULL,
                        state TEXT NOT NULL,
                        exchangeOrderId TEXT,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL,
                        lastError TEXT
                    )"""
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_order_intents_cycleId_legIndex ON order_intents(cycleId, legIndex)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_order_intents_state_updatedAtEpochMs ON order_intents(state, updatedAtEpochMs)"
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
