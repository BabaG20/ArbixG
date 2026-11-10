package com.arbixg.app.data

import java.math.BigDecimal
import java.math.RoundingMode

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
    val feeCurrency: String = toCurrency,
    val levelsConsumed: Int,
    val executedBaseQuantity: Double = 0.0,
    val quoteAmount: Double = 0.0,
    val dustAmount: Double = 0.0,
    val dustCurrency: String = ""
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

/**
 * Three-leg L2 simulation. When live instrument rules are supplied, base
 * quantities are floored to lotSz using BigDecimal and the cycle is re-run
 * with the actual post-fee output of each leg. Residual input is reported as
 * dust and is never carried forward or counted as proceeds.
 *
 * OKX Spot feeType 0 charges the received currency; feeType 1 charges the
 * quote currency. The latter is reserved from quote input on buys and
 * deducted from quote output on sells. Fee rates are supplied per instrument.
 */
object OrderBookDepthSimulator {
    fun simulateCycle(
        path: List<String>,
        marketIds: List<String>,
        markets: Map<String, MarketDepth>,
        startAmount: Double,
        feeRate: Double,
        now: Long = System.currentTimeMillis(),
        maxBookAgeMs: Long = 2_000L,
        instrumentRules: Map<String, SpotInstrumentRules> = emptyMap(),
        feeRatesByInstrument: Map<String, Double>? = null,
        feeType: Int = 0
    ): DepthCycleResult {
        require(path.size == 4 && path.first() == path.last()) { "Path must be a closed three-currency cycle." }
        require(path.take(3).distinct().size == 3) { "Cycle must contain three different currencies." }
        require(marketIds.size == 3 && marketIds.distinct().size == 3) { "Exactly three different instruments are required." }
        require(startAmount.isFinite() && startAmount > 0.0) { "Starting amount must be finite and positive." }
        require(feeRate.isFinite() && feeRate >= 0.0 && feeRate < 1.0) { "Fee rate must be between 0 and 1." }
        require(maxBookAgeMs >= 0L) { "Maximum order-book age cannot be negative." }
        require(feeType in 0..1) { "OKX Spot feeType must be 0 (received currency) or 1 (quote currency)." }
        feeRatesByInstrument?.values?.forEach { rate ->
            require(rate.isFinite() && rate >= 0.0 && rate < 1.0) { "Each instrument fee rate must be between 0 and 1." }
        }
        if (feeRatesByInstrument != null) {
            require(marketIds.all { feeRatesByInstrument.containsKey(it) }) { "A taker fee rate is missing for a cycle instrument." }
        }

        val gross = runCycle(path, marketIds, markets, startAmount, 0.0, emptyMap(), 0, now, maxBookAgeMs, instrumentRules)
        val net = if (feeRate == 0.0 && feeRatesByInstrument.isNullOrEmpty()) gross else
            runCycle(path, marketIds, markets, startAmount, feeRate, feeRatesByInstrument.orEmpty(), feeType, now, maxBookAgeMs, instrumentRules)
        return DepthCycleResult(
            startCurrency = path.first(),
            startAmount = startAmount,
            finalAmount = net.finalAmount,
            grossReturnPct = (gross.finalAmount / startAmount - 1.0) * 100.0,
            netReturnPct = (net.finalAmount / startAmount - 1.0) * 100.0,
            feeRate = feeRate,
            legs = net.legs
        )
    }

    private data class CycleRun(val finalAmount: Double, val legs: List<DepthLegResult>)

    private fun runCycle(
        path: List<String>,
        marketIds: List<String>,
        markets: Map<String, MarketDepth>,
        startAmount: Double,
        defaultFeeRate: Double,
        feeRatesByInstrument: Map<String, Double>,
        feeType: Int,
        now: Long,
        maxBookAgeMs: Long,
        rules: Map<String, SpotInstrumentRules>
    ): CycleRun {
        var amount = startAmount
        val legs = ArrayList<DepthLegResult>(3)
        for (index in 0 until 3) {
            val from = path[index]
            val to = path[index + 1]
            val id = marketIds[index]
            val market = markets[id] ?: error("Missing order book for $id.")
            require(market.instrumentId == id && market.orderBook.instrumentId == id) {
                "Order-book instrument mismatch for $id."
            }
            require(market.orderBook.isUsable(now, maxBookAgeMs)) {
                "Order book for $id is stale, crossed, empty, or incorrectly ordered."
            }
            require(
                (from == market.baseCurrency && to == market.quoteCurrency) ||
                    (from == market.quoteCurrency && to == market.baseCurrency)
            ) { "Instrument $id cannot convert $from to $to." }

            val feeRate = feeRatesByInstrument[id] ?: defaultFeeRate
            val rule = rules[id]
            val lot = rule?.lotSize?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            val minimum = rule?.minimumSize?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            if (rule != null && (lot == null || minimum == null)) error("$id: invalid lotSz or minSz metadata.")

            val conversion = if (from == market.baseCurrency) {
                val requestedBase = BigDecimal.valueOf(amount)
                val executedBase = lot?.let { floorToStep(requestedBase, it) } ?: requestedBase
                if (minimum != null && executedBase < minimum) error("$id: rounded sell quantity is below minSz.")
                if (executedBase <= BigDecimal.ZERO) error("$id: rounded sell quantity is zero.")
                val baseQty = executedBase.toDouble()
                val quote = consumeBids(baseQty, market.orderBook.bids, id)
                Conversion(
                    output = quote,
                    executedBase = baseQty,
                    quoteAmount = quote,
                    dust = (requestedBase - executedBase).max(BigDecimal.ZERO).toDouble(),
                    dustCurrency = if (requestedBase > executedBase) from else "",
                    levels = levelsNeededForSell(baseQty, market.orderBook.bids)
                )
            } else {
                // With feeType=1, OKX charges Spot fees in quote currency. Reserve
                // the fee from the quote budget before consuming asks.
                val quoteTradeBudget = if (feeType == 1) amount / (1.0 + feeRate) else amount
                val requestedQuote = BigDecimal.valueOf(quoteTradeBudget)
                val affordableBase = affordableBase(requestedQuote.toDouble(), market.orderBook.asks, id)
                val affordable = BigDecimal.valueOf(affordableBase)
                val executedBase = lot?.let { floorToStep(affordable, it) } ?: affordable
                if (minimum != null && executedBase < minimum) error("$id: rounded buy quantity is below minSz.")
                if (executedBase <= BigDecimal.ZERO) error("$id: rounded buy quantity is zero.")
                val baseQty = executedBase.toDouble()
                val quoteSpent = consumeAsksForBase(baseQty, market.orderBook.asks, id)
                val residual = (requestedQuote - BigDecimal.valueOf(quoteSpent)).max(BigDecimal.ZERO)
                val allowedDust = lot?.let { it.multiply(BigDecimal.valueOf(marginalAskPrice(baseQty, market.orderBook.asks, id))) }
                if ((allowedDust == null && residual > BigDecimal.valueOf(EPSILON)) ||
                    (allowedDust != null && residual > allowedDust.add(BigDecimal.valueOf(EPSILON)))) {
                    error("Insufficient ask depth for $id; quote budget could not be filled within one lot-size remainder.")
                }
                Conversion(
                    output = baseQty,
                    executedBase = baseQty,
                    quoteAmount = quoteSpent,
                    dust = residual.toDouble(),
                    dustCurrency = if (residual > BigDecimal.ZERO) from else "",
                    levels = levelsNeededForBuyBase(baseQty, market.orderBook.asks)
                )
            }

            val quoteFeeOnBuy = feeType == 1 && from == market.quoteCurrency
            val fee = if (quoteFeeOnBuy) conversion.quoteAmount * feeRate else conversion.output * feeRate
            val afterFee = if (quoteFeeOnBuy) conversion.output else conversion.output - fee
            val feeCurrency = if (feeType == 1) market.quoteCurrency else to
            require(afterFee.isFinite() && afterFee > 0.0) { "Invalid output after fee for $id." }
            legs += DepthLegResult(
                instrumentId = id,
                fromCurrency = from,
                toCurrency = to,
                inputAmount = amount,
                outputBeforeFee = conversion.output,
                feeAmount = fee,
                outputAfterFee = afterFee,
                feeCurrency = feeCurrency,
                levelsConsumed = conversion.levels,
                executedBaseQuantity = conversion.executedBase,
                quoteAmount = conversion.quoteAmount,
                dustAmount = if (quoteFeeOnBuy) {
                    (amount - conversion.quoteAmount - fee).coerceAtLeast(0.0)
                } else conversion.dust,
                dustCurrency = if (quoteFeeOnBuy && amount > conversion.quoteAmount + fee) from else conversion.dustCurrency
            )
            amount = afterFee
        }
        return CycleRun(amount, legs)
    }

    private data class Conversion(
        val output: Double,
        val executedBase: Double,
        val quoteAmount: Double,
        val dust: Double,
        val dustCurrency: String,
        val levels: Int
    )

    private fun floorToStep(value: BigDecimal, step: BigDecimal): BigDecimal =
        value.divide(step, 0, RoundingMode.FLOOR).multiply(step)

    private fun affordableBase(quoteBudget: Double, asks: List<OrderBookLevel>, id: String): Double {
        var remaining = quoteBudget
        var totalBase = 0.0
        for (level in asks) {
            validateLevel(level, id)
            val spent = minOf(remaining, level.price * level.size)
            totalBase += spent / level.price
            remaining -= spent
            if (remaining <= EPSILON) break
        }
        if (totalBase <= 0.0) error("Insufficient ask depth for $id.")
        return totalBase
    }

    private fun consumeBids(baseAmount: Double, bids: List<OrderBookLevel>, id: String): Double {
        var remaining = baseAmount
        var quoteOutput = 0.0
        for (level in bids) {
            validateLevel(level, id)
            val filled = minOf(remaining, level.size)
            quoteOutput += filled * level.price
            remaining -= filled
            if (remaining <= EPSILON) return quoteOutput
        }
        error("Insufficient bid depth for $id; unfilled base amount=$remaining.")
    }

    private fun consumeAsksForBase(baseAmount: Double, asks: List<OrderBookLevel>, id: String): Double {
        var remaining = baseAmount
        var quoteSpent = 0.0
        for (level in asks) {
            validateLevel(level, id)
            val filled = minOf(remaining, level.size)
            quoteSpent += filled * level.price
            remaining -= filled
            if (remaining <= EPSILON) return quoteSpent
        }
        error("Insufficient ask depth for $id; unfilled base amount=$remaining.")
    }


    private fun marginalAskPrice(baseAmount: Double, asks: List<OrderBookLevel>, id: String): Double {
        var remaining = baseAmount
        for (level in asks) {
            validateLevel(level, id)
            remaining -= minOf(remaining, level.size)
            if (remaining <= EPSILON) return level.price
        }
        error("Insufficient ask depth for $id.")
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

    private fun levelsNeededForBuyBase(amount: Double, levels: List<OrderBookLevel>): Int {
        var remaining = amount
        var used = 0
        for (level in levels) {
            used++
            remaining -= minOf(remaining, level.size)
            if (remaining <= EPSILON) return used
        }
        return used
    }

    private fun validateLevel(level: OrderBookLevel, id: String) {
        require(level.price.isFinite() && level.size.isFinite() && level.price > 0.0 && level.size > 0.0) {
            "Invalid order-book level for $id."
        }
    }

    private const val EPSILON = 1e-10
}
