package com.arbixg.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersistentCycleRiskManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "risk-manager-test.db"
    private val now = 1_800_000_000_000L

    private fun db() = Room.databaseBuilder(
        context,
        ArbixDatabase::class.java,
        databaseName
    ).allowMainThreadQueries().addMigrations(
        ArbixDatabase.MIGRATION_1_2,
        ArbixDatabase.MIGRATION_2_3
    ).build()

    private fun limits() = RiskLimits(
        maxCycleStartAmount = BigDecimal("10"),
        maxDailyRealizedLoss = BigDecimal("2"),
        maxSlippageBps = BigDecimal("20"),
        maxSnapshotAgeMs = 2_000
    )

    private fun balances(amount: String = "10") = mapOf(
        "USDT" to RiskBalance(BigDecimal(amount), now)
    )

    private suspend fun reserve(
        manager: PersistentCycleRiskManager,
        cycleId: String,
        amount: String = "8"
    ) = manager.reserve(
        cycleId = cycleId,
        startCurrency = "USDT",
        startAmount = BigDecimal(amount),
        requiredFunds = mapOf("USDT" to BigDecimal(amount)),
        balances = balances(),
        marketSnapshotEpochMs = now,
        estimatedSlippageBps = BigDecimal("5")
    )

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun reservationSurvivesDatabaseReopenAndBlocksDoubleUse() = runBlocking {
        val firstDb = db()
        val firstManager = PersistentCycleRiskManager(firstDb, limits()) { now }
        assertTrue(reserve(firstManager, "cycle-1").approved)
        firstDb.close()

        val reopenedDb = db()
        val reopenedManager = PersistentCycleRiskManager(reopenedDb, limits()) { now }
        val restored = reopenedManager.activeReservations()
        assertEquals(1, restored.size)
        assertEquals("cycle-1", restored.single().cycleId)
        assertEquals(BigDecimal("8"), restored.single().requirements["USDT"])
        assertFalse(reserve(reopenedManager, "cycle-2").approved)
        assertTrue(reopenedManager.release("cycle-1"))
        assertTrue(reserve(reopenedManager, "cycle-2").approved)
        reopenedDb.close()
    }

    @Test
    fun killSwitchAndDailyLossSurviveDatabaseReopen() = runBlocking {
        val firstDb = db()
        val firstManager = PersistentCycleRiskManager(firstDb, limits()) { now }
        firstManager.setKillSwitchEnabled(true)
        firstManager.recordRealizedLoss(BigDecimal("2"))
        firstDb.close()

        val reopenedDb = db()
        val reopenedManager = PersistentCycleRiskManager(reopenedDb, limits()) { now }
        assertTrue(reopenedManager.isKillSwitchEnabled())
        assertEquals(BigDecimal("2"), reopenedManager.currentDailyRealizedLoss())
        assertFalse(reserve(reopenedManager, "blocked-cycle").approved)
        reopenedDb.close()
    }

    @Test
    fun rejectsStaleBalancesAndMarketSnapshots() = runBlocking {
        val database = db()
        val manager = PersistentCycleRiskManager(database, limits()) { now }
        val staleBalance = mapOf("USDT" to RiskBalance(BigDecimal("10"), now - 3_000))
        val staleBalanceDecision = manager.reserve(
            "stale-balance", "USDT", BigDecimal("1"),
            mapOf("USDT" to BigDecimal("1")), staleBalance, now, BigDecimal("1")
        )
        val staleMarketDecision = manager.reserve(
            "stale-market", "USDT", BigDecimal("1"),
            mapOf("USDT" to BigDecimal("1")), balances(), now - 3_000, BigDecimal("1")
        )
        assertFalse(staleBalanceDecision.approved)
        assertFalse(staleMarketDecision.approved)
        assertTrue(manager.activeReservations().isEmpty())
        database.close()
    }

    @Test
    fun concurrentCyclesCannotReserveTheSameAvailableFundsTwice() = runBlocking {
        val database = db()
        val manager = PersistentCycleRiskManager(database, limits()) { now }
        val first = async(Dispatchers.IO) { reserve(manager, "parallel-1") }
        val second = async(Dispatchers.IO) { reserve(manager, "parallel-2") }
        val decisions = listOf(first.await(), second.await())
        assertEquals(1, decisions.count { it.approved })
        assertEquals(1, manager.activeReservations().size)
        database.close()
    }

    @Test
    fun versionTwoMigrationPreservesTradeAndOrderHistory() = runBlocking {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        val oldDb = SQLiteDatabase.openOrCreateDatabase(file, null)
        oldDb.execSQL(
            """CREATE TABLE trade_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                mode TEXT NOT NULL,
                route TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                realizedPnl REAL
            )"""
        )
        oldDb.execSQL(
            """CREATE TABLE tracked_orders (
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
        oldDb.execSQL(
            "CREATE INDEX index_tracked_orders_locallyUpdatedAtEpochMs ON tracked_orders(locallyUpdatedAtEpochMs)"
        )
        oldDb.execSQL(
            "INSERT INTO trade_records(mode, route, status, createdAtEpochMs, realizedPnl) VALUES(?, ?, ?, ?, ?)",
            arrayOf("DEMO", "USDT-BTC-ETH-USDT", "FILLED", now, 0.25)
        )
        oldDb.execSQL(
            """INSERT INTO tracked_orders(
                orderId, instrumentId, status, requestedSize, cumulativeFilledSize,
                remainingSize, averageFillPrice, cumulativeFee, feeCurrency,
                lastExchangeUpdateEpochMs, locallyUpdatedAtEpochMs
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf("order-1", "BTC-USDT", "FILLED", "0.01", "0.01", "0", "65000", "0.00001", "BTC", now, now)
        )
        oldDb.version = 2
        oldDb.close()

        val migrated = db()
        val trades = migrated.tradeRecordDao().getAllForBackup()
        val order = migrated.trackedOrderDao().getByOrderId("order-1")
        assertEquals(1, trades.size)
        assertEquals("USDT-BTC-ETH-USDT", trades.single().route)
        assertEquals("FILLED", order?.status)
        assertTrue(migrated.riskStateDao().getState() == null)
        migrated.close()
    }

    @Test
    fun lossLimitIsAppliedAcrossManagerInstances() = runBlocking {
        val firstDb = db()
        val firstManager = PersistentCycleRiskManager(firstDb, limits()) { now }
        assertTrue(firstManager.recordRealizedLoss(BigDecimal("1")).approved)
        firstDb.close()

        val reopenedDb = db()
        val reopenedManager = PersistentCycleRiskManager(reopenedDb, limits()) { now }
        assertFalse(reopenedManager.recordRealizedLoss(BigDecimal("1")).approved)
        assertFalse(reserve(reopenedManager, "loss-limited").approved)
        assertEquals(BigDecimal("2"), reopenedManager.currentDailyRealizedLoss())
        reopenedDb.close()
    }
}
