package com.arbixg.app.data

import org.junit.Assert.*
import org.junit.Test

class InstrumentRulesValidatorTest {
    private val path = listOf("USDT", "BTC", "ETH", "USDT")
    private val ids = listOf("BTC-USDT", "ETH-BTC", "ETH-USDT")
    private val now = 1_800_000_000_000L

    private val books = mapOf(
        "BTC-USDT" to SpotOrderBook("BTC-USDT", now, listOf(OrderBookLevel(100.0, 1.0)), listOf(OrderBookLevel(101.0, 1.0))),
        "ETH-BTC" to SpotOrderBook("ETH-BTC", now, listOf(OrderBookLevel(0.06, 10.0)), listOf(OrderBookLevel(0.061, 10.0))),
        "ETH-USDT" to SpotOrderBook("ETH-USDT", now, listOf(OrderBookLevel(7.0, 10.0)), listOf(OrderBookLevel(7.1, 10.0)))
    )

    private fun rules() = ids.associateWith { id ->
        val (base, quote) = id.split("-")
        SpotInstrumentRules(id, base, quote, "0.0001", "0.0001", "0.0001", "live")
    }

    private fun legs() = listOf(
        DepthLegResult("BTC-USDT", "USDT", "BTC", 10.0, 0.09900990099009901, 0.0, 0.09900990099009901, 1),
        DepthLegResult("ETH-BTC", "BTC", "ETH", 0.09900990099009901, 1.6231131309852297, 0.0, 1.6231131309852297, 1),
        DepthLegResult("ETH-USDT", "ETH", "USDT", 1.6231131309852297, 11.361791916896608, 0.0, 11.361791916896608, 1)
    )

    @Test fun rejectsQuantityThatNeedsLotRoundingInsteadOfClaimingExecutable() {
        val result = InstrumentRulesValidator.validate(path, ids, books, rules(), legs())
        assertFalse(result.passed)
        assertTrue(result.message.contains("lotSz rounding"))
    }

    @Test fun rejectsMissingInstrumentRules() {
        val result = InstrumentRulesValidator.validate(path, ids, books, rules() - "ETH-BTC", legs())
        assertFalse(result.passed)
        assertTrue(result.message.contains("rules are missing"))
    }

    @Test fun rejectsPriceNotAlignedToTickSize() {
        val changed = books.toMutableMap()
        changed["BTC-USDT"] = books.getValue("BTC-USDT").copy(
            asks = listOf(OrderBookLevel(101.00005, 1.0))
        )
        val alignedLegs = listOf(
            legs()[0].copy(outputBeforeFee = 0.099),
            legs()[1].copy(inputAmount = 0.099, outputBeforeFee = 1.6231),
            legs()[2].copy(inputAmount = 1.6231)
        )
        val result = InstrumentRulesValidator.validate(path, ids, changed, rules(), alignedLegs)
        assertFalse(result.passed)
        assertTrue(result.message.contains("tickSz"))
    }
}
