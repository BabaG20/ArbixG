package com.arbixg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackedOrderEntityTest {
    private fun order() = ReconciledOrder(
        orderId = "demo-order-001",
        instrumentId = "BTC-USDT",
        status = ReconciledOrderStatus.PARTIALLY_FILLED,
        requestedSize = "0.0100",
        cumulativeFilledSize = "0.0040",
        remainingSize = "0.0060",
        averageFillPrice = "65000.25",
        cumulativeFee = "-0.000004",
        feeCurrency = "BTC",
        lastExchangeUpdateEpochMs = 1_800_000_000_000L
    )

    @Test fun entityRoundTripPreservesExchangeStateAndDecimalPrecision() {
        val entity = order().toEntity(nowEpochMs = 1_800_000_000_123L)
        val restored = entity.toReconciledOrder()

        assertEquals(order(), restored)
        assertEquals(1_800_000_000_123L, entity.locallyUpdatedAtEpochMs)
        assertEquals("0.0040", entity.cumulativeFilledSize)
        assertEquals("-0.000004", entity.cumulativeFee)
    }

    @Test fun nullableAveragePriceAndFeeCurrencyRemainNull() {
        val source = order().copy(averageFillPrice = null, feeCurrency = null)
        val restored = source.toEntity(nowEpochMs = 10L).toReconciledOrder()

        assertNull(restored.averageFillPrice)
        assertNull(restored.feeCurrency)
        assertEquals(source, restored)
    }
}
