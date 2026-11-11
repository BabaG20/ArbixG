package com.arbixg.app.data

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DemoThreeLegCycleEngineTest {
    private val leg1 = DemoCycleLegSpec(
        instrumentId = "BTC-USDT",
        side = "buy",
        fromCurrency = "USDT",
        toCurrency = "BTC",
        limitPrice = BigDecimal("50000"),
        lotSize = BigDecimal("0.00001"),
        minimumSize = BigDecimal("0.00001"),
        minimumNotional = BigDecimal("5"),
        tickSize = BigDecimal("0.1")
    )
    private val leg2 = leg1.copy(
        instrumentId = "BTC-ETH",
        side = "sell",
        fromCurrency = "BTC",
        toCurrency = "ETH"
    )
    private val leg3 = leg1.copy(
        instrumentId = "ETH-USDT",
        side = "sell",
        fromCurrency = "ETH",
        toCurrency = "USDT"
    )

    @Test
    fun acceptsExactlyThreeLegClosedRoute() {
        val request = DemoThreeLegCycleRequest(
            cycleId = "cycle-001",
            startCurrency = "USDT",
            startAmount = BigDecimal("10"),
            legs = listOf(leg1, leg2, leg3),
            requiredFunds = mapOf("USDT" to BigDecimal("10")),
            marketSnapshotEpochMs = 1_800_000_000_000L,
            estimatedSlippageBps = BigDecimal("10")
        )
        assertEquals(3, request.legs.size)
        assertEquals("USDT", request.legs.last().toCurrency)
    }

    @Test
    fun rejectsRoutesThatDoNotReturnToStartingCurrency() {
        assertThrows(IllegalArgumentException::class.java) {
            DemoThreeLegCycleRequest(
                cycleId = "cycle-002",
                startCurrency = "USDT",
                startAmount = BigDecimal("10"),
                legs = listOf(leg1, leg2, leg2.copy(fromCurrency = "ETH", toCurrency = "BTC")),
                requiredFunds = mapOf("USDT" to BigDecimal("10")),
                marketSnapshotEpochMs = 1_800_000_000_000L,
                estimatedSlippageBps = BigDecimal("10")
            )
        }
    }

    @Test
    fun rejectsTwoLegCycle() {
        assertThrows(IllegalArgumentException::class.java) {
            DemoThreeLegCycleRequest(
                cycleId = "cycle-003",
                startCurrency = "USDT",
                startAmount = BigDecimal("10"),
                legs = listOf(leg1, leg2),
                requiredFunds = mapOf("USDT" to BigDecimal("10")),
                marketSnapshotEpochMs = 1_800_000_000_000L,
                estimatedSlippageBps = BigDecimal("10")
            )
        }
    }
}
