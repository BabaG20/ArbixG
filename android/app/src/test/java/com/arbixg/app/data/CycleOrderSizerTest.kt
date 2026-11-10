package com.arbixg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CycleOrderSizerTest {
    private val lot = BigDecimal("0.001")
    private val minSize = BigDecimal("0.001")
    private val minNotional = BigDecimal("5")
    private val tick = BigDecimal("0.01")

    @Test fun sizesBuyFromActualQuoteProceedsAndLeavesDust() {
        val sized = CycleOrderSizer.size(
            "ETH-USDT", "buy", "USDT", BigDecimal("101.23"),
            BigDecimal("2000.00"), lot, minSize, minNotional, tick
        )
        assertEquals(BigDecimal("0.05"), sized.orderSizeBase)
        assertEquals(BigDecimal("100"), sized.inputConsumed)
        assertEquals(BigDecimal("1.23"), sized.unspentInput)
        assertEquals("ETH", sized.outputCurrency)
    }

    @Test fun sizesSellFromActualBaseProceeds() {
        val sized = CycleOrderSizer.size(
            "ETH-USDT", "sell", "ETH", BigDecimal("0.0537"),
            BigDecimal("2000.00"), lot, minSize, minNotional, tick
        )
        assertEquals(BigDecimal("0.053"), sized.orderSizeBase)
        assertEquals(BigDecimal("0.053"), sized.inputConsumed)
        assertEquals(BigDecimal("0.0007"), sized.unspentInput)
        assertEquals(BigDecimal("106"), sized.quoteNotional)
    }

    @Test fun refusesToGuessMissingMinimumNotional() {
        assertTrue(runCatching {
            CycleOrderSizer.size(
                "ETH-USDT", "buy", "USDT", BigDecimal("100"),
                BigDecimal("2000"), lot, minSize, null, tick
            )
        }.isFailure)
    }

    @Test fun rejectsInvalidTickAndTooSmallNotional() {
        assertTrue(runCatching {
            CycleOrderSizer.size(
                "ETH-USDT", "buy", "USDT", BigDecimal("100"),
                BigDecimal("2000.005"), lot, minSize, minNotional, tick
            )
        }.isFailure)
        assertTrue(runCatching {
            CycleOrderSizer.size(
                "ETH-USDT", "buy", "USDT", BigDecimal("4"),
                BigDecimal("2000.00"), lot, minSize, minNotional, tick
            )
        }.isFailure)
    }

    @Test fun rejectsWrongInputCurrencyAndInsufficientLot() {
        assertTrue(runCatching {
            CycleOrderSizer.size(
                "ETH-USDT", "buy", "ETH", BigDecimal("1"),
                BigDecimal("2000"), lot, minSize, minNotional, tick
            )
        }.isFailure)
        assertTrue(runCatching {
            CycleOrderSizer.size(
                "ETH-USDT", "sell", "ETH", BigDecimal("0.0005"),
                BigDecimal("2000"), lot, minSize, minNotional, tick
            )
        }.isFailure)
    }
}
