package com.arbixg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriangularScannerTest {
    private fun ticker(
        id: String,
        base: String,
        quote: String,
        bid: String,
        ask: String
    ) = SpotTicker(
        instrumentId = id,
        baseCurrency = base,
        quoteCurrency = quote,
        last = bid,
        bid = bid,
        ask = ask,
        timestamp = 1_800_000_000_000L
    )

    @Test
    fun discoversGrossPositiveThreeMarketCycle() {
        val markets = listOf(
            ticker("BTC-USDT", "BTC", "USDT", "100", "101"),
            ticker("ETH-BTC", "ETH", "BTC", "0.060", "0.061"),
            ticker("ETH-USDT", "ETH", "USDT", "7.00", "7.05")
        )

        val candidates = TriangularScanner.scanAll(markets)

        assertTrue("Expected at least one gross-positive cycle", candidates.isNotEmpty())
        val candidate = candidates.first()
        assertEquals(3, candidate.instruments.size)
        assertEquals(candidate.path.first(), candidate.path.last())
        assertEquals(4, candidate.path.size)
        assertTrue(candidate.grossProfitPct > 0.0)
        assertEquals("FEE_AND_DEPTH_NOT_VALIDATED", candidate.feeStatus)
    }

    @Test
    fun rejectsNonPositiveAndCrossedQuotes() {
        val markets = listOf(
            ticker("BTC-USDT", "BTC", "USDT", "0", "101"),
            ticker("ETH-BTC", "ETH", "BTC", "0.062", "0.061"),
            ticker("ETH-USDT", "ETH", "USDT", "7.00", "7.05")
        )

        assertTrue(TriangularScanner.scanAll(markets).isEmpty())
    }

    @Test
    fun returnsNoCandidateWhenOnlyTwoMarketsExist() {
        val markets = listOf(
            ticker("BTC-USDT", "BTC", "USDT", "100", "101"),
            ticker("ETH-BTC", "ETH", "BTC", "0.060", "0.061")
        )

        assertTrue(TriangularScanner.scanAll(markets).isEmpty())
    }

    @Test
    fun limitsResultCountToRequestedMaximum() {
        val markets = listOf(
            ticker("BTC-USDT", "BTC", "USDT", "100", "101"),
            ticker("ETH-BTC", "ETH", "BTC", "0.060", "0.061"),
            ticker("ETH-USDT", "ETH", "USDT", "7.00", "7.05"),
            ticker("SOL-USDT", "SOL", "USDT", "10", "10.1"),
            ticker("SOL-BTC", "SOL", "BTC", "0.0065", "0.0066"),
            ticker("ETH-SOL", "ETH", "SOL", "0.70", "0.71")
        )

        assertTrue(TriangularScanner.scanAll(markets, limit = 1).size <= 1)
    }
}
