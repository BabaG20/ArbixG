package com.arbixg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OkxFeeSimulationTest {
    private val now = 1_800_000_000_000L

    private fun markets() = mapOf(
        "BTC-USDT" to MarketDepth(
            "BTC-USDT", "BTC", "USDT",
            SpotOrderBook("BTC-USDT", now,
                listOf(OrderBookLevel(99.0, 1.0)),
                listOf(OrderBookLevel(100.0, 1.0)))
        ),
        "ETH-BTC" to MarketDepth(
            "ETH-BTC", "ETH", "BTC",
            SpotOrderBook("ETH-BTC", now,
                listOf(OrderBookLevel(0.049, 10.0)),
                listOf(OrderBookLevel(0.05, 10.0)))
        ),
        "ETH-USDT" to MarketDepth(
            "ETH-USDT", "ETH", "USDT",
            SpotOrderBook("ETH-USDT", now,
                listOf(OrderBookLevel(6.5, 10.0)),
                listOf(OrderBookLevel(6.6, 10.0)))
        )
    )

    private val path = listOf("USDT", "BTC", "ETH", "USDT")
    private val instruments = listOf("BTC-USDT", "ETH-BTC", "ETH-USDT")
    private val fees = instruments.associateWith { 0.01 }

    @Test fun feeTypeZeroChargesEachReceivedCurrency() {
        val result = OrderBookDepthSimulator.simulateCycle(
            path = path,
            marketIds = instruments,
            markets = markets(),
            startAmount = 10.0,
            feeRate = 0.0,
            now = now,
            feeRatesByInstrument = fees,
            feeType = 0
        )

        assertEquals(listOf("BTC", "ETH", "USDT"), result.legs.map { it.feeCurrency })
        assertTrue(result.legs.all { it.feeAmount > 0.0 })
        assertTrue(result.finalAmount < 13.0)
    }

    @Test fun feeTypeOneChargesQuoteCurrencyIncludingBuyLegInput() {
        val result = OrderBookDepthSimulator.simulateCycle(
            path = path,
            marketIds = instruments,
            markets = markets(),
            startAmount = 10.0,
            feeRate = 0.0,
            now = now,
            feeRatesByInstrument = fees,
            feeType = 1
        )

        assertEquals(listOf("USDT", "BTC", "USDT"), result.legs.map { it.feeCurrency })
        assertTrue(result.legs.all { it.feeAmount > 0.0 })
        assertTrue(result.legs[0].outputAfterFee > result.legs[0].outputBeforeFee - 1e-9)
        assertTrue(result.legs[0].dustAmount >= 0.0)
        assertTrue(result.finalAmount < 13.0)
    }
}
