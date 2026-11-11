package com.arbixg.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DemoOrderSubmissionCoordinatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "demo-submission-coordinator-test.db"
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

    private fun intent() = OrderIntentEntity(
        clientOrderId = "CoordinatorLeg01",
        cycleId = "coordinator-cycle",
        legIndex = 0,
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

    private class FakeGateway : OkxDemoOrderGateway {
        var submitCalls = 0
        var lookupCalls = 0
        var failSubmission = false
        var failLookup = false

        private fun result() = OkxDemoOrderResult(
            orderId = "exchange-order-123",
            clientOrderId = "CoordinatorLeg01",
            instrumentId = "BTC-USDT",
            state = "live",
            raw = JSONObject().put("ordId", "exchange-order-123")
        )

        override suspend fun placeLimitOrder(order: OkxDemoLimitOrder): OkxDemoOrderResult {
            submitCalls++
            if (failSubmission) throw IOException("simulated ambiguous timeout")
            return result()
        }

        override suspend fun queryByClientOrderId(
            instrumentId: String,
            clientOrderId: String
        ): OkxDemoOrderResult {
            lookupCalls++
            if (failLookup) throw IOException("simulated lookup timeout")
            return result()
        }
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun persistsIntentBeforeSubmissionAndNeverPostsSameIntentTwice() = runBlocking {
        val database = db()
        val gateway = FakeGateway()
        val coordinator = DemoOrderSubmissionCoordinator(
            database.orderIntentDao(), gateway
        ) { now + 1 }

        val first = coordinator.submitOnce(intent())
        assertTrue(first is DemoOrderSubmissionCoordinator.Result.Acknowledged)
        assertEquals(1, gateway.submitCalls)
        assertEquals(OrderIntentEntity.ACKNOWLEDGED,
            database.orderIntentDao().get("CoordinatorLeg01")?.state)

        val duplicate = coordinator.submitOnce(intent())
        assertTrue(duplicate is DemoOrderSubmissionCoordinator.Result.AlreadyExists)
        assertEquals(1, gateway.submitCalls)
        database.close()
    }

    @Test
    fun preparedIntentFromPriorProcessCanResumeAndConcurrentCallsOnlyPostOnce() = runBlocking {
        val database = db()
        val dao = database.orderIntentDao()
        assertTrue(dao.insertIfAbsent(intent()) > 0L)
        val gateway = FakeGateway()
        val coordinator = DemoOrderSubmissionCoordinator(dao, gateway) { now + 1 }

        val first = async(Dispatchers.IO) { coordinator.submitOnce(intent()) }
        val second = async(Dispatchers.IO) { coordinator.submitOnce(intent()) }
        val results = listOf(first.await(), second.await())

        assertEquals(1, gateway.submitCalls)
        assertEquals(1, results.count { it is DemoOrderSubmissionCoordinator.Result.Acknowledged })
        assertEquals(1, results.count { it is DemoOrderSubmissionCoordinator.Result.AlreadyExists })
        database.close()
    }

    @Test
    fun ambiguousSubmitIsPersistedAndReconciledByClientIdWithoutResubmission() = runBlocking {
        val database = db()
        val gateway = FakeGateway().apply { failSubmission = true }
        val coordinator = DemoOrderSubmissionCoordinator(
            database.orderIntentDao(), gateway
        ) { now + 1 }

        val submitted = coordinator.submitOnce(intent())
        assertTrue(submitted is DemoOrderSubmissionCoordinator.Result.Ambiguous)
        assertEquals(1, gateway.submitCalls)
        assertEquals(OrderIntentEntity.UNKNOWN,
            database.orderIntentDao().get("CoordinatorLeg01")?.state)

        gateway.failSubmission = false
        val saved = database.orderIntentDao().get("CoordinatorLeg01")!!
        val recovered = coordinator.reconcileUnknown(saved)
        assertTrue(recovered is DemoOrderSubmissionCoordinator.Result.Acknowledged)
        assertEquals(1, gateway.submitCalls)
        assertEquals(1, gateway.lookupCalls)
        assertEquals(OrderIntentEntity.ACKNOWLEDGED,
            database.orderIntentDao().get("CoordinatorLeg01")?.state)
        database.close()
    }
}
