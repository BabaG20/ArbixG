package com.arbixg.app.data

import java.math.BigDecimal

/**
 * Converts a fully filled OKX Spot order into the actual currency movement
 * for one cycle leg. Fees in either leg currency are accounted for exactly;
 * third-currency fees are rejected because their value cannot be guessed.
 */
data class CycleLegMovement(
    val inputCurrency: String,
    val inputConsumed: BigDecimal,
    val outputCurrency: String,
    val outputReceived: BigDecimal,
    val feeCurrency: String?,
    val absoluteFee: BigDecimal
)

object CycleFillMath {
    fun movement(
        order: ReconciledOrder,
        side: String,
        fromCurrency: String,
        toCurrency: String
    ): CycleLegMovement {
        require(order.status == ReconciledOrderStatus.FILLED) {
            "A cycle cannot advance until the exchange confirms a fully filled order."
        }
        require(side == "buy" || side == "sell") { "Order side must be buy or sell." }
        require(order.instrumentId.matches(Regex("[A-Z0-9]+-[A-Z0-9]+"))) {
            "Invalid Spot instrument ID."
        }
        val (base, quote) = order.instrumentId.split("-")
        val filledBase = decimal(order.cumulativeFilledSize, "cumulative filled size")
        val averagePrice = decimal(order.averageFillPrice ?: "", "average fill price")
        val rawFee = decimal(order.cumulativeFee, "cumulative fee")
        require(filledBase > BigDecimal.ZERO && averagePrice > BigDecimal.ZERO) {
            "A completed order must have positive filled size and average fill price."
        }
        val fee = rawFee.abs()
        val feeCurrency = order.feeCurrency?.takeIf { it.isNotBlank() }
        require(fee.compareTo(BigDecimal.ZERO) == 0 || feeCurrency != null) {
            "OKX reported a non-zero fee without a fee currency."
        }

        val input: String
        val output: String
        val grossOutput: BigDecimal
        val grossInput: BigDecimal
        when (side) {
            "buy" -> {
                require(fromCurrency == quote && toCurrency == base) {
                    "Buy leg currencies must flow from quote to base."
                }
                input = quote
                output = base
                grossInput = filledBase.multiply(averagePrice)
                grossOutput = filledBase
            }
            else -> {
                require(fromCurrency == base && toCurrency == quote) {
                    "Sell leg currencies must flow from base to quote."
                }
                input = base
                output = quote
                grossInput = filledBase
                grossOutput = filledBase.multiply(averagePrice)
            }
        }

        val inputConsumed = if (feeCurrency == input) grossInput.add(fee) else grossInput
        val outputReceived = if (feeCurrency == output) grossOutput.subtract(fee) else grossOutput
        require(feeCurrency == null || feeCurrency == input || feeCurrency == output) {
            "Fee was charged in $feeCurrency, outside this leg's input/output currencies."
        }
        require(inputConsumed > BigDecimal.ZERO && outputReceived > BigDecimal.ZERO) {
            "Fee-adjusted cycle leg movement is not positive."
        }

        return CycleLegMovement(
            inputCurrency = input,
            inputConsumed = inputConsumed.stripTrailingZeros(),
            outputCurrency = output,
            outputReceived = outputReceived.stripTrailingZeros(),
            feeCurrency = feeCurrency,
            absoluteFee = fee.stripTrailingZeros()
        )
    }

    private fun decimal(value: String, label: String): BigDecimal =
        value.toBigDecimalOrNull() ?: error("$label is missing or invalid.")
}
