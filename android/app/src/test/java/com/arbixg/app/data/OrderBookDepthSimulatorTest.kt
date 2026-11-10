package com.arbixg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrderBookDepthSimulatorTest {
    private val now = 1_000L
    @Test
    fun simulatesThreeLegCycleAndDeductsFeeOnEveryOutput() {
        val markets = mapOf(
            "A-B" to market("A-B", "A", "B", bids = listOf(OrderBookLevel(2.0, 10.0)), asks = listOf(OrderBookLevel(2.1, 10.0))),
            "B-C" to market("B-C", "B", "C", bids = listOf(OrderBookLevel(3.0, 20.0)), asks = listOf(OrderBookLevel(3.1, 20.0))),
            "A-C" to market("A-C", "A", "C", bids = listOf(OrderBookLevel(3.9, 10.0)), asks = listOf(OrderBookLevel(4.0, 20.0)))
        )

        val result = OrderBookDepthSimulator.simulateCycle(
            path = listOf("A", "B", "C", "A"),
            marketIds = listOf("A-B", "B-C", "A-C"),
            markets = markets,
            startAmount = 5.0,
            feeRate = 0.001,
            now = now
        )

        assertEquals(3, result.legs.size)
        assertEquals(50.0, result.grossReturnPct, 1e-9)
        assertEquals(7.5 * 0.999 * 0.999 * 0.999, result.finalAmount, 1e-9)
        assertEquals((result.finalAmount / 5.0 - 1.0) * 100.0, result.netReturnPct, 1e-9)
    }

    @Test
    fun rejectsInsufficientDepthInsteadOfAssumingFullFill() {
        val markets = mapOf(
            "A-B" to market("A-B", "A", "B", bids = listOf(OrderBookLevel(2.0, 1.0)), asks = listOf(OrderBookLevel(2.1, 10.0))),
            "B-C" to market("B-C", "B", "C", bids = listOf(OrderBookLevel(3.0, 20.0)), asks = listOf(OrderBookLevel(3.1, 20.0))),
            "A-C" to market("A-C", "A", "C", bids = listOf(OrderBookLevel(3.9, 10.0)), asks = listOf(OrderBookLevel(4.0, 20.0)))
        )
        assertThrows(IllegalStateException::class.java) {
            OrderBookDepthSimulator.simulateCycle(
                listOf("A", "B", "C", "A"), listOf("A-B", "B-C", "A-C"),
                markets, 5.0, 0.001, now = now
            )
        }
    }

    @Test
    fun rejectsInvalidFeeAndCurrencyRoute() {
        val markets = mapOf(
            "A-B" to market("A-B", "A", "B", listOf(OrderBookLevel(2.0, 10.0)), listOf(OrderBookLevel(2.1, 10.0))),
            "B-C" to market("B-C", "B", "C", listOf(OrderBookLevel(3.0, 20.0)), listOf(OrderBookLevel(3.1, 20.0))),
            "A-C" to market("A-C", "A", "C", listOf(OrderBookLevel(3.9, 10.0)), listOf(OrderBookLevel(4.0, 20.0)))
        )
        assertThrows(IllegalArgumentException::class.java) {
            OrderBookDepthSimulator.simulateCycle(listOf("A", "B", "C", "A"), listOf("A-B", "B-C", "A-C"), markets, 5.0, -0.1, now = now)
        }
        assertThrows(IllegalStateException::class.java) {
            OrderBookDepthSimulator.simulateCycle(listOf("A", "B", "D", "A"), listOf("A-B", "B-C", "A-C"), markets, 5.0, 0.001, now = now)
        }
    }


    @Test
    fun rejectsStaleOrderBooks() {
        val stale = market("A-B", "A", "B", listOf(OrderBookLevel(2.0, 10.0)), listOf(OrderBookLevel(2.1, 10.0)))
            .copy(orderBook = SpotOrderBook("A-B", now - 3_000L, listOf(OrderBookLevel(2.0, 10.0)), listOf(OrderBookLevel(2.1, 10.0))))
        val markets = mapOf(
            "A-B" to stale,
            "B-C" to market("B-C", "B", "C", listOf(OrderBookLevel(3.0, 20.0)), listOf(OrderBookLevel(3.1, 20.0))),
            "A-C" to market("A-C", "A", "C", listOf(OrderBookLevel(3.9, 10.0)), listOf(OrderBookLevel(4.0, 20.0)))
        )
        assertThrows(IllegalArgumentException::class.java) {
            OrderBookDepthSimulator.simulateCycle(listOf("A", "B", "C", "A"), listOf("A-B", "B-C", "A-C"), markets, 5.0, 0.001, now = now)
        }
    }

    private fun market(
        id: String,
        base: String,
        quote: String,
        bids: List<OrderBookLevel>,
        asks: List<OrderBookLevel>
    ) = MarketDepth(id, base, quote, SpotOrderBook(id, now, bids, asks))
}
