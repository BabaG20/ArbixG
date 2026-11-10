package com.arbixg.app.data

import java.math.BigDecimal
import java.math.RoundingMode

data class SizedCycleOrder(
    val instrumentId: String,
    val side: String,
    val inputCurrency: String,
    val outputCurrency: String,
    val orderSizeBase: BigDecimal,
    val limitPrice: BigDecimal,
    val inputConsumed: BigDecimal,
    val unspentInput: BigDecimal,
    val quoteNotional: BigDecimal
)

/**
 * Sizes each leg from the actual currency output of the preceding confirmed fill.
 * All quantities use BigDecimal and are rounded down to the exchange lot size.
 * Missing minNotional or exchange filters must block sizing; no defaults are guessed.
 */
object CycleOrderSizer {
    fun size(
        instrumentId: String,
        side: String,
        inputCurrency: String,
        availableInput: BigDecimal,
        limitPrice: BigDecimal,
        lotSize: BigDecimal,
        minimumSize: BigDecimal,
        minimumNotional: BigDecimal?,
        tickSize: BigDecimal
    ): SizedCycleOrder {
        require(instrumentId.matches(Regex("[A-Z0-9]+-[A-Z0-9]+"))) { "Invalid Spot instrument ID." }
        require(side == "buy" || side == "sell") { "Side must be buy or sell." }
        require(availableInput > BigDecimal.ZERO) { "Available input amount must be positive." }
        require(limitPrice > BigDecimal.ZERO) { "Limit price must be positive." }
        require(lotSize > BigDecimal.ZERO && minimumSize > BigDecimal.ZERO && tickSize > BigDecimal.ZERO) {
            "OKX lotSz, minSz and tickSz must be positive."
        }
        require(minimumNotional != null && minimumNotional > BigDecimal.ZERO) {
            "OKX minimum notional is missing; this order is not eligible for execution."
        }
        require(limitPrice.remainder(tickSize).compareTo(BigDecimal.ZERO) == 0) {
            "Limit price is not aligned to OKX tickSz."
        }

        val (base, quote) = instrumentId.split("-")
        val outputCurrency = when (side) {
            "buy" -> {
                require(inputCurrency == quote) { "A buy order must spend the instrument quote currency." }
                base
            }
            else -> {
                require(inputCurrency == base) { "A sell order must spend the instrument base currency." }
                quote
            }
        }

        val rawBaseSize = if (side == "buy") availableInput.divide(limitPrice, 24, RoundingMode.DOWN)
            else availableInput
        val size = rawBaseSize.divideToIntegralValue(lotSize).multiply(lotSize).stripTrailingZeros()
        require(size > BigDecimal.ZERO) { "Available input is too small for one OKX lot." }
        require(size >= minimumSize) { "Rounded order size is below OKX minSz." }

        val notional = size.multiply(limitPrice)
        require(notional >= minimumNotional) { "Order notional is below the OKX minimum notional." }
        val consumed = if (side == "buy") notional else size
        require(consumed <= availableInput) { "Rounded order would consume more than the available input." }

        return SizedCycleOrder(
            instrumentId = instrumentId,
            side = side,
            inputCurrency = inputCurrency,
            outputCurrency = outputCurrency,
            orderSizeBase = size,
            limitPrice = limitPrice.stripTrailingZeros(),
            inputConsumed = consumed.stripTrailingZeros(),
            unspentInput = availableInput.subtract(consumed).stripTrailingZeros(),
            quoteNotional = notional.stripTrailingZeros()
        )
    }
}
