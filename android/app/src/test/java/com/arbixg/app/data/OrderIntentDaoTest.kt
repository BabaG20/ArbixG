package com.arbixg.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class OrderIntentDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "order-intent-test.db"
    private val now = 1_800_000_000_000L

    private fun db() = Room.databaseBuilder(
        context,
        ArbixDatabase::class.java,
        databaseName
    ).allowMainThreadQueries().addMigrations(
        ArbixDatabase.MIGRATION_1_2,
        ArbixDatabase.MIGRATION_2_3,
        ArbixDatabase.MIGRATION_3_4
    ).build()

    private fun intent(
        clientId: String = "CycleLeg0001",
        cycleId: String = "cycle-1",
        leg: Int = 0
    ) = OrderIntentEntity(
        clientOrderId = clientId,
        cycleId = cycleId,
        legIndex = leg,
        instrumentId = "BTC-USDT",
        side = "buy",
        orderType = "limit",
        price = "60000",
        size = "0.001",
        state = OrderIntentEntity.PREPARED,
        exchangeOrderId = null,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        lastError = null
    )

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun preparedIntentIsDurableAcrossDatabaseReopen() = runBlocking {
        val first = db()
        assertTrue(first.orderIntentDao().insertIfAbsent(intent()) > 0L)
        first.close()

        val reopened = db()
        val saved = reopened.orderIntentDao().get("CycleLeg0001")
        assertEquals(OrderIntentEntity.PREPARED, saved?.state)
        assertEquals("BTC-USDT", saved?.instrumentId)
        assertEquals("0.001", saved?.size)
        reopened.close()
    }

    @Test
    fun duplicateClientIdAndDuplicateCycleLegCannotCreateSecondIntent() = runBlocking {
        val database = db()
        val dao = database.orderIntentDao()
        assertTrue(dao.insertIfAbsent(intent()) > 0L)
        assertEquals(-1L, dao.insertIfAbsent(intent()))
        assertEquals(-1L, dao.insertIfAbsent(intent(clientId = "OtherClientId")))
        assertEquals(1, dao.count())
        database.close()
    }

    @Test
    fun intentMustBeMarkedSubmittingBeforeAcknowledgementAndCannotSubmitTwice() = runBlocking {
        val database = db()
        val dao = database.orderIntentDao()
        dao.insertIfAbsent(intent())

        assertFalse(dao.markAcknowledged("CycleLeg0001", "exchange-1", now))
        assertTrue(dao.markSubmitting("CycleLeg0001", now + 1))
        assertFalse(dao.markSubmitting("CycleLeg0001", now + 2))
        assertTrue(dao.markAcknowledged("CycleLeg0001", "exchange-1", now + 3))
        assertEquals("exchange-1", dao.get("CycleLeg0001")?.exchangeOrderId)
        assertEquals(OrderIntentEntity.ACKNOWLEDGED, dao.get("CycleLeg0001")?.state)
        database.close()
    }

    @Test
    fun ambiguousSubmissionIsRecoverableAndCanBeAcknowledgedAfterLookup() = runBlocking {
        val database = db()
        val dao = database.orderIntentDao()
        dao.insertIfAbsent(intent())
        assertTrue(dao.markSubmitting("CycleLeg0001", now + 1))
        assertTrue(dao.markUnknown("CycleLeg0001", now + 2, "Network timeout; exchange result unknown"))

        val recoverable = dao.getRecoverable()
        assertEquals(1, recoverable.size)
        assertEquals(OrderIntentEntity.UNKNOWN, recoverable.single().state)

        assertTrue(dao.markAcknowledged("CycleLeg0001", "exchange-lookup-1", now + 3))
        assertEquals(OrderIntentEntity.ACKNOWLEDGED, dao.get("CycleLeg0001")?.state)
        database.close()
    }

    @Test
    fun recoveredIntentSurvivesRestartAndIsListedForReconciliation() = runBlocking {
        val first = db()
        val dao = first.orderIntentDao()
        dao.insertIfAbsent(intent())
        assertTrue(dao.markSubmitting("CycleLeg0001", now + 1))
        assertTrue(dao.markUnknown("CycleLeg0001", now + 2, "Process stopped during request"))
        first.close()

        val reopened = db()
        val recoverable = reopened.orderIntentDao().getRecoverable()
        assertEquals("CycleLeg0001", recoverable.single().clientOrderId)
        assertEquals(OrderIntentEntity.UNKNOWN, recoverable.single().state)
        reopened.close()
    }
}
