package com.arbixg.app.data

import org.junit.Assert.*
import org.junit.Test

class OrderFillReconcilerTest {
    private fun snapshot(state: String, filled: String, time: Long, size: String = "1.0") =
        OkxOrderSnapshot(
            orderId = "order-123",
            instrumentId = "BTC-USDT",
            exchangeState = state,
            requestedSize = size,
            cumulativeFilledSize = filled,
            averageFillPrice = if (filled == "0") "" else "65000.5",
            cumulativeFee = "-0.0001",
            feeCurrency = "BTC",
            updateTimeEpochMs = time
        )

    @Test fun tracksPartialFillAndRemainingQuantity() {
        val result = OrderFillReconciler.reconcile(null, snapshot("partially_filled", "0.4", 100))
        assertEquals(ReconciledOrderStatus.PARTIALLY_FILLED, result.status)
        assertEquals("0.4", result.cumulativeFilledSize)
        assertEquals("0.6", result.remainingSize)
        assertEquals("BTC", result.feeCurrency)
    }

    @Test fun advancesPartialFillToFilledExactlyOnce() {
        val partial = OrderFillReconciler.reconcile(null, snapshot("partially_filled", "0.4", 100))
        val filled = OrderFillReconciler.reconcile(partial, snapshot("filled", "1.0", 200))
        assertEquals(ReconciledOrderStatus.FILLED, filled.status)
        assertEquals("0", filled.remainingSize)
        assertEquals(filled, OrderFillReconciler.reconcile(filled, snapshot("filled", "1.0", 300)))
    }

    @Test fun canceledOrderKeepsAlreadyFilledAmount() {
        val partial = OrderFillReconciler.reconcile(null, snapshot("partially_filled", "0.25", 100))
        val canceled = OrderFillReconciler.reconcile(partial, snapshot("canceled", "0.25", 200))
        assertEquals(ReconciledOrderStatus.CANCELED, canceled.status)
        assertEquals("0.75", canceled.remainingSize)
        assertEquals(canceled, OrderFillReconciler.reconcile(canceled, snapshot("canceled", "0.25", 300)))
    }

    @Test fun ignoresOlderExchangeSnapshot() {
        val latest = OrderFillReconciler.reconcile(null, snapshot("partially_filled", "0.5", 200))
        val stale = OrderFillReconciler.reconcile(latest, snapshot("live", "0.2", 100))
        assertEquals(latest, stale)
    }

    @Test fun rejectsFillAboveRequestedSize() {
        try {
            OrderFillReconciler.reconcile(null, snapshot("filled", "1.01", 100))
            fail("Overfill must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsFilledStateWhenCumulativeFillIsIncomplete() {
        try {
            OrderFillReconciler.reconcile(null, snapshot("filled", "0.9", 100))
            fail("Incomplete filled state must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsCumulativeFillRegression() {
        val previous = OrderFillReconciler.reconcile(null, snapshot("partially_filled", "0.5", 100))
        try {
            OrderFillReconciler.reconcile(previous, snapshot("partially_filled", "0.4", 200))
            fail("Fill regression must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }
}
