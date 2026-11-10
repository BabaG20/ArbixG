package com.arbixg.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DepthValidationEngineTest {
    private fun candidate() = TriangularCandidate(
        path = listOf("USDT", "BTC", "ETH", "USDT"),
        instruments = listOf("BTC-USDT", "ETH-BTC", "ETH-USDT"),
        grossProfitPct = 1.0,
        feeStatus = "FEE_AND_DEPTH_NOT_VALIDATED"
    )

    private fun books(now: Long) = mapOf(
        "BTC-USDT" to SpotOrderBook("BTC-USDT", now,
            listOf(OrderBookLevel(99.0, 1.0)), listOf(OrderBookLevel(100.0, 1.0))),
        "ETH-BTC" to SpotOrderBook("ETH-BTC", now,
            listOf(OrderBookLevel(0.049, 10.0)), listOf(OrderBookLevel(0.05, 10.0))),
        "ETH-USDT" to SpotOrderBook("ETH-USDT", now,
            listOf(OrderBookLevel(6.5, 10.0)), listOf(OrderBookLevel(6.6, 10.0)))
    )

    private fun rules() = listOf(
        SpotInstrumentRules("BTC-USDT", "BTC", "USDT", "0.0001", "0.0001", "1", "live"),
        SpotInstrumentRules("ETH-BTC", "ETH", "BTC", "0.0001", "0.0001", "0.001", "live"),
        SpotInstrumentRules("ETH-USDT", "ETH", "USDT", "0.0001", "0.0001", "0.1", "live")
    ).associateBy { it.instrumentId }

    @Test fun validatesTenUsdtDepthButDoesNotClaimNetProfitWithoutFee() = runBlocking {
        val now = 1_800_000_000_000L
        val source = books(now)
        val engine = DepthValidationEngine(loadOrderBook = { source.getValue(it) })
        val result = engine.validateUsdtCandidates(listOf(candidate()), now = now, instrumentRules = rules()).single()

        assertEquals(DepthValidationStatus.DEPTH_PASSED_WITH_RULES_GROSS_ONLY, result.status)
        assertTrue("Depth simulation should return the calculated output amount", result.finalAmount!! > 10.0)
        assertNull(result.netReturnPct)
        assertTrue(result.message.contains("minNotional is not exposed"))
    }

    @Test fun reportsInsufficientDepthAsRejected() = runBlocking {
        val now = 1_800_000_000_000L
        val source = books(now).toMutableMap()
        source["BTC-USDT"] = source.getValue("BTC-USDT").copy(
            asks = listOf(OrderBookLevel(101.0, 0.0001))
        )
        val engine = DepthValidationEngine(loadOrderBook = { source.getValue(it) })
        val result = engine.validateUsdtCandidates(listOf(candidate()), now = now).single()

        assertEquals(DepthValidationStatus.DEPTH_REJECTED, result.status)
        assertNull(result.finalAmount)
    }

    @Test fun doesNotRequestNonUsdtStartingCycles() = runBlocking {
        var requests = 0
        val engine = DepthValidationEngine(loadOrderBook = {
            requests++
            error("Should not request a book")
        })
        val nonUsdt = candidate().copy(path = listOf("BTC", "ETH", "USDT", "BTC"))
        assertTrue(engine.validateUsdtCandidates(listOf(nonUsdt)).isEmpty())
        assertEquals(0, requests)
    }

    @Test fun fetchesEachUniqueInstrumentOnlyOnce() = runBlocking {
        val now = 1_800_000_000_000L
        val source = books(now)
        val counts = mutableMapOf<String, Int>()
        val engine = DepthValidationEngine(loadOrderBook = {
            counts[it] = (counts[it] ?: 0) + 1
            source.getValue(it)
        })
        engine.validateUsdtCandidates(listOf(candidate(), candidate().copy(grossProfitPct = 0.5)), now = now, instrumentRules = rules())
        assertEquals(mapOf("BTC-USDT" to 1, "ETH-BTC" to 1, "ETH-USDT" to 1), counts)
    }
}
