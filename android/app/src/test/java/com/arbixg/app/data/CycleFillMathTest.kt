package com.arbixg.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CycleFillMathTest {
    private fun assertDecimalEquals(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }

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
        assertDecimalEquals("600", movement.inputConsumed)
        assertDecimalEquals("0.00999", movement.outputReceived)
        assertEquals("BTC", movement.outputCurrency)
    }

    @Test fun sellSubtractsQuoteCurrencyFeeFromProceeds() {
        val movement = CycleFillMath.movement(
            filled("BTC-USDT", "0.01", "60000", "-0.6", "USDT"),
            "sell", "BTC", "USDT"
        )
        assertDecimalEquals("0.01", movement.inputConsumed)
        assertDecimalEquals("599.4", movement.outputReceived)
    }

    @Test fun buyAddsQuoteCurrencyFeeToInputConsumed() {
        val movement = CycleFillMath.movement(
            filled("BTC-USDT", "0.01", "60000", "-0.5", "USDT"),
            "buy", "USDT", "BTC"
        )
        assertDecimalEquals("600.5", movement.inputConsumed)
        assertDecimalEquals("0.01", movement.outputReceived)
    }

    @Test fun sellAddsBaseCurrencyFeeToInputConsumed() {
        val movement = CycleFillMath.movement(
            filled("BTC-USDT", "0.01", "60000", "-0.0001", "BTC"),
            "sell", "BTC", "USDT"
        )
        assertDecimalEquals("0.0101", movement.inputConsumed)
        assertDecimalEquals("600", movement.outputReceived)
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
