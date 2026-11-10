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
        DepthLegResult("BTC-USDT", "USDT", "BTC", 10.0, 0.099, 0.0, 0.099, 1, executedBaseQuantity = 0.099, quoteAmount = 10.0),
        DepthLegResult("ETH-BTC", "BTC", "ETH", 0.099, 1.62, 0.0, 1.62, 1, executedBaseQuantity = 1.62, quoteAmount = 0.099),
        DepthLegResult("ETH-USDT", "ETH", "USDT", 1.62, 11.34, 0.0, 11.34, 1, executedBaseQuantity = 1.62, quoteAmount = 11.34)
    )

    @Test fun acceptsAlreadyRoundedExecutedQuantities() {
        val result = InstrumentRulesValidator.validate(path, ids, books, rules(), legs())
        assertTrue(result.message, result.passed)
        assertTrue(result.message.contains("lotSz flooring"))
    }

    @Test fun rejectsExecutedQuantityThatIsNotLotAligned() {
        val changed = legs().toMutableList()
        changed[0] = changed[0].copy(executedBaseQuantity = 0.09905)
        val result = InstrumentRulesValidator.validate(path, ids, books, rules(), changed)
        assertFalse(result.passed)
        assertTrue(result.message.contains("lotSz"))
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
        val result = InstrumentRulesValidator.validate(path, ids, changed, rules(), legs())
        assertFalse(result.passed)
        assertTrue(result.message.contains("tickSz"))
    }
}
