package com.arbixg.app.data

import org.junit.Assert.*
import org.junit.Test

class OkxAssetExclusionTest {
    private val tickers = listOf(
        SpotTicker("BTC-USDT", "BTC", "USDT", "50000", "49999", "50001", 1L),
        SpotTicker("ETH-BTC", "ETH", "BTC", "0.05", "0.049", "0.051", 1L),
        SpotTicker("ETH-USDT", "ETH", "USDT", "2500", "2499", "2501", 1L),
        SpotTicker("SOL-USDT", "SOL", "USDT", "100", "99", "101", 1L),
        SpotTicker("SOL-BTC", "SOL", "BTC", "0.002", "0.0019", "0.0021", 1L),
        SpotTicker("SOL-ETH", "SOL", "ETH", "0.04", "0.039", "0.041", 1L)
    )
    private val btcCycle = TriangularCandidate(
        listOf("USDT", "BTC", "ETH", "USDT"),
        listOf("BTC-USDT", "ETH-BTC", "ETH-USDT"),
        1.0,
        "FEE_AND_DEPTH_NOT_VALIDATED"
    )
    private val solEthCycle = TriangularCandidate(
        listOf("USDT", "SOL", "ETH", "USDT"),
        listOf("SOL-USDT", "SOL-ETH", "ETH-USDT"),
        0.4,
        "FEE_AND_DEPTH_NOT_VALIDATED"
    )
    private val solCycle = TriangularCandidate(
        listOf("USDT", "SOL", "BTC", "USDT"),
        listOf("SOL-USDT", "SOL-BTC", "BTC-USDT"),
        0.5,
        "FEE_AND_DEPTH_NOT_VALIDATED"
    )

    @Test fun excludesCyclesTouchingSpotHeldBaseCurrencies() {
        val snapshot = OkxAssetExclusionSnapshot(setOf("BTC"), emptySet(), emptySet(), 10L, true)
        val result = OpportunityAssetFilter.apply(listOf(btcCycle, solEthCycle), tickers, snapshot)
        assertEquals(listOf(solEthCycle), result.included)
        assertEquals(listOf(btcCycle), result.excluded)
    }

    @Test fun excludesCyclesTouchingEarnHeldCurrencies() {
        val snapshot = OkxAssetExclusionSnapshot(emptySet(), setOf("ETH"), emptySet(), 10L, true)
        val result = OpportunityAssetFilter.apply(listOf(btcCycle, solCycle), tickers, snapshot)
        assertEquals(listOf(solCycle), result.included)
        assertEquals(listOf(btcCycle), result.excluded)
    }

    @Test fun excludesExactInstrumentWithAnOpenSpotOrder() {
        val snapshot = OkxAssetExclusionSnapshot(emptySet(), emptySet(), setOf("SOL-USDT"), 10L, true)
        val result = OpportunityAssetFilter.apply(listOf(solCycle), tickers, snapshot)
        assertTrue(result.included.isEmpty())
        assertTrue(result.blockedReasonsByPath.values.flatten().any { it.contains("open-spot-order") })
    }

    @Test fun doesNotBlockUsdtQuoteBalanceAsHeldBaseAsset() {
        val snapshot = OkxAssetExclusionSnapshot(setOf("USDT"), emptySet(), emptySet(), 10L, true)
        val result = OpportunityAssetFilter.apply(listOf(btcCycle), tickers, snapshot)
        assertEquals(1, result.included.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesToFilterFromIncompleteAccountSnapshot() {
        OpportunityAssetFilter.apply(
            listOf(btcCycle),
            tickers,
            OkxAssetExclusionSnapshot(emptySet(), emptySet(), emptySet(), 10L, false)
        )
    }

    @Test fun stakingReceiptTokensAlsoBlockUnderlyingAssetCycles() {
        val snapshot = OkxAssetExclusionSnapshot(emptySet(), setOf("BETH", "OKSOL"), emptySet(), 10L, true)
        assertTrue("ETH" in snapshot.blockedBaseCurrencies)
        assertTrue("SOL" in snapshot.blockedBaseCurrencies)
        assertTrue("BETH" in snapshot.blockedBaseCurrencies)
        assertTrue("OKSOL" in snapshot.blockedBaseCurrencies)
    }

    @Test fun invalidEarnAmountsAreNotCountedAsHoldings() {
        assertTrue(OkxEarnHolding("BTC", "0", "Simple Earn").hasPositiveAmount().not())
        assertTrue(OkxEarnHolding("BTC", "bad", "Simple Earn").hasPositiveAmount().not())
        assertTrue(OkxEarnHolding("BTC", "0.001", "Simple Earn").hasPositiveAmount())
    }
}

