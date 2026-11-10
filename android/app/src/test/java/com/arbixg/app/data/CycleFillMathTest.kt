package com.arbixg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CycleFillMathTest {
    private fun filled(
        instrument: String,
        size: String,
        average: String,
        fee: String,
        feeCurrency: String?
    ) = ReconciledOrder(
        orderId = "demo-order",
        instrumentId = instrument,
        status = ReconciledOrderStatus.FILLED,
        requestedSize = size,
        cumulativeFilledSize = size,
        remainingSize = "0",
        averageFillPrice = average,
        cumulativeFee = fee,
        feeCurrency = feeCurrency,
        lastExchangeUpdateEpochMs = 1_767_225_600_123L
    )

    @Test fun buySubtractsBaseCurrencyFeeFromReceivedAsset() {
        val movement = CycleFillMath.movement(
            filled("BTC-USDT", "0.01", "60000", "-0.00001", "BTC"),
            "buy", "USDT", "BTC"
        )
        assertEquals(BigDecimal("600"), movement.inputConsumed)
        assertEquals(BigDecimal("0.00999"), movement.outputReceived)
        assertEquals("BTC", movement.outputCurrency)
    }

    @Test fun sellSubtractsQuoteCurrencyFeeFromProceeds() {
        val movement = CycleFillMath.movement(
            filled("BTC-USDT", "0.01", "60000", "-0.6", "USDT"),
            "sell", "BTC", "USDT"
        )
        assertEquals(BigDecimal("0.01"), movement.inputConsumed)
        assertEquals(BigDecimal("599.4"), movement.outputReceived)
    }

    @Test fun buyAddsQuoteCurrencyFeeToInputConsumed() {
        val movement = CycleFillMath.movement(
            filled("BTC-USDT", "0.01", "60000", "-0.5", "USDT"),
            "buy", "USDT", "BTC"
        )
        assertEquals(BigDecimal("600.5"), movement.inputConsumed)
        assertEquals(BigDecimal("0.01"), movement.outputReceived)
    }

    @Test fun sellAddsBaseCurrencyFeeToInputConsumed() {
        val movement = CycleFillMath.movement(
            filled("BTC-USDT", "0.01", "60000", "-0.0001", "BTC"),
            "sell", "BTC", "USDT"
        )
        assertEquals(BigDecimal("0.0101"), movement.inputConsumed)
        assertEquals(BigDecimal("600"), movement.outputReceived)
    }

    @Test fun rejectsThirdCurrencyFeeInsteadOfGuessingItsValue() {
        val result = runCatching {
            CycleFillMath.movement(
                filled("BTC-USDT", "0.01", "60000", "-0.1", "OKB"),
                "sell", "BTC", "USDT"
            )
        }
        assertTrue(result.isFailure)
    }

    @Test fun rejectsPartialFillAndWrongCurrencyDirection() {
        val partial = filled("BTC-USDT", "0.01", "60000", "0", null)
            .copy(status = ReconciledOrderStatus.PARTIALLY_FILLED)
        assertTrue(runCatching {
            CycleFillMath.movement(partial, "buy", "USDT", "BTC")
        }.isFailure)
        assertTrue(runCatching {
            CycleFillMath.movement(
                filled("BTC-USDT", "0.01", "60000", "0", null),
                "buy", "BTC", "USDT"
            )
        }.isFailure)
    }
}
