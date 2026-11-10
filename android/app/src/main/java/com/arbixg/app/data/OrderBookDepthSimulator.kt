package com.arbixg.app.data

/**
 * Simulates a three-leg conversion against the supplied L2 snapshot.
 *
 * Fee rate is explicit and must come from the user's actual OKX account fee tier.
 * This class does not round to lot/tick rules and never submits orders; a successful
 * result is a depth estimate, not an executable-profit guarantee.
 */
data class MarketDepth(
    val instrumentId: String,
    val baseCurrency: String,
    val quoteCurrency: String,
    val orderBook: SpotOrderBook
)

data class DepthLegResult(
    val instrumentId: String,
    val fromCurrency: String,
    val toCurrency: String,
    val inputAmount: Double,
    val outputBeforeFee: Double,
    val feeAmount: Double,
    val outputAfterFee: Double,
    val levelsConsumed: Int
)

data class DepthCycleResult(
    val startCurrency: String,
    val startAmount: Double,
    val finalAmount: Double,
    val grossReturnPct: Double,
    val netReturnPct: Double,
    val feeRate: Double,
    val legs: List<DepthLegResult>
)

object OrderBookDepthSimulator {
    /**
     * path must contain A -> B -> C -> A and marketIds must identify the
     * corresponding three instruments in the same order.
     */
    fun simulateCycle(
        path: List<String>,
        marketIds: List<String>,
        markets: Map<String, MarketDepth>,
        startAmount: Double,
        feeRate: Double
    ): DepthCycleResult {
        require(path.size == 4 && path.first() == path.last()) { "Path must be a closed three-currency cycle." }
        require(path.take(3).distinct().size == 3) { "Cycle must contain three different currencies." }
        require(marketIds.size == 3 && marketIds.distinct().size == 3) { "Exactly three different instruments are required." }
        require(startAmount.isFinite() && startAmount > 0.0) { "Starting amount must be finite and positive." }
        require(feeRate.isFinite() && feeRate >= 0.0 && feeRate < 1.0) { "Fee rate must be between 0 and 1." }

        var amount = startAmount
        var grossAmount = startAmount
        val legs = ArrayList<DepthLegResult>(3)
        for (index in 0 until 3) {
            val from = path[index]
            val to = path[index + 1]
            val instrumentId = marketIds[index]
            val market = markets[instrumentId] ?: error("Missing order book for $instrumentId.")
            require(market.instrumentId == instrumentId && market.orderBook.instrumentId == instrumentId) {
                "Order-book instrument mismatch for $instrumentId."
            }
            val beforeFee = when {
                from == market.baseCurrency && to == market.quoteCurrency ->
                    consumeBids(amount, market.orderBook.bids, instrumentId)
                from == market.quoteCurrency && to == market.baseCurrency ->
                    consumeAsks(amount, market.orderBook.asks, instrumentId)
                else -> error("Instrument $instrumentId cannot convert $from to $to.")
            }
            val grossOutput = convert(grossAmount, from, to, market)
            grossAmount = grossOutput
            val fee = beforeFee * feeRate
            val afterFee = beforeFee - fee
            require(afterFee.isFinite() && afterFee > 0.0) { "Invalid output after fee for $instrumentId." }
            val consumed = if (from == market.baseCurrency) {
                levelsNeededForSell(amount, market.orderBook.bids)
            } else {
                levelsNeededForBuy(amount, market.orderBook.asks)
            }
            legs += DepthLegResult(instrumentId, from, to, amount, beforeFee, fee, afterFee, consumed)
            amount = afterFee
        }

        val grossReturn = grossAmount / startAmount
        val netReturn = amount / startAmount
        return DepthCycleResult(
            startCurrency = path.first(),
            startAmount = startAmount,
            finalAmount = amount,
            grossReturnPct = (grossReturn - 1.0) * 100.0,
            netReturnPct = (netReturn - 1.0) * 100.0,
            feeRate = feeRate,
            legs = legs
        )
    }

    private fun convert(amount: Double, from: String, to: String, market: MarketDepth): Double =
        when {
            from == market.baseCurrency && to == market.quoteCurrency ->
                consumeBids(amount, market.orderBook.bids, market.instrumentId)
            from == market.quoteCurrency && to == market.baseCurrency ->
                consumeAsks(amount, market.orderBook.asks, market.instrumentId)
            else -> error("Instrument ${market.instrumentId} cannot convert $from to $to.")
        }

    private fun consumeBids(baseAmount: Double, bids: List<OrderBookLevel>, instrumentId: String): Double {
        var remaining = baseAmount
        var quoteOutput = 0.0
        for (level in bids) {
            validateLevel(level, instrumentId)
            val filled = minOf(remaining, level.size)
            quoteOutput += filled * level.price
            remaining -= filled
            if (remaining <= EPSILON) return quoteOutput
        }
        error("Insufficient bid depth for $instrumentId; unfilled base amount=$remaining.")
    }

    private fun consumeAsks(quoteAmount: Double, asks: List<OrderBookLevel>, instrumentId: String): Double {
        var remainingQuote = quoteAmount
        var baseOutput = 0.0
        for (level in asks) {
            validateLevel(level, instrumentId)
            val availableQuote = level.price * level.size
            val spent = minOf(remainingQuote, availableQuote)
            baseOutput += spent / level.price
            remainingQuote -= spent
            if (remainingQuote <= EPSILON) return baseOutput
        }
        error("Insufficient ask depth for $instrumentId; unspent quote amount=$remainingQuote.")
    }

    private fun levelsNeededForSell(amount: Double, levels: List<OrderBookLevel>): Int {
        var remaining = amount
        var used = 0
        for (level in levels) {
            used++
            remaining -= minOf(remaining, level.size)
            if (remaining <= EPSILON) return used
        }
        return used
    }

    private fun levelsNeededForBuy(amount: Double, levels: List<OrderBookLevel>): Int {
        var remaining = amount
        var used = 0
        for (level in levels) {
            used++
            remaining -= minOf(remaining, level.price * level.size)
            if (remaining <= EPSILON) return used
        }
        return used
    }

    private fun validateLevel(level: OrderBookLevel, instrumentId: String) {
        require(level.price.isFinite() && level.size.isFinite() && level.price > 0.0 && level.size > 0.0) {
            "Invalid order-book level for $instrumentId."
        }
    }

    private const val EPSILON = 1e-10
}
